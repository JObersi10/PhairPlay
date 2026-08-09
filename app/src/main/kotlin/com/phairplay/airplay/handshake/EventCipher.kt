package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.InputStream
import java.io.OutputStream

/**
 * EventCipher — ChaCha20-Poly1305 framing for the AirPlay 2 **event channel**.
 *
 * WHY: the event channel is encrypted, and we had been reading it as plaintext. The sender opens
 * the TCP connection to the port returned in SETUP and immediately switches to encryption, so every
 * byte we logged from it was ciphertext and every cleartext reply we wrote was noise the sender
 * discarded. That is why answering `200 OK` there never had an effect.
 *
 * The channel is logically **receiver → sender** even though the sender dials it, which is why the
 * output key is the *write* key here and the sender uses the same pair with the roles swapped.
 *
 * Keys come from the pair-verify shared secret via HKDF-SHA512:
 *
 * | Direction | Salt          | Info                            |
 * |-----------|---------------|---------------------------------|
 * | Output    | `Events-Salt` | `Events-Write-Encryption-Key`   |
 * | Input     | `Events-Salt` | `Events-Read-Encryption-Key`    |
 *
 * Framing is the HomeKit session-security frame Apple reuses across AirPlay: a 2-byte
 * little-endian plaintext length, which doubles as the AEAD associated data, then the ciphertext
 * and its 16-byte Poly1305 tag. Each direction has its own counter, starting at zero and
 * incrementing per frame — they must never be shared, or the second frame decrypts to garbage.
 */
class EventCipher(sharedSecret: ByteArray) {

    private val writeKey = hkdf(sharedSecret, EVENTS_SALT, WRITE_INFO)
    private val readKey = hkdf(sharedSecret, EVENTS_SALT, READ_INFO)

    private var writeCounter = 0L
    private var readCounter = 0L

    /** Encrypts one message as a single framed record and writes it. */
    fun write(out: OutputStream, plaintext: ByteArray) {
        val header = lengthHeader(plaintext.size)
        val sealed = seal(writeKey, nonce(writeCounter++), plaintext, header)
        out.write(header)
        out.write(sealed)
        out.flush()
    }

    /**
     * Reads exactly one framed record. Returns null at end of stream, which is the normal way this
     * channel closes when a session ends.
     *
     * @throws javax.crypto.AEADBadTagException equivalent (an [IllegalStateException] from the
     *   underlying engine) if the tag does not verify — meaning the keys or counters are wrong, and
     *   the caller should give up on the channel rather than keep reading misaligned frames.
     */
    fun read(input: InputStream): ByteArray? {
        val header = readFully(input, 2) ?: return null
        val length = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
        // Length + tag. A frame claiming more than the cap means we have lost frame alignment;
        // continuing would just consume the rest of the stream as garbage.
        if (length > MAX_FRAME) error("event frame claims $length bytes — stream is out of sync")
        val body = readFully(input, length + TAG_BYTES) ?: return null
        return open(readKey, nonce(readCounter++), body, header)
    }

    private fun lengthHeader(length: Int) =
        byteArrayOf((length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte())

    /** 12-byte nonce: four zero bytes then the little-endian frame counter. */
    private fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = ((counter shr (8 * i)) and 0xFF).toByte()
        return n
    }

    private fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        chacha(true, key, nonce, plaintext, aad)

    private fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
        chacha(false, key, nonce, sealed, aad)

    private fun chacha(
        encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray
    ): ByteArray {
        val engine = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        engine.init(
            encrypt,
            org.bouncycastle.crypto.params.AEADParameters(
                org.bouncycastle.crypto.params.KeyParameter(key), TAG_BYTES * 8, nonce, aad
            )
        )
        val out = ByteArray(engine.getOutputSize(input.size))
        val n = engine.processBytes(input, 0, input.size, out, 0)
        val total = n + engine.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n < 0) return null
            read += n
        }
        return buf
    }

    private companion object {
        val EVENTS_SALT = "Events-Salt".toByteArray(Charsets.US_ASCII)
        val WRITE_INFO = "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII)
        val READ_INFO = "Events-Read-Encryption-Key".toByteArray(Charsets.US_ASCII)

        const val TAG_BYTES = 16

        /** Frames are small control messages; anything larger means we have lost alignment. */
        const val MAX_FRAME = 256 * 1024

        fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val out = ByteArray(32)
            HKDFBytesGenerator(SHA512Digest()).apply {
                init(HKDFParameters(secret, salt, info))
                generateBytes(out, 0, out.size)
            }
            return out
        }
    }

    init {
        Logger.i("Event channel cipher ready (ChaCha20-Poly1305, HKDF-SHA512 off the pair-verify secret)")
    }
}
