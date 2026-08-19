package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * RtcpReport — parses the RTCP control packets that arrive alongside every RTP stream.
 *
 * WHY IT MATTERS: an RTCP **Sender Report** is the only place the sender states, authoritatively,
 * that "RTP timestamp X happened at wall-clock time T". Everything else in the pipeline infers that
 * relationship — our NTP probes measure it indirectly, the RAOP control channel's sync packets give
 * it for audio only, and A/V alignment for mirroring has been estimated from arrival time. A Sender
 * Report gives it directly, for the exact stream it describes.
 *
 * PhairPlay recognised RTCP on interleaved channel 1 and threw it away, which is why the mirroring
 * audio delay could be measured but not corrected: the correction term was arriving on a channel
 * the reader deliberately skipped.
 *
 * Packets are compound — several reports concatenated, each with its own length — so parsing has to
 * walk the whole datagram rather than reading the first header and stopping.
 */
object RtcpReport {

    /** RTCP payload types we act on. Others are parsed past and ignored. */
    const val PT_SENDER_REPORT = 200
    const val PT_RECEIVER_REPORT = 201
    const val PT_SOURCE_DESCRIPTION = 202
    const val PT_BYE = 203

    /**
     * A Sender Report: the sender's own clock, paired with the RTP timestamp it corresponds to.
     *
     * @param ssrc which stream this describes.
     * @param ntpTimestamp the sender's wall clock as a packed 64-bit NTP value.
     * @param rtpTimestamp the RTP timestamp for that same instant.
     * @param packetCount total packets sent — a gap versus what we received is real loss.
     * @param octetCount total payload bytes sent.
     */
    data class SenderReport(
        val ssrc: Long,
        val ntpTimestamp: Long,
        val rtpTimestamp: Long,
        val packetCount: Long,
        val octetCount: Long,
    ) {
        /** The sender's wall clock in microseconds since the Unix epoch. */
        val senderTimeUs: Long
            get() = TimingHandler.ntpToUs(ntpTimestamp ushr 32, ntpTimestamp and 0xFFFFFFFFL)
    }

    /**
     * Walks a compound RTCP packet and reports every Sender Report it contains.
     *
     * Malformed input is abandoned rather than guessed at: a truncated length field would otherwise
     * send the loop off the end of the buffer or, worse, spin forever on a zero length.
     */
    fun parse(data: ByteArray, length: Int = data.size, onSenderReport: (SenderReport) -> Unit) {
        var offset = 0
        while (offset + HEADER_BYTES <= length) {
            val version = (data[offset].toInt() and 0xC0) shr 6
            if (version != RTP_VERSION) {
                Logger.w("RTCP: version $version at offset $offset — abandoning packet")
                return
            }
            val payloadType = data[offset + 1].toInt() and 0xFF
            // The length field counts 32-bit words MINUS ONE, so the real size is (n + 1) * 4.
            val words = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val packetBytes = (words + 1) * 4
            if (packetBytes <= 0 || offset + packetBytes > length) {
                // Truncated or nonsensical. Stopping is right: the remainder cannot be located.
                return
            }

            if (payloadType == PT_SENDER_REPORT && packetBytes >= SENDER_REPORT_BYTES) {
                onSenderReport(
                    SenderReport(
                        ssrc = readUint32(data, offset + 4),
                        ntpTimestamp = (readUint32(data, offset + 8) shl 32) or readUint32(data, offset + 12),
                        rtpTimestamp = readUint32(data, offset + 16),
                        packetCount = readUint32(data, offset + 20),
                        octetCount = readUint32(data, offset + 24),
                    ),
                )
            }
            offset += packetBytes
        }
    }

    /** Reads a big-endian unsigned 32-bit value as a Long, so it never comes back negative. */
    private fun readUint32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private const val RTP_VERSION = 2
    private const val HEADER_BYTES = 4

    /** Header + SSRC + NTP(8) + RTP(4) + packet count(4) + octet count(4). */
    private const val SENDER_REPORT_BYTES = 28
}
