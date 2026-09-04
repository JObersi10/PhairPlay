package com.phairplay.media.shazam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The fingerprint is a port of someone else's algorithm, so these tests are about FIDELITY rather
 * than about design: the question is not "is this reasonable" but "does this match `shazamio-core`".
 *
 * Two things make that checkable without a Rust toolchain. The header layout is pinned against the
 * bytes of the project's own golden signature (`tests/data/probe.flac.uri`, decoded), and the peak
 * detector is checked against physics — fed tones at known frequencies, it has to find peaks at
 * those frequencies.
 *
 * What is NOT checked here is byte-equality with that golden end to end. It cannot be: the golden
 * was produced through `rodio`'s resampler from 44.1 kHz, and a different resampler moves the
 * samples slightly, which moves the peaks, which changes every byte after the header. Claiming a
 * match there would mean weakening the test until it passed.
 */
class ShazamSignatureTest {

    // ── The window ─────────────────────────────────────────────────────────────────────────────

    /**
     * `hanning.rs` is a 2048-entry literal table; this reproduces it from its generating function,
     * so the endpoints it pins are the thing worth checking. Both ends of the Rust table read
     * 0.0000023508, and the centre reaches 1.0.
     */
    @Test
    fun `the generated window matches the pinned table endpoints`() {
        assertEquals(0.0000023508f, ShazamSignature.hanningAt(0), 1e-9f)
        assertEquals(0.0000023508f, ShazamSignature.hanningAt(2047), 1e-9f)
        // The table's second and third entries, as further evidence the period is 2049 and not 2048.
        assertEquals(0.0000094032f, ShazamSignature.hanningAt(1), 1e-9f)
        assertEquals(0.000021157f, ShazamSignature.hanningAt(2), 1e-8f)
        assertEquals(1.0f, ShazamSignature.hanningAt(1023), 1e-5f)
    }

    @Test
    fun `the window is symmetric`() {
        for (i in 0 until 1024) {
            assertEquals(ShazamSignature.hanningAt(i), ShazamSignature.hanningAt(2047 - i), 1e-7f)
        }
    }

    // ── The transform ──────────────────────────────────────────────────────────────────────────

    /**
     * The FFT is hand-written, so it is checked against the definition rather than against itself.
     * A naive DFT is far too slow for the real thing and exactly right for four bins of a test.
     */
    @Test
    fun `the fft agrees with a direct dft`() {
        val input = FloatArray(2048) { i ->
            (sin(2.0 * PI * 5 * i / 2048.0) * 1000.0 + cos(2.0 * PI * 37 * i / 2048.0) * 250.0).toFloat()
        }
        val fft = ShazamSignature.Fft2048()
        fft.forward(input)

        for (k in intArrayOf(0, 5, 37, 512)) {
            var re = 0.0
            var im = 0.0
            for (n in 0 until 2048) {
                val a = -2.0 * PI * k * n / 2048.0
                re += input[n] * cos(a)
                im += input[n] * sin(a)
            }
            // Tolerance is relative to the signal's scale: these bins run to ~10^6.
            assertEquals("bin $k real", re.toFloat(), fft.real[k], 1f)
            assertEquals("bin $k imag", im.toFloat(), fft.imag[k], 1f)
        }
    }

    /** A pure tone must land in its own bin and nowhere else. */
    @Test
    fun `a single tone produces one fft peak`() {
        val bin = 64
        val input = FloatArray(2048) { i -> (sin(2.0 * PI * bin * i / 2048.0) * 10000.0).toFloat() }
        val fft = ShazamSignature.Fft2048()
        fft.forward(input)
        val power = FloatArray(1025) { fft.real[it] * fft.real[it] + fft.imag[it] * fft.imag[it] }
        assertEquals(bin, power.indices.maxByOrNull { power[it] })
    }

    // ── The container ──────────────────────────────────────────────────────────────────────────

    /**
     * Every constant here was read out of the decoded golden signature, not out of the Rust source,
     * so this checks the port against the format as it actually appears on the wire.
     *
     * Golden header (`probe.flac.uri`, base64-decoded), by offset:
     *   0: 0xcafe2580   4: crc32 over [8..]   8: len-48   12: 0x94119c00
     *  28: 0x18000000 (sample-rate id 3, shifted left 27)   40: samples + 3840
     *  44: 0x007c0000   48: 0x40000000   52: len-48
     */
    @Test
    fun `the header matches the golden signature layout`() {
        val samples = ShortArray(16000) { (sin(2.0 * PI * 1000 * it / 16000.0) * 8000).toInt().toShort() }
        val bytes = ShazamSignature.generate(samples).encode()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0xcafe2580.toInt(), b.getInt(0))
        assertEquals(0x94119c00.toInt(), b.getInt(12))
        assertEquals(0x18000000, b.getInt(28))
        assertEquals(0x007c0000, b.getInt(44))
        assertEquals(0x40000000, b.getInt(48))

        assertEquals(bytes.size - 48, b.getInt(8))
        assertEquals(bytes.size - 48, b.getInt(52))

        // 0.24 seconds of samples added to the count, per the format.
        assertEquals(16000 + 3840, b.getInt(40))

        // The CRC covers everything past its own field, and is computed after the lengths are set.
        val expected = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        assertEquals(expected, b.getInt(4))
    }

    @Test
    fun `the uri carries the shazam signature media type`() {
        val samples = ShortArray(16000) { (sin(2.0 * PI * 1000 * it / 16000.0) * 8000).toInt().toShort() }
        val uri = ShazamSignature.generate(samples).toUri()
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        // Base64 of a signature whose length is a multiple of 4 never needs padding.
        assertTrue(uri.substringAfter(',').all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `duration is reported in milliseconds not samples`() {
        val signature = ShazamSignature.generate(ShortArray(24_500))
        // The Rust test pins this exact case: 24500 samples at 16 kHz is 1531ms, truncated.
        assertEquals(1531, signature.durationMs)
    }

    // ── The detector ───────────────────────────────────────────────────────────────────────────

    /**
     * The whole chain, checked against physics.
     *
     * This is the content of the project's own `probe` fixture — two chirps plus fixed tones,
     * chosen there because a plain sine yields a near-empty signature. Fed tones at 1237 Hz and
     * 3001 Hz, the detector has to report peaks at those frequencies; if the window, the FFT, the
     * spreading or the bin-to-hertz conversion were wrong, the peaks would land somewhere else or
     * not at all.
     */
    @Test
    fun `peaks land on the frequencies that were played`() {
        val n = 16000 * 8
        val samples = ShortArray(n) { i ->
            val t = i / 16000.0
            val v = 0.30 * sin(2.0 * PI * (300 + 180 * t) * t) +
                0.22 * sin(2.0 * PI * 1237 * t) +
                0.16 * sin(2.0 * PI * 3001 * t)
            (v * 12000).toInt().toShort()
        }
        val signature = ShazamSignature.generate(samples)
        assertTrue("no peaks at all — the detector never fired", signature.peakCount > 50)

        val hz = signature.peakFrequencies()
        assertTrue("expected a peak near 1237 Hz, got ${hz.take(20)}", hz.any { abs(it - 1237f) < 25f })
        assertTrue("expected a peak near 3001 Hz, got ${hz.take(20)}", hz.any { abs(it - 3001f) < 25f })
    }

    /** Silence has nothing to fingerprint, and must not produce peaks or throw. */
    @Test
    fun `silence produces an empty signature`() {
        val signature = ShazamSignature.generate(ShortArray(16000 * 4))
        assertEquals(0, signature.peakCount)
        // Still a well-formed packet: the 56-byte fixed preamble, no band blocks. NOT 48 -- the
        // 0x40000000 marker and the repeated size sit after the header and before the first band.
        assertEquals(56, signature.encode().size)
    }

    /** Too short to reach the 46-frame warm-up: no peaks, no crash, no out-of-bounds read. */
    @Test
    fun `a buffer shorter than the detector warm-up is harmless`() {
        val signature = ShazamSignature.generate(ShortArray(1000))
        assertEquals(0, signature.peakCount)
    }
}
