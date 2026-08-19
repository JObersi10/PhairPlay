package com.phairplay.homekit

import java.io.ByteArrayOutputStream

/**
 * HapTlv — TLV8, the encoding every HomeKit pairing message is written in.
 *
 * WHY: HAP pairing (`/pair-setup`, `/pair-verify`) does not use plists or JSON. Each message is a
 * flat sequence of `type(1) length(1) value(length)` records. Only the pairing handshake uses it;
 * once a session is established everything switches to JSON.
 *
 * The one non-obvious rule is fragmentation: a length byte tops out at 255, so a longer value is
 * written as CONSECUTIVE records of the same type, and the reader must concatenate them. Public
 * keys (384 bytes for SRP-3072) always hit this, so getting it wrong breaks pairing immediately
 * rather than subtly.
 *
 * A repeated type that is NOT a continuation (two separate values of the same type) only appears
 * inside the pairing-list responses, which are separated by a zero-length [SEPARATOR] record.
 */
object HapTlv {

    // Types used by pair-setup / pair-verify (HAP spec table 5-6).
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04

    /**
     * kTLVType_Signature. NOT [PROOF].
     *
     * The two are easy to conflate and the spec uses both: PROOF (4) carries SRP proofs in M3/M4,
     * while SIGNATURE (10) carries the Ed25519 signature inside the encrypted sub-TLVs of M5/M6 and
     * pair-verify. Reading a signature out of PROOF finds nothing, and the pairing simply stops --
     * the controller sits waiting and iOS eventually reports that the accessory cannot be used.
     */
    const val SIGNATURE = 0x0A
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val PERMISSIONS = 0x0B

    /**
     * kTLVType_Flags. Carries the pairing-type bits a controller asks for in M1.
     *
     * Only [FLAG_TRANSIENT] matters here, and only to report it: transient pairing stops after M4
     * and derives the session keys straight from the SRP shared secret, which needs HKDF salt/info
     * strings PhairPlay has no verified reference for. Rather than guess them and produce a
     * handshake that completes with a wrong key -- the exact failure mode FairPlay v2 already has --
     * the request is logged so the device log can answer whether any real sender asks for it.
     */
    const val FLAGS = 0x13

    /** kPairingFlag_Transient (1 << 4). */
    const val FLAG_TRANSIENT = 0x10
    const val SEPARATOR = 0xFF

    // Error codes (HAP spec table 5-7).
    const val ERROR_UNKNOWN = 0x01
    const val ERROR_AUTHENTICATION = 0x02
    const val ERROR_BACKOFF = 0x03
    const val ERROR_MAX_PEERS = 0x04
    const val ERROR_MAX_TRIES = 0x05
    const val ERROR_UNAVAILABLE = 0x06
    const val ERROR_BUSY = 0x07

    private const val MAX_FRAGMENT = 255

    /**
     * Decodes a TLV8 buffer into type → value, concatenating fragments of the same type.
     *
     * Truncated input yields what parsed cleanly rather than throwing: this data arrives straight
     * off a socket from an unauthenticated peer, and a malformed record is a bad request to answer
     * with an error, not a crash to propagate.
     */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArrayOutputStream>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            if (i + 2 + len > data.size) break        // truncated record — keep what we have
            out.getOrPut(type) { ByteArrayOutputStream() }.write(data, i + 2, len)
            i += 2 + len
        }
        return out.mapValues { it.value.toByteArray() }
    }

    /** Encodes pairs in the given order, fragmenting any value longer than 255 bytes. */
    fun encode(vararg entries: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in entries) {
            if (value.isEmpty()) {
                out.write(type); out.write(0)
                continue
            }
            var off = 0
            while (off < value.size) {
                val n = minOf(MAX_FRAGMENT, value.size - off)
                out.write(type); out.write(n); out.write(value, off, n)
                off += n
            }
        }
        return out.toByteArray()
    }

    /** Single-byte value helper — [STATE], [METHOD] and [ERROR] are always one byte. */
    fun byte(value: Int): ByteArray = byteArrayOf(value.toByte())

    /** `state` + `error`, the reply shape for every failed pairing step. */
    fun error(state: Int, code: Int): ByteArray =
        encode(STATE to byte(state), ERROR to byte(code))
}
