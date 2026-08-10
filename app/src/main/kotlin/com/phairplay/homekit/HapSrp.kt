package com.phairplay.homekit

import java.math.BigInteger
import java.security.SecureRandom

/**
 * HapSrp — the accessory half of SRP-6a as HomeKit specifies it.
 *
 * Deliberately separate from [com.phairplay.airplay.handshake.LegacyPairSetupPin], which implements
 * the SAME algorithm with different parameters for AirPlay: 2048-bit group, generator 2, SHA-1.
 * HomeKit uses the RFC 5054 **3072-bit** group, generator **5**, and **SHA-512** throughout. The
 * two cannot share code without a parameter soup that hides which constant belongs to which
 * protocol, and a mismatched constant fails as "wrong password" — the least informative error
 * either protocol produces.
 *
 * Username is the fixed string "Pair-Setup"; the password is the 8-digit setup code shown on the
 * TV, formatted `XXX-XX-XXX`.
 */
class HapSrp(setupCode: String, random: SecureRandom = SecureRandom()) {

    /**
     * The SRP password.
     *
     * MUST be the dashed form, `123-45-678`. The controller derives its verifier from exactly the
     * string the user typed into the Home app, dashes included — so feeding the raw eight digits
     * here produces a different x, a different verifier, and a proof that can never match. It fails
     * as "incorrect setup code" with no other symptom, which is precisely how it hid: the code on
     * screen was right, the code typed in was right, and pairing still failed every time.
     *
     * Normalised here rather than at the call site so no future caller can reintroduce it.
     */
    private val password: String = formatCode(setupCode)

    /** Random 16-byte salt, sent to the controller in M2. */
    val salt: ByteArray = ByteArray(16).also { random.nextBytes(it) }

    private val x: BigInteger = run {
        // x = H(s | H(I | ":" | P))
        val inner = HapCrypto.sha512("$USERNAME:$password".toByteArray(Charsets.UTF_8))
        BigInteger(1, HapCrypto.sha512(salt, inner))
    }

    /** v = g^x mod N */
    private val verifier: BigInteger = G.modPow(x, N)

    private val b: BigInteger = BigInteger(256, random).mod(N)

    /** B = (k*v + g^b) mod N — the accessory's public value. */
    val serverPublic: BigInteger = run {
        val k = BigInteger(1, HapCrypto.sha512(pad(N), pad(G)))
        (k.multiply(verifier).add(G.modPow(b, N))).mod(N)
    }

    /**
     * B exactly as it goes on the wire — and therefore exactly as the controller will hash it.
     *
     * The proof covers B, so the bytes we send and the bytes we hash have to be the same object,
     * not two independent serialisations of the same number. Callers should send THIS rather than
     * re-encoding [serverPublic] themselves.
     */
    val publicBytes: ByteArray = toBytes(serverPublic)

    /** Set once [verify] succeeds: K = H(S), the input to every pair-setup key derivation. */
    var sessionKey: ByteArray? = null
        private set

    /**
     * Verifies the controller's proof M1 for its public value A.
     *
     * @return the accessory's proof M2 to send back, or null if the proof did not match — which
     *   for SRP means the user typed the wrong setup code, and is the only failure mode here.
     */
    fun verify(aBytes: ByteArray, clientProof: ByteArray): ByteArray? {
        val a = BigInteger(1, aBytes)
        // A ≡ 0 (mod N) would make S computable by anyone; RFC 5054 requires rejecting it.
        if (a.mod(N) == BigInteger.ZERO) return null

        val u = BigInteger(1, HapCrypto.sha512(pad(a), pad(serverPublic)))
        val s = a.multiply(verifier.modPow(u, N)).mod(N).modPow(b, N)
        val k = HapCrypto.sha512(toBytes(s))

        // M1 = H(H(N) xor H(g) | H(I) | s | A | B | K)
        val hn = HapCrypto.sha512(toBytes(N))
        val hg = HapCrypto.sha512(toBytes(G))
        val hx = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        // A is hashed as the controller SENT it, not as a re-serialised BigInteger. Round-tripping
        // through BigInteger silently drops a leading zero byte, so roughly one pairing in 256 would
        // fail for no discoverable reason.
        val expected = HapCrypto.sha512(
            hx,
            HapCrypto.sha512(USERNAME.toByteArray(Charsets.UTF_8)),
            salt, aBytes, publicBytes, k,
        )
        if (!java.security.MessageDigest.isEqual(expected, clientProof)) return null

        sessionKey = k
        // M2 = H(A | M1 | K)
        return HapCrypto.sha512(aBytes, clientProof, k)
    }

    /** Big-endian magnitude with no sign byte — SRP values are unsigned on the wire. */
    private fun toBytes(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    /** Left-pad to N's byte length. RFC 5054 pads both operands of H() to |N| when computing k and u. */
    private fun pad(v: BigInteger): ByteArray {
        val raw = toBytes(v)
        val width = (N.bitLength() + 7) / 8
        if (raw.size >= width) return raw
        return ByteArray(width).also { System.arraycopy(raw, 0, it, width - raw.size, raw.size) }
    }

    companion object {
        const val USERNAME = "Pair-Setup"

        /**
         * Normalises a setup code to the dashed `123-45-678` form the controller hashes.
         *
         * Accepts an already-dashed code unchanged, and leaves anything that is not eight digits
         * alone — a caller with a non-standard code is better served by its own string than by a
         * substring() that would throw.
         */
        fun formatCode(code: String): String {
            val digits = code.filter { it.isDigit() }
            if (digits.length != 8) return code
            return "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5, 8)}"
        }

        private val G = BigInteger.valueOf(5)

        /** RFC 5054 group 15 (3072-bit). HomeKit mandates this group; AirPlay uses the 2048-bit one. */
        private val N = BigInteger(
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
    }
}
