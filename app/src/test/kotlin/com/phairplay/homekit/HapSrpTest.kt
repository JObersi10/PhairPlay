package com.phairplay.homekit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Drives [HapSrp] with an independently written CLIENT implementation of SRP-6a.
 *
 * Writing the client separately is the point: it is the only way to catch a wrong constant or a
 * missing pad without a real iPhone. Both sides arriving at the same session key from different
 * code is strong evidence the parameters match the spec, whereas testing the server against itself
 * would pass with the AirPlay group substituted for HomeKit's.
 */
class HapSrpTest {

    @Test
    fun `client and accessory agree on the session key for the right code`() {
        val code = "31415926"
        val server = HapSrp(code)
        val client = Client(code, server.salt, server.serverPublic)

        val m2 = server.verify(client.aBytes, client.proof())

        assertNotNull("accessory rejected a correct setup code", m2)
        assertArrayEquals(client.sessionKey, server.sessionKey)
        // The accessory's own proof must verify on the client, or a real controller aborts at M4.
        assertArrayEquals(client.expectedServerProof(), m2)
    }

    @Test
    fun `a wrong setup code is rejected`() {
        val server = HapSrp("31415926")
        val client = Client("00000001", server.salt, server.serverPublic)

        assertNull(server.verify(client.aBytes, client.proof()))
        assertNull("session key must not be set on a failed proof", server.sessionKey)
    }

    @Test
    fun `public value congruent to zero is rejected`() {
        // RFC 5054: A ≡ 0 (mod N) would let anyone compute S, so it must never be accepted.
        val server = HapSrp("31415926")
        assertNull(server.verify(ByteArray(384), ByteArray(64)))
    }

    @Test
    fun `each instance uses a fresh salt`() {
        val a = HapSrp("31415926").salt
        val b = HapSrp("31415926").salt
        assert(!a.contentEquals(b)) { "salt must be random per pairing attempt" }
    }

    /** Minimal SRP-6a client, written from the RFC rather than from [HapSrp]. */
    private class Client(code: String, private val salt: ByteArray, private val b: BigInteger) {
        private val a = BigInteger(256, SecureRandom()).mod(N)
        private val aPub: BigInteger = G.modPow(a, N)
        val aBytes: ByteArray = unsigned(aPub)

        private val x = BigInteger(
            1,
            HapCrypto.sha512(salt, HapCrypto.sha512("$USERNAME:$code".toByteArray(Charsets.UTF_8))),
        )

        val sessionKey: ByteArray = run {
            val k = BigInteger(1, HapCrypto.sha512(pad(N), pad(G)))
            val u = BigInteger(1, HapCrypto.sha512(pad(aPub), pad(b)))
            // S = (B - k*g^x)^(a + u*x)
            val base = b.subtract(k.multiply(G.modPow(x, N))).mod(N)
            HapCrypto.sha512(unsigned(base.modPow(a.add(u.multiply(x)), N)))
        }

        fun proof(): ByteArray {
            val hn = HapCrypto.sha512(unsigned(N))
            val hg = HapCrypto.sha512(unsigned(G))
            val hx = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
            return HapCrypto.sha512(
                hx,
                HapCrypto.sha512(USERNAME.toByteArray(Charsets.UTF_8)),
                salt, unsigned(aPub), unsigned(b), sessionKey,
            )
        }

        fun expectedServerProof(): ByteArray =
            HapCrypto.sha512(unsigned(aPub), proof(), sessionKey)

        private fun pad(v: BigInteger): ByteArray {
            val raw = unsigned(v)
            val width = (N.bitLength() + 7) / 8
            if (raw.size >= width) return raw
            return ByteArray(width).also { System.arraycopy(raw, 0, it, width - raw.size, raw.size) }
        }

        companion object {
            const val USERNAME = "Pair-Setup"
            val G: BigInteger = BigInteger.valueOf(5)
            val N: BigInteger = BigInteger(
                "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
                    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
                    "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
                    "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
                16,
            )

            fun unsigned(v: BigInteger): ByteArray {
                val bytes = v.toByteArray()
                return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
            }
        }
    }
}
