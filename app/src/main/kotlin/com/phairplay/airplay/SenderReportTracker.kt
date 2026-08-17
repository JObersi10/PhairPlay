package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * SenderReportTracker — consumes the RTCP Sender Reports that [RtcpReport] parses.
 *
 * The reports were being parsed and dropped on the floor, which is the worst of both worlds: the
 * cost of decoding them with none of the information. Two things in them are worth keeping, and
 * neither can be derived anywhere else in the pipeline:
 *
 *  - **Sender clock skew.** A Sender Report states "RTP timestamp X was sampled at wall-clock time
 *    T" using the SENDER's clock. Comparing T against ours gives the real end-to-end offset for
 *    this exact stream, rather than the NTP probe's estimate of the offset between two hosts.
 *
 *  - **Loss.** `packetCount` is a running total of everything the sender has put on the wire. The
 *    delta between consecutive reports is what it sent; anything we did not see is genuinely lost
 *    rather than merely late.
 *
 * Deliberately diagnostic only. Feeding the skew back into playback timing would be a real change
 * to A/V alignment, and this stream (legacy SDP with video) is not where that would be validated —
 * so it reports rather than corrects, and says which it is.
 */
class SenderReportTracker {

    private var lastPacketCount = -1L
    private var lastLoggedAtMs = 0L

    /** Most recent sender-vs-receiver clock skew in milliseconds; null until the first report. */
    @Volatile
    var skewMs: Long? = null
        private set

    fun accept(report: RtcpReport.SenderReport) {
        val nowUs = System.currentTimeMillis() * 1_000L
        val skew = (nowUs - report.senderTimeUs) / 1_000L
        skewMs = skew

        val sent = if (lastPacketCount < 0) 0L else report.packetCount - lastPacketCount
        lastPacketCount = report.packetCount

        // One line per interval rather than per report: senders emit these every few seconds and a
        // per-report log buries everything else during mirroring.
        val now = System.currentTimeMillis()
        if (now - lastLoggedAtMs < LOG_INTERVAL_MS) return
        lastLoggedAtMs = now
        Logger.i(
            "RTCP SR: ssrc=${report.ssrc} rtp=${report.rtpTimestamp} skew=${skew}ms " +
                "sent=${report.packetCount} (+$sent) bytes=${report.octetCount}",
        )
    }

    private companion object {
        const val LOG_INTERVAL_MS = 5_000L
    }
}
