package com.phairplay.media.shazam

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * ShazamSignature — the audio fingerprint, ported from `shazamio-core`.
 *
 * WHY THIS EXISTS: raw system audio (a browser tab, a game, anything that is not Music or Podcasts)
 * arrives over AirPlay with no title and no artist at all, so every downstream lookup — cover art,
 * and anything else keyed on a track name — has nothing to work with. Fingerprinting is the only
 * thing that turns that audio into a name.
 *
 * PROVENANCE. This is a line-by-line port of `shazamio-core`'s `fingerprinting` module
 * (github.com/shazamio/shazamio-core, MIT), specifically `algorithm.rs` and `signature_format.rs`.
 * It was ported FROM THE SOURCES, not reconstructed from a description of them: every constant
 * below — the peak neighbour offsets, the 46/49-frame lookbacks, the magnitude curve, the header
 * magics — is meaningless on its own and wrong if approximated. If this ever needs revisiting,
 * re-read the Rust rather than reasoning about what the numbers ought to be.
 *
 * WHAT WAS DELIBERATELY LEFT OUT: the Rust carries a large decoding front end (rodio, symphonia and
 * an ffmpeg fallback) for turning mp3/ogg/m4a FILES into PCM. None of it is needed here —
 * `AudioStreamServer` has already decoded the stream, so the only preparation left is resampling to
 * the mono 16 kHz the fingerprint is defined over. That is the bulk of `algorithm.rs` and none of
 * its substance.
 *
 * The generator is single-use: build one, feed it a buffer, take the signature.
 */
object ShazamSignature {

    /** The fingerprint is defined over mono 16 kHz PCM. Nothing here is valid at another rate. */
    const val SAMPLE_RATE = 16000

    /** The four bands the peaks are filed under, in the order the encoder writes them. */
    internal enum class Band(val id: Int) { HZ_250_520(0), HZ_520_1450(1), HZ_1450_3500(2), HZ_3500_5500(3) }

    internal class Peak(val fftPassNumber: Int, val magnitude: Int, val correctedBin: Int)

    /**
     * Hanning window over 2048 entries.
     *
     * The Rust ships this as a literal table with its "leading and trailing zeroes omitted"; the
     * generating function is `0.5 * (1 - cos(2*pi*(i+1)/2049))`, which is `numpy.hanning(2049)[1:]`.
     * Checked against the table's own pinned endpoints — its first and last entries are both
     * 0.0000023508, which this reproduces — so the 2048-line table is computed here instead of
     * copied, and [hanningMatchesPinnedTableValues] holds it to that.
     */
    private val HANNING = FloatArray(2048) { i ->
        (0.5 * (1.0 - cos(2.0 * Math.PI * (i + 1) / 2049.0))).toFloat()
    }

    /**
     * Builds a signature over mono 16 kHz PCM.
     *
     * @param samples 16-bit mono PCM at 16 kHz. Shorter than ~3 seconds is not worth sending: the
     *   recogniser needs enough passes to find peaks, and the first 46 FFT frames produce none by
     *   construction.
     */
    fun generate(samples: ShortArray): Signature {
        val g = Generator(samples.size)
        // 128 samples per pass -- the hop, not the window. The window is 2048 and overlaps.
        var offset = 0
        while (offset + 128 <= samples.size) {
            g.doFft(samples, offset)
            g.doPeakSpreading()
            g.spreadFftsDone++
            if (g.spreadFftsDone >= 46) g.doPeakRecognition()
            offset += 128
        }
        return Signature(SAMPLE_RATE, samples.size, g.peaks)
    }

    /** A finished fingerprint, ready to be encoded. */
    class Signature internal constructor(
        private val sampleRateHz: Int,
        private val numberSamples: Int,
        private val peaks: Map<Int, MutableList<Peak>>,
    ) {
        /** How much audio this covers, in milliseconds — what the request reports as `samplems`. */
        val durationMs: Int get() = (numberSamples.toLong() * 1000 / sampleRateHz).toInt()

        val peakCount: Int get() = peaks.values.sumOf { it.size }

        /**
         * The frequencies the peaks sit at, in Hz.
         *
         * Not needed to build a request -- the encoder writes the raw bins. It exists because a
         * fingerprint is otherwise completely opaque: when a lookup returns no match there is no way
         * to tell "the detector found nothing" from "the detector found the wrong thing" without
         * being able to read the peaks back as frequencies.
         */
        fun peakFrequencies(): List<Float> =
            peaks.values.flatten().map { it.correctedBin * (SAMPLE_RATE / 2f / 1024f / 64f) }

        /** `data:audio/vnd.shazam.sig;base64,...` — what the request carries. */
        fun toUri(): String = DATA_URI_PREFIX + base64(encode())

        /**
         * The binary signature.
         *
         * Little-endian throughout, with a 48-byte header whose CRC and length fields can only be
         * filled in once the body is known — hence the buffer rewrite at the end rather than a
         * single forward pass.
         */
        fun encode(): ByteArray {
            val body = ByteArrayOutputStream()
            // Bands are written in ascending id order. The Rust sorts explicitly because it is
            // iterating a HashMap; a sorted map here makes that structural instead.
            for ((bandId, list) in peaks.toSortedMap()) {
                val peaksBuf = ByteArrayOutputStream()
                var pass = 0
                for (p in list) {
                    // The gap to the previous peak is written as one byte. When it does not fit,
                    // 0xff introduces an absolute 32-bit pass number and the gap restarts from it.
                    if (p.fftPassNumber - pass >= 255) {
                        peaksBuf.write(0xff)
                        peaksBuf.writeLe32(p.fftPassNumber)
                        pass = p.fftPassNumber
                    }
                    peaksBuf.write(p.fftPassNumber - pass)
                    peaksBuf.writeLe16(p.magnitude)
                    peaksBuf.writeLe16(p.correctedBin)
                    pass = p.fftPassNumber
                }
                val bytes = peaksBuf.toByteArray()
                body.writeLe32(0x60030040 + bandId)
                body.writeLe32(bytes.size)
                body.write(bytes)
                // Every band block is padded to a 4-byte boundary.
                repeat((4 - bytes.size % 4) % 4) { body.write(0) }
            }
            val bodyBytes = body.toByteArray()

            val out = ByteBuffer.allocate(PREAMBLE_BYTES + bodyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(MAGIC_1)
            out.putInt(0)                       // crc32, filled in below
            out.putInt(0)                       // size minus header, filled in below
            out.putInt(MAGIC_2)
            out.putInt(0); out.putInt(0); out.putInt(0)
            out.putInt(sampleRateId(sampleRateHz) shl 27)
            out.putInt(0); out.putInt(0)
            // Not a sample count: the count plus 0.24 seconds' worth of them. Copied as-is from the
            // Rust; the constant is Shazam's, not ours.
            out.putInt(numberSamples + (sampleRateHz * 0.24f).toInt())
            out.putInt((15 shl 19) + 0x40000)
            out.putInt(0x40000000)
            out.putInt(0)                       // size minus header again, filled in below
            out.put(bodyBytes)

            val buf = out.array()
            val sizeMinusHeader = buf.size - HEADER_BYTES
            buf.setLe32(8, sizeMinusHeader)
            buf.setLe32(HEADER_BYTES + 4, sizeMinusHeader)

            // CRC is over everything after the CRC field itself, and must be computed only once the
            // two length fields above are already in place.
            val crc = CRC32().apply { update(buf, 8, buf.size - 8) }.value
            buf.setLe32(4, crc.toInt())
            return buf
        }

        private fun sampleRateId(hz: Int): Int = when (hz) {
            8000 -> 1; 11025 -> 2; 16000 -> 3; 32000 -> 4; 44100 -> 5; 48000 -> 6
            else -> throw IllegalArgumentException("Shazam signatures have no id for $hz Hz")
        }
    }

    /**
     * The rolling state of one signature pass.
     *
     * Three ring buffers, all power-of-two sized so the wrap is a mask: the raw samples (2048), the
     * FFT magnitudes (256 frames) and the spread magnitudes (256 frames). The peak detector reaches
     * up to 53 frames backwards and 249 forwards through the spread ring, which is why it is 256
     * deep and why the indices are masked rather than clamped.
     */
    private class Generator(sampleCount: Int) {
        val ring = ShortArray(2048)
        var ringIndex = 0
        val reordered = FloatArray(2048)
        val fftOutputs = Array(256) { FloatArray(1025) }
        var fftIndex = 0
        val spread = Array(256) { FloatArray(1025) }
        var spreadIndex = 0
        var spreadFftsDone = 0
        val peaks = HashMap<Int, MutableList<Peak>>()
        val fft = Fft2048()

        fun doFft(samples: ShortArray, offset: Int) {
            System.arraycopy(samples, offset, ring, ringIndex, 128)
            ringIndex = (ringIndex + 128) and 2047

            // Reorder so the newest sample lands at the end, and window in the same pass.
            for (i in 0 until 2048) {
                reordered[i] = ring[(i + ringIndex) and 2047].toFloat() * HANNING[i]
            }

            val out = fftOutputs[fftIndex]
            fft.forward(reordered)
            for (i in 0..1024) {
                val re = fft.real[i]
                val im = fft.imag[i]
                // Scaled by 2^17 and floored, exactly as the Rust does. The floor is what keeps the
                // later ln() finite on a silent frame.
                out[i] = max((re * re + im * im) / 131072f, 0.0000000001f)
            }
            fftIndex = (fftIndex + 1) and 255
        }

        fun doPeakSpreading() {
            val src = fftOutputs[(fftIndex - 1) and 255]
            val dst = spread[spreadIndex]
            System.arraycopy(src, 0, dst, 0, 1025)

            // Spread each bin over itself and its two neighbours above. In place and ascending, so
            // a bin already sees the spread value of the one below it -- that is the Rust's
            // behaviour and it is load-bearing, not an accident of iteration order.
            for (i in 0..1022) {
                dst[i] = max(dst[i], max(dst[i + 1], dst[i + 2]))
            }

            // Then spread backwards in TIME, into the frames 1, 3 and 6 passes ago.
            val copy = dst.copyOf()
            for (back in intArrayOf(1, 3, 6)) {
                val older = spread[(spreadIndex - back) and 255]
                for (i in 0..1024) older[i] = max(older[i], copy[i])
            }
            spreadIndex = (spreadIndex + 1) and 255
        }

        fun doPeakRecognition() {
            val m46 = fftOutputs[(fftIndex - 46) and 255]
            val m49 = spread[(spreadIndex - 49) and 255]

            for (bin in 10..1014) {
                if (m46[bin] < 1f / 64f || m46[bin] < m49[bin - 1]) continue

                // A peak must dominate its frequency neighbours ...
                var maxNeighbour = 0f
                for (o in NEIGHBOUR_OFFSETS) maxNeighbour = max(maxNeighbour, m49[bin + o])
                if (m46[bin] <= maxNeighbour) continue

                // ... and its time neighbours, reaching both backwards and forwards through the ring.
                var maxOther = maxNeighbour
                for (o in TIME_OFFSETS) {
                    maxOther = max(maxOther, spread[(spreadIndex + o) and 255][bin - 1])
                }
                if (m46[bin] <= maxOther) continue

                val pass = spreadFftsDone - 46
                val mag = magnitude(m46[bin])
                val before = magnitude(m46[bin - 1])
                val after = magnitude(m46[bin + 1])

                // Quadratic interpolation across the three bins, giving a frequency 64x finer than
                // the bin grid. variation1 is the curvature and cannot be zero here: the checks
                // above already established this bin is strictly the largest of the three.
                val variation1 = mag * 2f - before - after
                val variation2 = (after - before) * 32f / variation1
                val correctedBin = (bin * 64) + variation2.toInt()

                val hz = correctedBin * (SAMPLE_RATE / 2f / 1024f / 64f)
                val band = when (hz.toInt()) {
                    in 250..519 -> Band.HZ_250_520
                    in 520..1449 -> Band.HZ_520_1450
                    in 1450..3499 -> Band.HZ_1450_3500
                    in 3500..5500 -> Band.HZ_3500_5500
                    else -> continue      // outside 250 Hz - 5.5 kHz, not part of the fingerprint
                }
                peaks.getOrPut(band.id) { ArrayList() }
                    .add(Peak(pass, mag.toInt(), correctedBin))
            }
        }

        /** The magnitude curve the format stores. Constants are Shazam's. */
        private fun magnitude(v: Float): Float = max(ln(v.toDouble()).toFloat(), 1f / 64f) * 1477.3f + 6144f
    }

    /**
     * A 2048-point FFT over real input.
     *
     * Written out rather than pulled in: Android ships no FFT in the framework, and every candidate
     * library is an AAR, which `:test-runner` cannot resolve — the protocol tests compile app code
     * on a plain JVM without AGP, so a dependency here would have to be stubbed, and a stubbed FFT
     * cannot be tested against the real one.
     *
     * Radix-2 decimation-in-time, unnormalised, matching `chfft`'s `RFft1D::forward` for the bins
     * 0..1024 that the caller reads.
     */
    internal class Fft2048 {
        val real = FloatArray(2048)
        val imag = FloatArray(2048)
        private val cosTable = FloatArray(1024)
        private val sinTable = FloatArray(1024)

        init {
            for (i in 0 until 1024) {
                cosTable[i] = cos(2.0 * Math.PI * i / 2048.0).toFloat()
                sinTable[i] = kotlin.math.sin(2.0 * Math.PI * i / 2048.0).toFloat()
            }
        }

        fun forward(input: FloatArray) {
            // Bit-reversal permutation, 11 bits for 2048 points.
            for (i in 0 until 2048) {
                val j = Integer.reverse(i) ushr 21
                real[j] = input[i]
                imag[j] = 0f
            }
            var size = 2
            while (size <= 2048) {
                val half = size / 2
                val step = 2048 / size
                var i = 0
                while (i < 2048) {
                    var k = 0
                    for (j in i until i + half) {
                        val l = j + half
                        val c = cosTable[k]
                        // Negative sine: this is the forward (e^-i) transform.
                        val s = -sinTable[k]
                        val tre = real[l] * c - imag[l] * s
                        val tim = real[l] * s + imag[l] * c
                        real[l] = real[j] - tre
                        imag[l] = imag[j] - tim
                        real[j] += tre
                        imag[j] += tim
                        k += step
                    }
                    i += size
                }
                size *= 2
            }
        }
    }

    /** Exposed for the test that pins the generated window against the Rust's literal table. */
    internal fun hanningAt(i: Int): Float = HANNING[i]

    private val NEIGHBOUR_OFFSETS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val TIME_OFFSETS = intArrayOf(
        -53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249,
    )

    /**
     * The length the two size fields are measured against — the header proper, 12 little-endian
     * u32s.
     */
    private const val HEADER_BYTES = 48

    /**
     * How many fixed bytes actually precede the first band block: the 48-byte header PLUS the
     * 0x40000000 marker and the repeated size that follow it.
     *
     * These two are easy to read as part of the header (they sit immediately after it and one of
     * them is another copy of its length) and they are not: the size fields are both `total - 48`,
     * so folding them in makes every length in the packet wrong by eight. Allocating 48 here is what
     * made the encoder overflow its own buffer.
     */
    private const val PREAMBLE_BYTES = 56
    private const val MAGIC_1 = 0xcafe2580.toInt()
    private const val MAGIC_2 = 0x94119c00.toInt()
    private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

    // ── Little-endian helpers ──────────────────────────────────────────────────────────────────

    private fun ByteArrayOutputStream.writeLe32(v: Int) {
        write(v and 0xff); write((v ushr 8) and 0xff); write((v ushr 16) and 0xff); write((v ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe16(v: Int) {
        write(v and 0xff); write((v ushr 8) and 0xff)
    }

    private fun ByteArray.setLe32(at: Int, v: Int) {
        this[at] = (v and 0xff).toByte()
        this[at + 1] = ((v ushr 8) and 0xff).toByte()
        this[at + 2] = ((v ushr 16) and 0xff).toByte()
        this[at + 3] = ((v ushr 24) and 0xff).toByte()
    }

    /**
     * Base64 without `android.util.Base64`.
     *
     * `java.util.Base64` is API 26 and this app ships to minSdk 25, and the Android one cannot be
     * called from `:test-runner`. Twelve lines is cheaper than either problem.
     */
    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            sb.append(alphabet[b0 ushr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0f) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }
}
