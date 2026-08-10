package com.phairplay.homekit

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest

/**
 * HapCrypto — key derivation and the record framing that carries every post-pairing HAP message.
 *
 * WHY: HomeKit reuses the same primitives PhairPlay already has for AirPlay (HKDF-SHA512,
 * ChaCha20-Poly1305, Ed25519, X25519), but wires them together differently. Two things here are
 * easy to get subtly wrong and produce a session that pairs and then goes silent:
 *
 *  - **Nonce layout.** HAP nonces are 12 bytes: four zero bytes, then an 8-byte LITTLE-endian
 *    counter. AirPlay's event channel uses the same cipher with a different layout, so the two
 *    are not interchangeable.
 *  - **AAD.** Each encrypted record is prefixed with its 2-byte little-endian plaintext length,
 *    and those exact two bytes are the additional authenticated data. Omitting them authenticates
 *    fine locally and fails against a real controller.
 *
 * The pairing steps use fixed string nonces ("PS-Msg05" and friends) padded to 12 bytes instead of
 * a counter; [pairingNonce] builds those.
 */
object HapCrypto {

    const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val MAX_FRAME = 1024

    /** HKDF-SHA512 → 32-byte key. Every HAP key is derived this way; only salt/info differ. */
    fun hkdf(ikm: ByteArray, salt: String, info: String): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA512Digest()).apply {
            init(HKDFParameters(ikm, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8)))
        }.generateBytes(out, 0, out.size)
        return out
    }

    /**
     * Nonce for a pairing step: the 8-byte ASCII label right-aligned in 12 bytes (4 leading zeros).
     * Used by "PS-Msg05", "PS-Msg06", "PV-Msg02", "PV-Msg03".
     */
    fun pairingNonce(label: String): ByteArray {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 8) { "pairing nonce label too long: $label" }
        return ByteArray(NONCE_BYTES).also {
            System.arraycopy(bytes, 0, it, NONCE_BYTES - bytes.size, bytes.size)
        }
    }

    /** Nonce for a session record: 4 zero bytes then the counter, little-endian. */
    fun sessionNonce(counter: Long): ByteArray = ByteArray(NONCE_BYTES).also {
        var v = counter
        for (i in 4 until NONCE_BYTES) {
            it[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    /**
     * ChaCha20-Poly1305 seal/open, via BouncyCastle rather than the JCE.
     *
     * The JCE route is a portability trap: OpenJDK's provider requires an `IvParameterSpec` for
     * this transformation while Android's Conscrypt takes a `GCMParameterSpec`, so a JCE
     * implementation compiles everywhere and throws InvalidAlgorithmParameterException on one of
     * them. Going straight to BouncyCastle — which the project already bundles and which
     * EventCipher already uses for the AirPlay event channel — behaves identically on device and
     * under unit test, and removes the API-28 floor the JCE transformation would have imposed.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(forEncryption = true, key = key, nonce = nonce, input = plaintext, aad = aad)

    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(forEncryption = false, key = key, nonce = nonce, input = ciphertext, aad = aad)

    private fun chacha(
        forEncryption: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?,
    ): ByteArray {
        val engine = ChaCha20Poly1305()
        engine.init(forEncryption, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(engine.getOutputSize(input.size))
        val n = engine.processBytes(input, 0, input.size, out, 0)
        val total = n + engine.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    /** SHA-512, used for the SRP proofs and to hash the SRP shared secret into a session key. */
    fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").apply { parts.forEach { update(it) } }.digest()

    /**
     * Splits [plaintext] into HAP session records: `len(2, LE) || ciphertext || tag(16)`.
     *
     * HAP caps a record's plaintext at 1024 bytes, so anything larger — the /accessories database
     * is several KB — must be chunked. Each chunk consumes its own nonce counter value, which is
     * why the counter is passed by reference through [counter] and returned.
     */
    fun encodeFrames(key: ByteArray, startCounter: Long, plaintext: ByteArray): Pair<ByteArray, Long> {
        val out = java.io.ByteArrayOutputStream()
        var counter = startCounter
        var off = 0
        do {
            val n = minOf(MAX_FRAME, plaintext.size - off)
            val aad = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
            val chunk = plaintext.copyOfRange(off, off + n)
            out.write(aad)
            out.write(seal(key, sessionNonce(counter), chunk, aad))
            counter++
            off += n
        } while (off < plaintext.size)
        return out.toByteArray() to counter
    }
}
