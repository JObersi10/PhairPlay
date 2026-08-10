package com.phairplay.airplay

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * RaopControlHandler — the RAOP control channel, the third UDP port of a classic realtime session.
 *
 * WHY THIS EXISTS AT ALL: PhairPlay advertised a control port in its SETUP response and then never
 * bound one. macOS Music sends its first sync packet to that port within milliseconds of RECORD;
 * the kernel answered with ICMP port-unreachable, Music concluded the receiver was broken, and tore
 * the session down before a single audio packet was sent. From the outside this looked exactly like
 * a decryption failure — the audio was silent — which is why it was chased as a FairPlay bug for so
 * long. The stream was never arriving in the first place.
 *
 * So the primary job of this class is simply TO EXIST AND ANSWER. Everything below is a bonus.
 *
 * Two packet types arrive here:
 *
 *  - **0x54 sync** (payload type 84). Sent every ~1s, and once immediately after RECORD with the
 *    marker bit set. Carries the RTP timestamp that corresponds to a given NTP instant — the same
 *    "this sample plays at this time" anchor that SETRATEANCHORTIME carries in AirPlay 2. Layout:
 *
 *        [0]     0x80, or 0x90 when the marker bit is set (the first sync of a stream)
 *        [1]     0xD4  (0x54 | 0x80)
 *        [2-3]   sequence
 *        [4-7]   RTP timestamp MINUS the sender's configured latency
 *        [8-15]  NTP timestamp of that moment
 *        [16-19] RTP timestamp of the NEXT packet the sender will send
 *
 *  - **0x56 retransmit reply** (payload type 86). The answer to a resend request, carrying a
 *    4-byte header followed by the original RTP packet. Parsed here so a caller can reinject it.
 *
 * We can also SEND on this socket: [requestResend] asks the sender to repeat a range of lost
 * sequence numbers. Realtime AirPlay has no TCP retransmission, so without this a dropped packet is
 * a permanent gap in the audio.
 */
class RaopControlHandler(
    /** Called for each sync packet: the RTP timestamp and the NTP instant it should play at. */
    private val onSync: (rtpTime: Long, ntpTimestamp: Long, nextRtpTime: Long) -> Unit = { _, _, _ -> },
    /** Called with a recovered RTP packet from a retransmit reply. */
    private val onRetransmit: (ByteArray) -> Unit = {},
) {

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var stopping = false

    /** Where to send resend requests — learned from the first packet the sender sends us. */
    @Volatile private var senderAddress: InetAddress? = null
    @Volatile private var senderPort: Int = 0

    /** The most recent sync anchor, for anyone who wants to check drift. */
    @Volatile var lastSyncRtpTime: Long = 0L
        private set

    @Volatile var lastSyncNtp: Long = 0L
        private set

    @Volatile var syncCount: Long = 0L
        private set

    val port: Int get() = socket?.localPort ?: -1

    fun start(scope: CoroutineScope, port: Int = CONTROL_PORT) {
        scope.launch(Dispatchers.IO) { runLoop(this, port) }
    }

    fun stop() {
        // Same ordering as TimingHandler: flag first, because close() unblocks receive() fast enough
        // that the catch block can still observe a non-null socket and log a shutdown as a crash.
        stopping = true
        runCatching { socket?.close() }
            .onFailure { Logger.e("Error closing control socket (non-fatal)", it) }
        socket = null
        senderAddress = null
    }

    private fun runLoop(scope: CoroutineScope, port: Int) {
        try {
            val sock = DatagramSocket(port)
            socket = sock
            Logger.i("RAOP control handler listening on UDP port $port")

            val buf = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(buf, buf.size)
            var firstPacket = true

            while (scope.isActive) {
                sock.receive(packet)
                if (firstPacket) {
                    Logger.i(
                        "Control: first packet from ${packet.address?.hostAddress}:${packet.port} " +
                            "(${packet.length}B, type 0x${(packet.data[1].toInt() and 0x7F).toString(16)})",
                    )
                    firstPacket = false
                }
                // Learn the return path from whoever talks to us; the SETUP header's control port is
                // the sender's LISTENING port and is not always the port it sends from.
                senderAddress = packet.address
                senderPort = packet.port
                handlePacket(packet.data, packet.length)
            }
        } catch (e: Exception) {
            if (!stopping && socket != null) Logger.e("Control handler error (unexpected)", e)
            else Logger.i("Control socket closed (expected during shutdown)")
        }
    }

    /** Exposed for tests: dispatches one control packet without needing a socket. */
    internal fun handlePacket(data: ByteArray, length: Int) {
        if (length < 4) return
        // The high bit of byte 1 is the RTP marker convention, not part of the type.
        when (val type = data[1].toInt() and 0x7F) {
            TYPE_SYNC -> handleSync(data, length)
            TYPE_RETRANSMIT -> handleRetransmit(data, length)
            else -> Logger.i("Control: ignoring packet type 0x${type.toString(16)} (${length}B)")
        }
    }

    private fun handleSync(data: ByteArray, length: Int) {
        if (length < SYNC_PACKET_SIZE) {
            Logger.w("Control: sync packet too short (${length}B) — ignoring")
            return
        }
        val rtpTime = readUint32(data, 4)
        val ntp = (readUint32(data, 8) shl 32) or readUint32(data, 12)
        val nextRtp = readUint32(data, 16)

        lastSyncRtpTime = rtpTime
        lastSyncNtp = ntp
        syncCount++

        // Only the first one is worth a log line; after that this fires every second forever.
        if (syncCount == 1L) {
            Logger.i("Control: first sync — rtp=$rtpTime next=$nextRtp (marker=${data[0].toInt() and 0x10 != 0})")
        }
        onSync(rtpTime, ntp, nextRtp)
    }

    private fun handleRetransmit(data: ByteArray, length: Int) {
        // 4-byte control header, then the original RTP packet exactly as it was first sent.
        if (length <= RETRANSMIT_HEADER) return
        onRetransmit(data.copyOfRange(RETRANSMIT_HEADER, length))
    }

    /**
     * Asks the sender to resend [count] packets starting at [firstSequence].
     *
     * Does nothing until the sender has sent us something, because until then we do not know where
     * to address the request — and guessing the wrong port would silently do nothing anyway.
     */
    fun requestResend(firstSequence: Int, count: Int): Boolean {
        val addr = senderAddress ?: return false
        val sock = socket ?: return false
        if (count <= 0) return false

        val req = ByteArray(RESEND_PACKET_SIZE)
        req[0] = 0x80.toByte()
        req[1] = (TYPE_RESEND or 0x80).toByte()
        // Resend requests carry a fixed sequence of 1; the sender keys off the range, not this field.
        req[2] = 0
        req[3] = 1
        req[4] = ((firstSequence shr 8) and 0xFF).toByte()
        req[5] = (firstSequence and 0xFF).toByte()
        req[6] = ((count shr 8) and 0xFF).toByte()
        req[7] = (count and 0xFF).toByte()

        return runCatching {
            sock.send(DatagramPacket(req, req.size, addr, senderPort))
            true
        }.onFailure { Logger.w("Control: resend request failed: ${it.message}") }.getOrDefault(false)
    }

    companion object {
        /** UDP port advertised as `control_port` in the SETUP response. */
        const val CONTROL_PORT = 6003

        private const val MAX_PACKET = 2048
        private const val SYNC_PACKET_SIZE = 20
        private const val RETRANSMIT_HEADER = 4
        private const val RESEND_PACKET_SIZE = 8

        private const val TYPE_SYNC = 0x54
        private const val TYPE_RESEND = 0x55
        private const val TYPE_RETRANSMIT = 0x56

        /** Reads a big-endian unsigned 32-bit value as a Long so it does not come back negative. */
        internal fun readUint32(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }
}
