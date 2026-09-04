package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * VideoDecoder stub for JVM test compilation.
 *
 * The real VideoDecoder depends on android.media.MediaCodec, android.media.MediaFormat,
 * and android.view.Surface — none of which can run in a plain JVM test.
 *
 * This stub exposes only the pure-logic members needed by VideoDecoderSpsTest:
 * - VideoDecoder.parseSpsResolution (companion-object function)
 * - VideoDecoder.Companion.SpsBitReader (nested class, matching production API)
 *
 * Instance methods are no-ops because no JVM test exercises the hardware-decode path.
 */
@Suppress("UNUSED_PARAMETER")
class VideoDecoder(outputSurface: Any?, onOutputSize: ((Int, Int) -> Unit)? = null) {

    // Matches the real VideoDecoder API used by MirrorStreamServer (self-heal flag).
    var isHealthy = true

    fun initialize(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {}
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long = 0L) {}
    /**
     * Matches the real decoder's in-place surface swap. Returns false so any caller takes the
     * "rebuild the decoder" branch, which is the path with no Android dependencies.
     */
    fun setOutputSurface(surface: Any?): Boolean = false

    val decodeErrorCount: Int = 0
    fun release() {}

    companion object {
        /**
         * Parses the H.264 SPS NAL unit to extract the actual video resolution.
         * Pure bit-manipulation; no Android API calls.
         */
        fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
            try {
                if (sps.size < 4) return null
                val rbsp = spsToRbsp(sps) ?: return null
                val reader = SpsBitReader(rbsp, startOffset = 0)

                val profileIdc = reader.readBits(8)
                reader.readBits(8)   // constraint flags + 2 reserved zeros
                reader.readBits(8)   // level_idc
                reader.readUe()      // seq_parameter_set_id

                // Default for Baseline/Main profiles per H.264 spec.
                var chromaFormatIdc = 1
                var separateColorPlaneFlag = 0

                val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
                if (profileIdc in highProfiles) {
                    chromaFormatIdc = reader.readUe()
                    if (chromaFormatIdc == 3) {
                        separateColorPlaneFlag = reader.readBits(1)
                    }
                    reader.readUe()
                    reader.readUe()
                    reader.readBits(1)
                    if (reader.readBits(1) == 1) {
                        val count = if (chromaFormatIdc != 3) 8 else 12
                        repeat(count) {
                            if (reader.readBits(1) == 1) reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }

                reader.readUe()
                val picOrderCntType = reader.readUe()
                when (picOrderCntType) {
                    0 -> reader.readUe()
                    1 -> {
                        reader.readBits(1)
                        reader.readSe()
                        reader.readSe()
                        repeat(reader.readUe()) { reader.readSe() }
                    }
                }

                reader.readUe()
                reader.readBits(1)

                val picWidthInMbsMinus1 = reader.readUe()
                val picHeightInMapUnitsMinus1 = reader.readUe()
                val frameMbsOnlyFlag = reader.readBits(1)
                if (frameMbsOnlyFlag == 0) {
                    reader.readBits(1)
                }
                reader.readBits(1)

                var cropLeft = 0
                var cropRight = 0
                var cropTop = 0
                var cropBottom = 0
                if (reader.readBits(1) == 1) {
                    cropLeft = reader.readUe()
                    cropRight = reader.readUe()
                    cropTop = reader.readUe()
                    cropBottom = reader.readUe()
                }

                val codedWidth = (picWidthInMbsMinus1 + 1) * 16
                val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

                val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

                val subWidthC = when (chromaArrayType) {
                    0 -> 1
                    1, 2 -> 2
                    else -> 1
                }
                val subHeightC = when (chromaArrayType) {
                    1 -> 2
                    else -> 1
                }

                val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
                val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

                val width = codedWidth - (cropLeft + cropRight) * cropUnitX
                val height = codedHeight - (cropTop + cropBottom) * cropUnitY
                if (width <= 0 || height <= 0) return null

                Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
                return Pair(width, height)

            } catch (e: Exception) {
                Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
                return null
            }
        }

        /**
         * Bitstream reader for H.264 NAL units. Pure bit-manipulation; no Android APIs.
         *
         * Kept as a companion-object nested class to match the production API used by tests.
         */
        /**
         * Mirror of the real VideoDecoder.spsToRbsp — see that file for why this exists. Callers
         * pass the NAL with an Annex-B start code (MediaCodec's csd-0 wants one), so the payload
         * offset has to be detected, and emulation-prevention bytes removed, before reading bits.
         */
        private fun spsToRbsp(sps: ByteArray): ByteArray? {
            var i = 0
            if (sps.size >= 4 && sps[0] == 0.toByte() && sps[1] == 0.toByte()) {
                i = when {
                    sps[2] == 1.toByte() -> 3
                    sps[2] == 0.toByte() && sps[3] == 1.toByte() -> 4
                    else -> 0
                }
            }
            if (i >= sps.size) return null
            if ((sps[i].toInt() and 0x1F) == 7) i++

            val out = ByteArray(sps.size - i)
            var n = 0
            var zeros = 0
            while (i < sps.size) {
                val b = sps[i]
                if (zeros >= 2 && b == 3.toByte()) {
                    zeros = 0
                } else {
                    out[n++] = b
                    zeros = if (b == 0.toByte()) zeros + 1 else 0
                }
                i++
            }
            return if (n < 4) null else out.copyOf(n)
        }

        class SpsBitReader(private val data: ByteArray, startOffset: Int) {
            private var bytePos = startOffset
            private var bitPos = 7  // MSB first

            fun readBit(): Int {
                if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
                val bit = (data[bytePos].toInt() ushr bitPos) and 1
                if (--bitPos < 0) {
                    bitPos = 7
                    bytePos++
                }
                return bit
            }

            fun readBits(n: Int): Int {
                var result = 0
                repeat(n) { result = (result shl 1) or readBit() }
                return result
            }

            fun readUe(): Int {
                var leadingZeros = 0
                while (readBit() == 0) {
                    if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
                }
                return if (leadingZeros == 0) 0
                else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }

            fun readSe(): Int {
                val k = readUe()
                return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
            }

            fun skipScalingList(size: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(size) {
                    if (nextScale != 0) {
                        val deltaScale = readSe()
                        nextScale = (lastScale + deltaScale + 256) % 256
                    }
                    lastScale = if (nextScale == 0) lastScale else nextScale
                }
            }
        }
    }
}
