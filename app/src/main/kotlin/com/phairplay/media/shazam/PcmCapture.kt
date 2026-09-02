package com.phairplay.media.shazam

/**
 * PcmCapture — collects a short window of mono 16 kHz PCM for [ShazamSignature].
 *
 * The receiver decodes to whatever the sender negotiated, which is 44100 Hz stereo in practice. The
 * fingerprint is defined over mono 16 kHz and nothing else, so the stream has to be downmixed and
 * resampled on the way in.
 *
 * NO RE-ENCODING HAPPENS HERE, and it is worth being precise about that because "resample" sounds
 * expensive and this is not: the audio is already decoded PCM, so the work is one multiply-add per
 * output sample over a twelve-second window, once per track, on the packet thread. The signature's
 * own FFTs cost hundreds of times more, and those only run once this buffer is full.
 *
 * ARMED, NOT ALWAYS ON. Capture starts only when a sender gives us audio with no metadata, and
 * stops the moment the buffer is full. A stream that names its own track never allocates anything
 * here.
 *
 * Not thread-safe by design: [offer] is called from the one packet thread that decodes audio, and
 * putting a lock on that path to protect a buffer only one thread touches would be a real cost for
 * an imaginary problem. [take] hands the buffer over and resets, and is called once.
 */
class PcmCapture(
    private val sourceSampleRate: Int,
    private val sourceChannels: Int,
    /** How much audio to gather. Shazam matches comfortably on ten to twelve seconds. */
    windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
) {
    private val target = ShortArray(ShazamSignature.SAMPLE_RATE * windowSeconds)
    private var written = 0

    /**
     * Position in the SOURCE stream of the next output sample, as a fixed-point fraction.
     *
     * Carried across calls, which is the whole reason this is a field rather than a local: packets
     * do not divide evenly into output samples, so restarting the phase at every packet boundary
     * would resample each packet independently and leave a discontinuity at each seam. Those seams
     * are broadband clicks, and a click is exactly what a peak detector notices.
     */
    private var phase = 0.0

    /** The last frame of the previous packet, so interpolation can span a packet boundary. */
    private var carry = 0f

    val isFull: Boolean get() = written >= target.size

    /** How much has been gathered, 0..1. For the log — a stalled capture is otherwise invisible. */
    val progress: Float get() = written.toFloat() / target.size

    /**
     * Feeds one decoded packet.
     *
     * @param pcm interleaved signed 16-bit little-endian, [sourceChannels] channels.
     */
    fun offer(pcm: ByteArray) {
        if (isFull) return
        val frames = pcm.size / (2 * sourceChannels)
        if (frames <= 0) return
        val step = sourceSampleRate.toDouble() / ShazamSignature.SAMPLE_RATE

        while (phase < frames && written < target.size) {
            val i = phase.toInt()
            val frac = (phase - i).toFloat()
            // Linear interpolation between neighbouring frames. `carry` stands in for frame -1 so
            // the first output sample of a packet interpolates from the end of the previous one
            // rather than from itself.
            val a = if (i == 0) carry else monoAt(pcm, i - 1)
            val b = monoAt(pcm, i)
            target[written++] = (a + (b - a) * frac).toInt().toShort()
            phase += step
        }
        carry = monoAt(pcm, frames - 1)
        // Keep the fractional remainder, drop the whole packets consumed.
        phase -= frames
        if (phase < 0) phase = 0.0
    }

    /** Averages the channels of one frame. */
    private fun monoAt(pcm: ByteArray, frame: Int): Float {
        var sum = 0
        val base = frame * 2 * sourceChannels
        for (c in 0 until sourceChannels) {
            val o = base + c * 2
            if (o + 1 >= pcm.size) break
            sum += ((pcm[o + 1].toInt() shl 8) or (pcm[o].toInt() and 0xFF)).toShort().toInt()
        }
        return sum.toFloat() / sourceChannels
    }

    /** The captured window. Only meaningful once [isFull]; resets the capture. */
    fun take(): ShortArray {
        val out = target.copyOf(written)
        written = 0
        phase = 0.0
        carry = 0f
        return out
    }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 12
    }
}
