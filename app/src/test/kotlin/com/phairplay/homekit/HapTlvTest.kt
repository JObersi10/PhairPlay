package com.phairplay.homekit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TLV8 is where a HomeKit implementation fails first and most silently: a fragmentation bug looks
 * like a wrong setup code, because the controller simply cannot parse the public key it was sent.
 */
class HapTlvTest {

    @Test
    fun `encodes a short value as one record`() {
        val out = HapTlv.encode(HapTlv.STATE to byteArrayOf(2))
        assertArrayEquals(byteArrayOf(HapTlv.STATE.toByte(), 1, 2), out)
    }

    @Test
    fun `round-trips multiple types preserving order`() {
        val encoded = HapTlv.encode(
            HapTlv.STATE to byteArrayOf(2),
            HapTlv.SALT to ByteArray(16) { it.toByte() },
        )
        val decoded = HapTlv.decode(encoded)
        assertArrayEquals(byteArrayOf(2), decoded[HapTlv.STATE])
        assertArrayEquals(ByteArray(16) { it.toByte() }, decoded[HapTlv.SALT])
    }

    @Test
    fun `fragments a value longer than 255 bytes and rejoins it on decode`() {
        // An SRP-3072 public key is 384 bytes, so every real pairing exercises this path.
        val key = ByteArray(384) { (it % 251).toByte() }
        val encoded = HapTlv.encode(HapTlv.PUBLIC_KEY to key)

        // 384 bytes → a full 255-byte record plus a 129-byte one, each with its own 2-byte header.
        assertEquals(2 + 255 + 2 + 129, encoded.size)
        assertEquals(255, encoded[1].toInt() and 0xFF)
        assertEquals(129, encoded[2 + 255 + 1].toInt() and 0xFF)

        assertArrayEquals(key, HapTlv.decode(encoded)[HapTlv.PUBLIC_KEY])
    }

    @Test
    fun `fragments a value that is an exact multiple of 255`() {
        val value = ByteArray(510) { 7 }
        val decoded = HapTlv.decode(HapTlv.encode(HapTlv.ENCRYPTED_DATA to value))
        assertArrayEquals(value, decoded[HapTlv.ENCRYPTED_DATA])
    }

    @Test
    fun `encodes an empty value as a zero-length record`() {
        val out = HapTlv.encode(HapTlv.SEPARATOR to ByteArray(0))
        assertArrayEquals(byteArrayOf(HapTlv.SEPARATOR.toByte(), 0), out)
    }

    @Test
    fun `truncated input yields the records that parsed rather than throwing`() {
        // This data arrives from an unauthenticated peer; a malformed record is a bad request to
        // answer, not an exception to propagate out of the connection thread.
        val good = HapTlv.encode(HapTlv.STATE to byteArrayOf(1))
        val truncated = good + byteArrayOf(HapTlv.PUBLIC_KEY.toByte(), 32, 1, 2, 3)
        val decoded = HapTlv.decode(truncated)
        assertArrayEquals(byteArrayOf(1), decoded[HapTlv.STATE])
        assertNull(decoded[HapTlv.PUBLIC_KEY])
    }

    @Test
    fun `decodes empty input to an empty map`() {
        assertTrue(HapTlv.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `error builds state plus error code`() {
        val decoded = HapTlv.decode(HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION))
        assertArrayEquals(byteArrayOf(4), decoded[HapTlv.STATE])
        assertArrayEquals(byteArrayOf(HapTlv.ERROR_AUTHENTICATION.toByte()), decoded[HapTlv.ERROR])
    }
}
