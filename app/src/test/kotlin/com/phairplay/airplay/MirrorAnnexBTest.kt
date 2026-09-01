package com.phairplay.airplay

import com.phairplay.airplay.handshake.MirrorCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The in-place AVCC→Annex-B conversion runs on every mirrored video frame, so what matters is that
 * it produces exactly what the copying version did — including on the malformed input a glitching
 * sender can produce.
 */
class MirrorAnnexBTest {

    /** Builds an AVCC buffer: each NAL prefixed with its 4-byte big-endian length. */
    private fun avcc(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (nal in nals) {
            val n = nal.size
            out.add((n ushr 24).toByte())
            out.add((n ushr 16).toByte())
            out.add((n ushr 8).toByte())
            out.add(n.toByte())
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (nal in nals) {
            out.add(0); out.add(0); out.add(0); out.add(1)
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    @Test
    fun `a single NAL becomes a start code plus its payload`() {
        val nal = byteArrayOf(0x65, 1, 2, 3)
        assertArrayEquals(annexB(nal), MirrorCrypto.avccToAnnexBInPlace(avcc(nal)))
    }

    @Test
    fun `several NALs in one payload all get start codes`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00)
        val pps = byteArrayOf(0x68, (0xCE).toByte())
        val idr = byteArrayOf(0x65, 9, 9, 9, 9, 9)
        assertArrayEquals(annexB(sps, pps, idr), MirrorCrypto.avccToAnnexBInPlace(avcc(sps, pps, idr)))
    }

    @Test
    fun `a well-formed payload is converted without allocating a new array`() {
        // This is the whole point of the in-place version — the hot path must not copy the frame.
        val input = avcc(byteArrayOf(0x65, 1, 2, 3), byteArrayOf(0x41, 4, 5))
        assertSame(input, MirrorCrypto.avccToAnnexBInPlace(input))
    }

    @Test
    fun `a NAL length running past the end of the buffer drops the bad tail`() {
        val good = byteArrayOf(0x65, 1, 2, 3)
        // A second length field claiming 999 bytes that are not there.
        val truncated = avcc(good) + byteArrayOf(0, 0, 3, (0xE7).toByte(), 7, 7)
        assertArrayEquals(annexB(good), MirrorCrypto.avccToAnnexBInPlace(truncated))
    }

    @Test
    fun `a zero length is treated as the end of the parseable data`() {
        val good = byteArrayOf(0x65, 1, 2, 3)
        val withZero = avcc(good) + byteArrayOf(0, 0, 0, 0, 5, 5, 5, 5)
        assertArrayEquals(annexB(good), MirrorCrypto.avccToAnnexBInPlace(withZero))
    }

    @Test
    fun `trailing bytes too short to be a length field are dropped`() {
        val good = byteArrayOf(0x65, 1, 2, 3)
        val withStub = avcc(good) + byteArrayOf(0, 0)
        assertArrayEquals(annexB(good), MirrorCrypto.avccToAnnexBInPlace(withStub))
    }

    @Test
    fun `an empty payload converts to nothing`() {
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(ByteArray(0)).size)
    }

    @Test
    fun `the copying wrapper leaves its input untouched`() {
        val input = avcc(byteArrayOf(0x65, 1, 2, 3))
        val before = input.copyOf()
        MirrorCrypto.avccToAnnexB(input)
        assertArrayEquals("avccToAnnexB must not modify the caller's array", before, input)
    }
}
