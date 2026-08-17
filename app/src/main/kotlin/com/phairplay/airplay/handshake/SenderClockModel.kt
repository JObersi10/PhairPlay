package com.phairplay.airplay.handshake

/**
 * SenderClockModel — tracks the sender's clock as an offset AND a rate, not just an offset.
 *
 * WHY rate matters, measured rather than assumed: over a 30-second window this receiver's estimate
 * of the sender's clock moved about 1.2ms, i.e. the two crystals differ by roughly 40 parts per
 * million. That is unremarkable for consumer hardware and completely invisible in single-room
 * playback, because a constant offset absorbs it.
 *
 * It stops being invisible the moment a second device plays the same stream. Two receivers each
 * holding an offset-only estimate drift apart at the DIFFERENCE of their skews, so ~40ppm of
 * relative error accumulates about 2.4ms of lip-sync error per minute and roughly 20ms over ten
 * minutes — audibly out of sync for anyone in earshot of both. Correcting it by periodically
 * re-latching a fresh offset just converts the drift into a step, which is more noticeable than
 * the drift.
 *
 * So the model fits a line, `senderTime ≈ intercept + slope × localTime`, over recent samples by
 * ordinary least squares. [skewPpm] then reports the crystal difference directly, which is the
 * number worth logging: it should be small, stable, and roughly constant for a given pair of
 * devices. A wildly varying skew means the samples are dominated by network jitter rather than by
 * clock difference, and [confident] says so instead of letting a bad fit look authoritative.
 *
 * Not thread-safe; the timing thread owns it.
 */
class SenderClockModel(private val capacity: Int = DEFAULT_CAPACITY) {

    private data class Sample(val localNanos: Long, val senderNanos: Long)

    private val samples = ArrayDeque<Sample>()

    /** Origin for the regression, so the values fed to it stay small enough for Double precision. */
    private var originLocal = 0L
    private var originSender = 0L

    /** senderNanos - localNanos at the most recent fit, in nanoseconds. */
    var offsetNanos: Long = 0L
        private set

    /** How fast the sender's clock runs relative to ours. 1.0 means identical. */
    var rate: Double = 1.0
        private set

    /**
     * A fit is only meaningful once there are enough samples spread over enough time. Two samples
     * a millisecond apart produce a perfectly-fitting line with an absurd slope; refusing to report
     * that is the difference between "unknown" and "confidently wrong".
     */
    val confident: Boolean
        get() = samples.size >= MIN_SAMPLES && spanNanos() >= MIN_SPAN_NANOS

    /** Crystal difference in parts per million — the human-readable form of [rate]. */
    val skewPpm: Double get() = (rate - 1.0) * 1_000_000.0

    /**
     * Adds a paired observation: our local time and the sender time it corresponded to.
     *
     * Callers should feed only samples they already trust — for NTP that means the best-of-window
     * low-delay ones, since a sample delayed by network jitter carries an offset error of up to
     * half that delay and would tilt the fit.
     */
    fun addSample(localNanos: Long, senderNanos: Long) {
        if (samples.isEmpty()) {
            originLocal = localNanos
            originSender = senderNanos
        }
        samples.addLast(Sample(localNanos, senderNanos))
        while (samples.size > capacity) samples.removeFirst()
        refit()
    }

    fun reset() {
        samples.clear()
        offsetNanos = 0L
        rate = 1.0
    }

    /** Converts one of our timestamps into the sender's timebase. */
    fun senderNanosAt(localNanos: Long): Long =
        originSender + ((localNanos - originLocal) * rate).toLong() + offsetAdjustment

    /**
     * Converts a sender timestamp into ours — the direction that matters for playback scheduling,
     * since every anchor and RTP timestamp arrives in the sender's timebase and has to become a
     * local deadline.
     */
    fun localNanosAt(senderNanos: Long): Long =
        originLocal + ((senderNanos - originSender - offsetAdjustment) / rate).toLong()

    private var offsetAdjustment: Long = 0L

    private fun spanNanos(): Long =
        if (samples.size < 2) 0 else samples.last().localNanos - samples.first().localNanos

    /**
     * Ordinary least squares on (local, sender) shifted to the first sample.
     *
     * Falls back to offset-only while there is too little data to justify a slope, so an early
     * estimate is merely imprecise rather than actively diverging.
     */
    private fun refit() {
        val n = samples.size
        if (n == 0) return

        val last = samples.last()
        if (!confident) {
            rate = 1.0
            offsetAdjustment = 0L
            offsetNanos = last.senderNanos - last.localNanos
            return
        }

        var sumX = 0.0; var sumY = 0.0
        for (s in samples) {
            sumX += (s.localNanos - originLocal).toDouble()
            sumY += (s.senderNanos - originSender).toDouble()
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var sxx = 0.0; var sxy = 0.0
        for (s in samples) {
            val dx = (s.localNanos - originLocal).toDouble() - meanX
            val dy = (s.senderNanos - originSender).toDouble() - meanY
            sxx += dx * dx
            sxy += dx * dy
        }

        // Guard the degenerate case: identical local timestamps give sxx = 0 and an infinite slope.
        val slope = if (sxx <= 0.0) 1.0 else sxy / sxx
        // A slope far from 1.0 is not a real crystal difference — consumer parts are within a few
        // hundred ppm — it is a bad sample set. Clamping keeps one outlier from wrecking playback.
        rate = slope.coerceIn(1.0 - MAX_SKEW, 1.0 + MAX_SKEW)

        offsetAdjustment = (meanY - rate * meanX).toLong()
        offsetNanos = senderNanosAt(last.localNanos) - last.localNanos
    }

    companion object {
        private const val DEFAULT_CAPACITY = 64
        private const val MIN_SAMPLES = 8

        /**
         * How long the samples must span before a slope means anything.
         *
         * Was 5s, and the arithmetic says that was never enough. Each NTP sample carries an offset
         * error up to half its round trip, and the measured round trips here swing between 4ms and
         * 31ms — so roughly 15ms of uncertainty at the ends of the window. Fitting a line through
         * points that uncertain over a 5s span admits slope errors on the order of 3000ppm, which is
         * two orders of magnitude larger than any crystal difference being measured. The device log
         * showed the consequence directly: −16.5ppm in one session and +115.6ppm in the next, both
         * reported with the same confidence, when consumer crystals sit within about ±50ppm.
         *
         * At 60s the same 15ms of endpoint noise contributes about 250ppm — still not tight, but the
         * fit is now dominated by real drift rather than by jitter, and the estimate stops swinging.
         */
        private const val MIN_SPAN_NANOS = 60_000_000_000L

        /**
         * 100ppm. Consumer crystals are specified within roughly ±50ppm, so a fit beyond this is
         * reporting network jitter, not a clock.
         *
         * The old bound was 500ppm, which is wide enough to pass a jitter-dominated fit through
         * untouched — the clamp only caught fits that were already absurd, so the plainly wrong
         * 115.6ppm went straight to playback as if measured.
         */
        private const val MAX_SKEW = 0.0001
    }
}
