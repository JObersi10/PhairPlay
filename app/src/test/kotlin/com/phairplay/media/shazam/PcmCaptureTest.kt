package com.phairplay.media.shazam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The capture is where a stream that plays perfectly can still fingerprint to nothing, because
 * every fault here is inaudible: the audio the listener hears is untouched, and only the copy fed to
 * the detector is wrong. So these check the two things that would silently ruin a match — the rate
 * conversion, and continuity across packet boundaries.
 */
class PcmCaptureTest {

    /** Interleaved signed 16-bit little-endian, as the decoders produce. */
    private fun packet(frames: Int, channels: Int, sample: (Int, Int) -> Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var o = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val v = sample(f, c)
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    @Test
    fun `44100 stereo is decimated to 16000 mono`() {
        val capture = PcmCapture(44100, 2, windowSeconds = 1)
        // One second of source audio should more than fill a one-second target buffer.
        capture.offer(packet(44100, 2) { _, _ -> 1000 })
        assertTrue(capture.isFull)
        assertEquals(16000, capture.take().size)
    }

    @Test
    fun `channels are averaged rather than one being dropped`() {
        val capture = PcmCapture(16000, 2, windowSeconds = 1)
        // Left +3000, right -1000 → mono +1000. Taking either channel alone gives a different answer.
        capture.offer(packet(16000, 2) { _, c -> if (c == 0) 3000 else -1000 })
        val out = capture.take()
        // Skip the first sample: it interpolates from the zero carry at the start of the stream.
        assertTrue(out.drop(1).all { abs(it - 1000) <= 1 })
    }

    @Test
    fun `mono input passes through at the same rate`() {
        val capture = PcmCapture(16000, 1, windowSeconds = 1)
        capture.offer(packet(16000, 1) { f, _ -> if (f % 2 == 0) 2000 else -2000 })
        assertEquals(16000, capture.take().size)
    }

    /**
     * The one that actually bites.
     *
     * Resampling each packet independently restarts the interpolation phase at every boundary, which
     * puts a discontinuity every few milliseconds. Those are broadband clicks, and a peak detector
     * notices clicks — so a stream captured this way fingerprints to the seams instead of the music.
     * Feeding a pure tone in many small packets must give back the same tone.
     */
    @Test
    fun `a tone split across many packets stays continuous`() {
        val capture = PcmCapture(44100, 2, windowSeconds = 1)
        val framesPerPacket = 352      // a realistic AAC/ALAC packet, and not a divisor of the ratio
        var frame = 0
        while (!capture.isFull) {
            capture.offer(packet(framesPerPacket, 2) { f, _ ->
                (sin(2.0 * PI * 440.0 * (frame + f) / 44100.0) * 8000).toInt()
            })
            frame += framesPerPacket
        }
        val out = capture.take()

        // A 440Hz tone at 16kHz crosses zero every ~18.2 samples. Seam discontinuities show up as a
        // sample that jumps further than the tone ever can between neighbours.
        val maxLegitimateStep = (2.0 * PI * 440.0 / 16000.0 * 8000).toInt() + 40
        var worst = 0
        for (i in 1 until out.size) {
            worst = maxOf(worst, abs(out[i] - out[i - 1]))
        }
        assertTrue(
            "largest sample-to-sample jump was $worst, above the $maxLegitimateStep a 440Hz tone " +
                "can produce — the packet seams are discontinuous",
            worst <= maxLegitimateStep,
        )
    }

    @Test
    fun `capture stops accepting once the window is full`() {
        val capture = PcmCapture(16000, 1, windowSeconds = 1)
        capture.offer(packet(16000, 1) { _, _ -> 500 })
        assertTrue(capture.isFull)
        capture.offer(packet(16000, 1) { _, _ -> -500 })
        assertEquals(16000, capture.take().size)
    }

    @Test
    fun `take resets the capture for the next track`() {
        val capture = PcmCapture(16000, 1, windowSeconds = 1)
        capture.offer(packet(16000, 1) { _, _ -> 500 })
        capture.take()
        assertFalse(capture.isFull)
        assertEquals(0f, capture.progress, 1e-6f)
    }

    @Test
    fun `an empty packet is harmless`() {
        val capture = PcmCapture(44100, 2, windowSeconds = 1)
        capture.offer(ByteArray(0))
        assertFalse(capture.isFull)
    }
}
