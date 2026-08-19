package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * AirPlayNtpClient — the receiver side of AirPlay 2 NTP timing.
 *
 * Unlike legacy AirPlay (where the sender probes the receiver), AirPlay 2 requires the RECEIVER to
 * poll the sender's timing port. macOS waits for the exchange to begin before it will send the
 * video stream SETUP, so without it the session stalls right after the key exchange.
 *
 * **This used to send requests and discard every reply.** That was enough to start a session —
 * mirror frames render on arrival — but it meant the receiver had no idea what time it was on the
 * sender's clock. Anything that needs a shared timeline (A/V drift correction, and every part of
 * multi-room: `SETPEERS`, `SETRATEANCHORTIME`, playing in step with another speaker) needs that
 * number, so the replies are now actually read.
 *
 * ## The exchange
 *
 * Four timestamps, the standard NTP set:
 *
 * | Symbol | Meaning                            | Where it comes from        |
 * |--------|------------------------------------|----------------------------|
 * | `t1`   | request left us                    | our clock, bytes 24–31 out |
 * | `t2`   | request reached the sender         | reply bytes 16–23          |
 * | `t3`   | reply left the sender              | reply bytes 24–31          |
 * | `t4`   | reply reached us                   | our clock, on receive      |
 *
 * ```
 * offset = ((t2 - t1) + (t3 - t4)) / 2      // sender clock minus ours
 * delay  = (t4 - t1) - (t3 - t2)            // round trip, excluding sender think-time
 * ```
 *
 * The offset is only as trustworthy as the path is symmetric, so a single sample means little on
 * Wi-Fi. We keep the sample with the **lowest delay** from a sliding window, which is textbook NTP
 * practice: the fastest round trip is the one least distorted by queueing, and its offset is
 * correspondingly the least wrong.
 *
 * ## Local clock
 *
 * Local timestamps come from a monotonic clock anchored once to wall time, not from
 * `System.currentTimeMillis()` per sample. A wall-clock adjustment mid-session (NTP on the TV, a
 * DST change) would otherwise appear as an instant multi-second offset jump and poison the window.
 *
 * ## What the sender's clock actually is (measured 2026-08-09)
 *
 * The offset against an iPhone comes out around **-56 years**, which is not a clock that is wrong —
 * it is a clock on a different timeline. Working back, the sender's timestamps sit near
 * 2208988800 s, i.e. approximately zero on the Unix epoch: a session- or boot-relative monotonic
 * clock, not wall time. That is normal for AirPlay, where media timestamps live on the sender's own
 * timeline, and it means **the absolute offset is meaningless and only its stability matters.**
 *
 * Its stability is good: successive best samples drifted about 1.2 ms over 30 s, roughly 40 ppm,
 * which is ordinary crystal drift between two devices and precisely what multi-room has to correct
 * for. Round trip was 5.5 ms at best with excursions past 130 ms — the best-of-window filter earns
 * its place, holding 5.5 ms while instantaneous samples spiked twenty-fold.
 *
 * Anything built on this must therefore track *rate* as well as offset. Treating the offset as a
 * fixed correction will drift about a millisecond every half minute, which is audible as two
 * speakers slowly separating.
 *
 * Reference: RFC 5905 §8 for the arithmetic; RPiPlay `lib/raop_ntp.c` for the AirPlay framing.
 */
class AirPlayNtpClient(
    private val remoteAddress: InetAddress,
    private val remoteTimingPort: Int,
) {
    private val socket = DatagramSocket()      // OS-assigned local port
    @Volatile private var running = false

    /** Local UDP port to advertise to the sender as the receiver's timingPort. */
    val localPort: Int get() = socket.localPort

    // ─── Synchronisation state ───────────────────────────────────────────────

    /** Sender clock minus ours, nanoseconds, from the best sample seen. 0 until synchronised. */
    @Volatile var offsetNanos: Long = 0L
        private set

    /** Round-trip delay of the sample [offsetNanos] came from. A quality figure for the estimate. */
    @Volatile var bestDelayNanos: Long = Long.MAX_VALUE
        private set

    /** True once at least one reply has been parsed successfully. */
    @Volatile var synchronised = false
        private set

    /**
     * Offset AND rate. The offset alone is enough for one room; holding two rooms together needs
     * the rate, because their crystals differ (~40ppm measured here) and that difference
     * accumulates into audible lip-sync error over minutes. See [SenderClockModel].
     */
    val clockModel = SenderClockModel()

    private val window = ArrayDeque<Sample>()

    private data class Sample(val offsetNanos: Long, val delayNanos: Long)

    /**
     * The sender's clock, in nanoseconds on the NTP timeline, as best we can tell right now.
     * Falls back to our own clock when unsynchronised, which is wrong but bounded — and
     * [synchronised] tells the caller which it is getting.
     */
    fun senderNowNanos(): Long = localNowNanos() + offsetNanos

    fun start(scope: CoroutineScope) {
        running = true
        socket.soTimeout = RECV_TIMEOUT_MS
        scope.launch(Dispatchers.IO) { loop() }
        Logger.i("NTP client → [$remoteAddress]:$remoteTimingPort, local timing port $localPort")
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun loop() {
        // request: [0]=0x80 (RTP), [1]=0xd2 (timing request), [2-3]=seq, [24-31]=send NTP time.
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte()
        request[3] = 0x07
        val response = ByteArray(128)
        var first = true
        var replies = 0L
        var timeouts = 0L
        while (running) {
            try {
                val t1 = localNowNanos()
                putNtpTimestamp(request, 24, t1)
                socket.send(DatagramPacket(request, request.size, remoteAddress, remoteTimingPort))
                if (first) { Logger.i("NTP: first timing request sent"); first = false }
                try {
                    val rx = DatagramPacket(response, response.size)
                    socket.receive(rx)
                    val t4 = localNowNanos()
                    if (handleReply(response, rx.length, t1, t4)) replies++
                } catch (_: java.net.SocketTimeoutException) {
                    timeouts++
                    // Silence here is the interesting case: it means the sender is not answering
                    // and no shared timeline exists, which the old code could not distinguish from
                    // working. Report it once it is clearly a pattern rather than one lost packet.
                    if (timeouts == TIMEOUTS_BEFORE_WARNING && replies == 0L) {
                        Logger.w("NTP: $timeouts requests sent, no replies — no shared clock with the sender")
                    }
                }
            } catch (e: Exception) {
                if (running) Logger.e("NTP client send error", e)
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** @return true if this was a well-formed timing reply we could take a sample from. */
    private fun handleReply(buf: ByteArray, length: Int, t1: Long, t4: Long): Boolean {
        if (length < TIMING_REPLY_BYTES) {
            Logger.w("NTP: ${length}B reply is too short to be a timing response")
            return false
        }
        val type = buf[1].toInt() and 0x7F
        if (type != TIMING_REPLY_TYPE) {
            Logger.w("NTP: unexpected packet type 0x${type.toString(16)} on the timing port")
            return false
        }
        val t2 = readNtpTimestamp(buf, 16)
        val t3 = readNtpTimestamp(buf, 24)
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        val delay = (t4 - t1) - (t3 - t2)
        // A negative delay is physically impossible and means the sender's timestamps are not on
        // the timeline we assume. Dropping the sample is right; silently averaging it in is not.
        if (delay < 0) {
            Logger.w("NTP: discarding sample with negative delay (${delay / 1000}us) — timestamps disagree")
            return false
        }

        window.addLast(Sample(offset, delay))
        while (window.size > WINDOW_SIZE) window.removeFirst()
        val best = window.minBy { it.delayNanos }
        offsetNanos = best.offsetNanos
        bestDelayNanos = best.delayNanos

        // Feed the rate model only low-delay samples. A sample delayed by network jitter carries
        // an offset error of up to half that delay, which would tilt the regression far more than
        // it would move a best-of-window offset.
        if (delay <= bestDelayNanos * RATE_SAMPLE_DELAY_FACTOR) {
            clockModel.addSample(t4, t3)
        }

        val wasSynchronised = synchronised
        synchronised = true
        // One line on first sync, then periodically — enough to see drift and jitter without
        // filling the log. Microseconds, because milliseconds is too coarse to judge lip sync.
        if (!wasSynchronised || window.size % LOG_EVERY_N_SAMPLES == 0) {
            Logger.i(
                "NTP sync: offset=${offset / 1000}us rtt=${delay / 1000}us " +
                    "| best offset=${offsetNanos / 1000}us rtt=${bestDelayNanos / 1000}us " +
                    "(${window.size} samples)" +
                    // Skew is the number that decides whether multi-room can hold sync: it should
                    // settle to a small, stable value for a given pair of devices.
                    if (clockModel.confident) " | skew=%.1fppm".format(clockModel.skewPpm) else ""
            )
        }
        return true
    }

    // ─── Clocks ──────────────────────────────────────────────────────────────

    /**
     * A monotonic local clock expressed on the NTP timeline, in nanoseconds. Anchored once so that
     * a wall-clock correction mid-session cannot appear as a sudden offset jump.
     */
    private fun localNowNanos(): Long = anchorNtpNanos + (System.nanoTime() - anchorNanoTime)

    private val anchorNanoTime = System.nanoTime()
    private val anchorNtpNanos =
        (System.currentTimeMillis() / 1000 + NTP_EPOCH_OFFSET) * NANOS_PER_SECOND +
            (System.currentTimeMillis() % 1000) * 1_000_000L

    /** Writes a 64-bit NTP timestamp (seconds since 1900 + 32-bit fraction) big-endian. */
    private fun putNtpTimestamp(buf: ByteArray, off: Int, nanos: Long) {
        val seconds = nanos / NANOS_PER_SECOND
        val fraction = (nanos % NANOS_PER_SECOND) * (1L shl 32) / NANOS_PER_SECOND
        writeUint32(buf, off, seconds)
        writeUint32(buf, off + 4, fraction)
    }

    /** Reads a 64-bit NTP timestamp as nanoseconds on the same timeline as [localNowNanos]. */
    private fun readNtpTimestamp(buf: ByteArray, off: Int): Long {
        val seconds = readUint32(buf, off)
        val fraction = readUint32(buf, off + 4)
        return seconds * NANOS_PER_SECOND + fraction * NANOS_PER_SECOND / (1L shl 32)
    }

    private fun writeUint32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    private fun readUint32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    companion object {
        private const val NTP_EPOCH_OFFSET = 2208988800L   // seconds between 1900 and 1970
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val POLL_INTERVAL_MS = 2000L
        private const val RECV_TIMEOUT_MS = 1000

        /** Reply packet type (0xd3), with the marker bit masked off. */
        private const val TIMING_REPLY_TYPE = 0x53
        private const val TIMING_REPLY_BYTES = 32

        /** Samples kept for best-of selection — at a 2 s poll, about a minute of history. */
        private const val WINDOW_SIZE = 32

        /** Only samples within this multiple of the best RTT are clean enough to fit a rate to. */
        private const val RATE_SAMPLE_DELAY_FACTOR = 2

        private const val LOG_EVERY_N_SAMPLES = 8

        /** ~20 s of silence before saying so, so one dropped packet is not an alarm. */
        private const val TIMEOUTS_BEFORE_WARNING = 10L
    }
}
