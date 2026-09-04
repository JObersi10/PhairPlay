package com.phairplay.airplay.handshake

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MirrorCrypto — key derivation and helpers for the AirPlay mirroring video stream.
 *
 * The stream is AES-128-CTR encrypted. RPiPlay's per-packet `og`/`nextDecryptCount`
 * bookkeeping (lib/mirror_buffer.c) reduces to a single continuous CTR keystream over the
 * concatenated video payloads (every full-block chunk leaves the cipher block-aligned, so
 * `aes_ctr_start_fresh_block` is always a no-op) — so one [Cipher] with sequential
 * `update()` per payload is exactly equivalent.
 *
 * Reference: RPiPlay lib/mirror_buffer.c (mirror_buffer_init_aes / mirror_buffer_decrypt).
 */
object MirrorCrypto {

    /**
     * Builds the AES-128-CTR cipher that decrypts the mirror video stream.
     *
     * key = SHA512("AirPlayStreamKey"+id ‖ eaeskey)[:16],
     * iv  = SHA512("AirPlayStreamIV"+id ‖ eaeskey)[:16],
     * where eaeskey = SHA512(aesKey ‖ ecdhSecret)[:16] and id is the unsigned decimal
     * streamConnectionID.
     */
    fun streamCipher(aesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long): Cipher {
        val eaeskey = sha512(aesKey + ecdhSecret).copyOf(16)
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII) + eaeskey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII) + eaeskey).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    /**
     * Converts AVCC (4-byte big-endian length-prefixed) NAL units — the format of a decrypted
     * mirror video payload — into Annex-B (00 00 00 01 start codes) that MediaCodec expects.
     */
    fun avccToAnnexB(data: ByteArray): ByteArray = avccToAnnexBInPlace(data.copyOf())

    /**
     * The same conversion, done by overwriting [data] rather than building a copy.
     *
     * A 4-byte big-endian length and a 4-byte start code are the same width, so the Annex-B form of
     * a well-formed payload is byte-for-byte the same *size* as the AVCC form — every length field
     * can simply be rewritten as `00 00 00 01` where it sits. The copying version allocated a
     * `ByteArrayOutputStream` (which reallocates as it grows) and then a final `toByteArray()`, so
     * a 1080p60 mirror paid two full copies of a few hundred KB sixty times a second purely to
     * change eight bytes in every NAL header.
     *
     * **[data] is modified.** The caller must own it — in practice it is the fresh array
     * `Cipher.update` just returned, which nothing else can see.
     *
     * A malformed or truncated tail is handled the way the copying version was: everything up to
     * the bad length field is kept and the remainder is dropped, which costs one copy in a case
     * that does not occur on a healthy stream.
     */
    fun avccToAnnexBInPlace(data: ByteArray): ByteArray {
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            if (len <= 0 || i + 4 + len > data.size) break
            data[i] = 0
            data[i + 1] = 0
            data[i + 2] = 0
            data[i + 3] = 1
            i += 4 + len
        }
        // The whole buffer parsed: hand back the very same array, no allocation at all.
        return if (i == data.size) data else data.copyOf(i)
    }

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Audio stream AES key: SHA-512(aesKey ‖ ecdhSecret)[:16] (the IV is the raw SETUP eiv). */
    fun audioKey(aesKey: ByteArray, ecdhSecret: ByteArray): ByteArray =
        sha512(aesKey + ecdhSecret).copyOf(16)

    private fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)
}
