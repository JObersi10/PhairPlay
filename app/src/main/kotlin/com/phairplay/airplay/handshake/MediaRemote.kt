package com.phairplay.airplay.handshake

/**
 * MediaRemote (MRP) — the control vocabulary AirPlay 2 senders use instead of DACP.
 *
 * WHY THIS EXISTS: the TV remote's skip/next buttons do nothing for an AirPlay 2 sender. Those
 * senders never send the `DACP-ID` / `Active-Remote` headers [DacpClient][com.phairplay.airplay.DacpClient]
 * needs, so there is no legacy control address to talk to. What they *do* send is
 * `POST /command` with `type=updateMRSupportedCommands`, whose `params` carry a list of opaque
 * blobs. Those blobs are serialized MediaRemote **protobuf** `CommandInfo` messages — one per
 * command the sender is willing to accept — and the reply path is a protobuf `ProtocolMessage`
 * carrying a `SendCommandMessage`.
 *
 * Only the two message shapes we actually need are implemented, by hand, against the schemas
 * published in pyatv (`CommandInfo.proto`, `SendCommandMessage.proto`, `ProtocolMessage.proto`).
 * Pulling in a protobuf runtime and generated stubs for six bytes of output would cost far more
 * than it saves — but the field numbers below are not guesses, they are that schema:
 *
 * ```proto
 * message ProtocolMessage {          message CommandInfo {
 *   optional Type   type = 1;          optional Command command = 1;
 *   optional string uniqueIdentifier = 85;   optional bool enabled = 2;
 *   extend { SendCommandMessage sendCommandMessage = 6; }  ...
 * }                                  }
 * message SendCommandMessage { optional Command command = 1; ... }
 * ```
 *
 * `Type.SEND_COMMAND_MESSAGE` is 1.
 */
object MediaRemote {

    // ---- Command enum (CommandInfo.proto). Only the ones a TV remote can produce. ----

    const val PLAY = 1
    const val PAUSE = 2
    const val TOGGLE_PLAY_PAUSE = 3
    const val STOP = 4
    const val NEXT_TRACK = 5
    const val PREVIOUS_TRACK = 6
    const val BEGIN_FAST_FORWARD = 9
    const val END_FAST_FORWARD = 10
    const val BEGIN_REWIND = 11
    const val END_REWIND = 12
    const val SKIP_FORWARD = 18
    const val SKIP_BACKWARD = 19

    /** One entry decoded out of `mrSupportedCommandsFromSender`. */
    data class SupportedCommand(val command: Int, val enabled: Boolean) {
        override fun toString() = "${name(command)}${if (enabled) "" else "(disabled)"}"
    }

    /** Human-readable name for a `Command` value, for logs. */
    fun name(command: Int): String = NAMES[command] ?: "Command$command"

    /**
     * Encodes a `ProtocolMessage{ type: SEND_COMMAND_MESSAGE, sendCommandMessage: { command } }`.
     *
     * `uniqueIdentifier` is included because real senders always populate it and pyatv's decoder
     * notes that a message without it is short enough to be mistaken for a length prefix. It costs
     * a few bytes and removes a whole class of "the other end quietly ignored us".
     */
    fun encodeSendCommand(command: Int, identifier: String = randomIdentifier()): ByteArray {
        val inner = varintField(FIELD_COMMAND, command.toLong())
        // Field order: declared fields first, then the extension — byte-for-byte what protobuf
        // itself emits for this message (verified against pyatv's generated serializer). Order
        // carries no meaning in protobuf, but matching the reference output costs nothing and
        // removes it as a suspect if a sender turns out to be fussy.
        return varintField(FIELD_TYPE, TYPE_SEND_COMMAND.toLong()) +
            lengthField(FIELD_UNIQUE_IDENTIFIER, identifier.toByteArray(Charsets.US_ASCII)) +
            lengthField(FIELD_SEND_COMMAND_MESSAGE, inner)
    }

    /**
     * Decodes one `CommandInfo` blob. Returns null if the bytes are not a protobuf we recognise —
     * which is information in itself, and the caller logs it rather than pretending otherwise.
     *
     * Every field except `command` and `enabled` is skipped by wire type, so schema fields we do
     * not model (localized titles, supported rates, queue metadata) cost nothing.
     */
    fun decodeCommandInfo(blob: ByteArray): SupportedCommand? {
        var command: Int? = null
        var enabled = true
        var i = 0
        while (i < blob.size) {
            val (tag, afterTag) = readVarint(blob, i) ?: return null
            i = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 0x7L).toInt()) {
                WIRE_VARINT -> {
                    val (value, next) = readVarint(blob, i) ?: return null
                    i = next
                    when (field) {
                        FIELD_COMMAND -> command = value.toInt()
                        FIELD_ENABLED -> enabled = value != 0L
                    }
                }
                WIRE_64BIT -> i += 8
                WIRE_LENGTH -> {
                    val (len, next) = readVarint(blob, i) ?: return null
                    i = next + len.toInt()
                }
                WIRE_32BIT -> i += 4
                else -> return null // groups: not used by this schema, and we would desynchronise
            }
            if (i > blob.size) return null
        }
        return command?.let { SupportedCommand(it, enabled) }
    }

    // ---- protobuf wire format ----

    private fun varintField(field: Int, value: Long) = varint(tag(field, WIRE_VARINT)) + varint(value)

    private fun lengthField(field: Int, payload: ByteArray) =
        varint(tag(field, WIRE_LENGTH)) + varint(payload.size.toLong()) + payload

    private fun tag(field: Int, wire: Int) = ((field.toLong() shl 3) or wire.toLong())

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); break }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    /** Reads a varint at [offset]; returns the value and the offset just past it, or null if truncated. */
    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun randomIdentifier() = java.util.UUID.randomUUID().toString().uppercase()

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_32BIT = 5

    /** ProtocolMessage.Type.SEND_COMMAND_MESSAGE. */
    private const val TYPE_SEND_COMMAND = 1

    private const val FIELD_TYPE = 1
    private const val FIELD_COMMAND = 1
    private const val FIELD_ENABLED = 2
    private const val FIELD_SEND_COMMAND_MESSAGE = 6
    private const val FIELD_UNIQUE_IDENTIFIER = 85

    private val NAMES = mapOf(
        1 to "Play", 2 to "Pause", 3 to "TogglePlayPause", 4 to "Stop",
        5 to "NextTrack", 6 to "PreviousTrack", 7 to "AdvanceShuffleMode",
        8 to "AdvanceRepeatMode", 9 to "BeginFastForward", 10 to "EndFastForward",
        11 to "BeginRewind", 12 to "EndRewind", 13 to "Rewind15Seconds",
        14 to "FastForward15Seconds", 15 to "Rewind30Seconds", 16 to "FastForward30Seconds",
        18 to "SkipForward", 19 to "SkipBackward", 20 to "ChangePlaybackRate",
        21 to "RateTrack", 22 to "LikeTrack", 23 to "DislikeTrack", 24 to "BookmarkTrack",
        45 to "SeekToPlaybackPosition", 46 to "ChangeRepeatMode", 47 to "ChangeShuffleMode",
    )
}
