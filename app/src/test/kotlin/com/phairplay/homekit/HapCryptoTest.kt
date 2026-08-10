package com.phairplay.homekit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The two things that make a HomeKit session pair successfully and then go silent are the nonce
 * layout and the AAD. Both are asserted here against their exact byte-level definitions rather
 * than round-tripped, because a round-trip passes happily with two matching wrong implementations.
 */
class HapCryptoTest {

    @Test
    fun `session nonce is four zero bytes then a little-endian counter`() {
        assertArrayEquals(ByteArray(12), HapCrypto.sessionNonce(0))

        val one = HapCrypto.sessionNonce(1)
        assertArrayEquals(ByteArray(4), one.copyOfRange(0, 4))
        assertEquals(1, one[4].toInt())          // low byte first
        assertEquals(0, one[5].toInt())

        val big = HapCrypto.sessionNonce(0x0102)
        assertEquals(0x02, big[4].toInt())
        assertEquals(0x01, big[5].toInt())
    }

    @Test
    fun `pairing nonce right-aligns the label in twelve bytes`() {
        val nonce = HapCrypto.pairingNonce("PS-Msg05")
        assertEquals(12, nonce.size)
        assertArrayEquals(ByteArray(4), nonce.copyOfRange(0, 4))
        assertArrayEquals("PS-Msg05".toByteArray(Charsets.US_ASCII), nonce.copyOfRange(4, 12))
    }

    @Test
    fun `hkdf is deterministic and separated by salt and info`() {
        val ikm = ByteArray(32) { it.toByte() }
        val a = HapCrypto.hkdf(ikm, "Control-Salt", "Control-Read-Encryption-Key")
        val b = HapCrypto.hkdf(ikm, "Control-Salt", "Control-Read-Encryption-Key")
        val c = HapCrypto.hkdf(ikm, "Control-Salt", "Control-Write-Encryption-Key")

        assertEquals(32, a.size)
        assertArrayEquals(a, b)
        // Read and write keys differing is what keeps the two directions independent; if info were
        // ignored these would match and the session would still "work" until a real controller.
        assertNotEquals(a.toList(), c.toList())
    }

    @Test
    fun `seal and open round-trip with matching aad`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = HapCrypto.sessionNonce(7)
        val aad = byteArrayOf(5, 0)
        val plaintext = "hello homekit".toByteArray()

        val sealed = HapCrypto.seal(key, nonce, plaintext, aad)
        assertEquals(plaintext.size + HapCrypto.TAG_BYTES, sealed.size)
        assertArrayEquals(plaintext, HapCrypto.open(key, nonce, sealed, aad))
    }

    @Test
    fun `open fails when the aad does not match`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = HapCrypto.sessionNonce(1)
        val sealed = HapCrypto.seal(key, nonce, "x".toByteArray(), byteArrayOf(1, 0))
        assertThrows(Exception::class.java) {
            HapCrypto.open(key, nonce, sealed, byteArrayOf(2, 0))
        }
    }

    @Test
    fun `frames carry a little-endian length prefix that is also the aad`() {
        val key = ByteArray(32) { 9 }
        val plaintext = ByteArray(300) { it.toByte() }

        val (framed, counter) = HapCrypto.encodeFrames(key, 0, plaintext)

        assertEquals(1, counter)
        assertEquals(2 + 300 + HapCrypto.TAG_BYTES, framed.size)
        assertEquals(300 and 0xFF, framed[0].toInt() and 0xFF)
        assertEquals((300 shr 8) and 0xFF, framed[1].toInt() and 0xFF)

        val aad = framed.copyOfRange(0, 2)
        val body = framed.copyOfRange(2, framed.size)
        assertArrayEquals(plaintext, HapCrypto.open(key, HapCrypto.sessionNonce(0), body, aad))
    }

    @Test
    fun `payload larger than 1024 bytes is split into records each advancing the counter`() {
        // The /accessories database is several KB, so this path runs on every connection.
        val key = ByteArray(32) { 3 }
        val plaintext = ByteArray(2500) { it.toByte() }

        val (framed, counter) = HapCrypto.encodeFrames(key, 0, plaintext)
        assertEquals(3, counter)          // 1024 + 1024 + 452

        // Walk the records back exactly as a controller would.
        val recovered = java.io.ByteArrayOutputStream()
        var off = 0
        var n = 0L
        while (off < framed.size) {
            val len = (framed[off].toInt() and 0xFF) or ((framed[off + 1].toInt() and 0xFF) shl 8)
            val aad = framed.copyOfRange(off, off + 2)
            val body = framed.copyOfRange(off + 2, off + 2 + len + HapCrypto.TAG_BYTES)
            recovered.write(HapCrypto.open(key, HapCrypto.sessionNonce(n), body, aad))
            off += 2 + len + HapCrypto.TAG_BYTES
            n++
        }
        assertArrayEquals(plaintext, recovered.toByteArray())
    }
}
