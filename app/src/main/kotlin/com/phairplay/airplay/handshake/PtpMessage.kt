package com.phairplay.airplay.handshake

/**
 * PtpMessage — IEEE 1588-2008 (PTPv2) wire format, the timing protocol AirPlay 2 multi-room uses.
 *
 * WHY this and not NTP: NTP synchronises us to the sender well enough for one room, but grouping
 * needs every receiver in the group to agree with the SAME grandmaster to sub-millisecond
 * accuracy. Apple's senders publish `timingProtocol=PTP` in SETUP when they intend to group, and
 * a receiver that only speaks NTP simply cannot join — it has no way to share the group's timebase.
 *
 * Only the subset AirPlay actually uses is implemented: Sync, Follow_Up, Delay_Req, Delay_Resp and
 * Announce. Everything else on the wire is parsed as a header and ignored, which is deliberate —
 * a general-purpose PTP stack would be far more code and none of the extra would ever run.
 *
 * Timestamps are the format's own: 48-bit seconds plus 32-bit nanoseconds, both big-endian. They
 * are converted to plain nanoseconds here, because every consumer wants arithmetic rather than
 * fields, and 2^48 seconds of range survives the conversion comfortably.
 */
object PtpMessage {

    const val HEADER_BYTES = 34
    private const val TIMESTAMP_BYTES = 10

    // Message types (low nibble of byte 0).
    const val TYPE_SYNC = 0x0
    const val TYPE_DELAY_REQ = 0x1
    const val TYPE_FOLLOW_UP = 0x8
    const val TYPE_DELAY_RESP = 0x9
    const val TYPE_ANNOUNCE = 0xB

    const val VERSION = 2

    /** Ports are fixed by the standard: event messages are timestamped, general ones are not. */
    const val PORT_EVENT = 319
    const val PORT_GENERAL = 320

    /**
     * A parsed PTP header plus whichever timestamp the body carried.
     *
     * @param timestampNanos the body's timestamp, or null for messages that have none of interest.
     * @param twoStep set when the sender will follow a Sync with a Follow_Up carrying the real
     *   origin time. One-step senders put it in the Sync itself, and the two must not be confused:
     *   treating a two-step Sync's (zero) timestamp as real produces an offset off by the whole
     *   epoch.
     */
    data class Header(
        val messageType: Int,
        val version: Int,
        val messageLength: Int,
        val domain: Int,
        val flags: Int,
        val correctionNanos: Long,
        val clockIdentity: Long,
        val portNumber: Int,
        val sequenceId: Int,
        val timestampNanos: Long?,
        val requestingClockIdentity: Long? = null,
        val requestingPortNumber: Int? = null,
    ) {
        val twoStep: Boolean get() = (flags and FLAG_TWO_STEP) != 0
    }

    private const val FLAG_TWO_STEP = 0x0200

    /**
     * Parses a datagram. Returns null when it is not a PTPv2 message we can use.
     *
     * Returning null rather than throwing is intentional: these arrive on a multicast port shared
     * with whatever else on the network speaks PTP, so foreign traffic is expected, not exceptional.
     */
    fun parse(data: ByteArray, length: Int = data.size): Header? {
        if (length < HEADER_BYTES) return null
        val version = data[1].toInt() and 0x0F
        if (version != VERSION) return null

        val messageType = data[0].toInt() and 0x0F
        val messageLength = be16(data, 2)
        val flags = be16(data, 6)

        // correctionField is nanoseconds scaled by 2^16 — the low 16 bits are sub-nanosecond and
        // are dropped here rather than carried as noise no consumer can act on.
        val correctionNanos = be64(data, 8) shr 16

        val clockIdentity = be64(data, 20)
        val portNumber = be16(data, 28)
        val sequenceId = be16(data, 30)

        var timestampNanos: Long? = null
        var reqClock: Long? = null
        var reqPort: Int? = null

        when (messageType) {
            TYPE_SYNC, TYPE_FOLLOW_UP, TYPE_DELAY_REQ, TYPE_ANNOUNCE -> {
                if (length >= HEADER_BYTES + TIMESTAMP_BYTES) {
                    timestampNanos = readTimestamp(data, HEADER_BYTES)
                }
            }
            TYPE_DELAY_RESP -> {
                // Delay_Resp carries the receive timestamp AND the identity of the port whose
                // Delay_Req it answers. Without checking that identity a receiver will happily
                // consume another device's response and compute a nonsense path delay.
                if (length >= HEADER_BYTES + TIMESTAMP_BYTES + 10) {
                    timestampNanos = readTimestamp(data, HEADER_BYTES)
                    reqClock = be64(data, HEADER_BYTES + TIMESTAMP_BYTES)
                    reqPort = be16(data, HEADER_BYTES + TIMESTAMP_BYTES + 8)
                }
            }
        }

        return Header(
            messageType = messageType,
            version = version,
            messageLength = messageLength,
            domain = data[4].toInt() and 0xFF,
            flags = flags,
            correctionNanos = correctionNanos,
            clockIdentity = clockIdentity,
            portNumber = portNumber,
            sequenceId = sequenceId,
            timestampNanos = timestampNanos,
            requestingClockIdentity = reqClock,
            requestingPortNumber = reqPort,
        )
    }

    /**
     * Builds a Delay_Req — the only message a receiver has to originate.
     *
     * Its origin timestamp is deliberately left zero: what matters is when the message actually
     * left, which the sender cannot know and we record locally as t3. Filling in a value here
     * would be a guess that the standard tells the responder to ignore anyway.
     */
    fun buildDelayReq(clockIdentity: Long, portNumber: Int, sequenceId: Int, domain: Int = 0): ByteArray {
        val out = ByteArray(HEADER_BYTES + TIMESTAMP_BYTES)
        out[0] = TYPE_DELAY_REQ.toByte()
        out[1] = VERSION.toByte()
        putBe16(out, 2, out.size)
        out[4] = domain.toByte()
        putBe64(out, 20, clockIdentity)
        putBe16(out, 28, portNumber)
        putBe16(out, 30, sequenceId)
        out[32] = CONTROL_DELAY_REQ.toByte()
        out[33] = LOG_INTERVAL_ONE_SHOT.toByte()
        return out
    }

    /** 48-bit seconds + 32-bit nanoseconds, both big-endian, flattened to nanoseconds. */
    internal fun readTimestamp(data: ByteArray, offset: Int): Long {
        var seconds = 0L
        for (i in 0 until 6) seconds = (seconds shl 8) or (data[offset + i].toLong() and 0xFF)
        val nanos = be32(data, offset + 6)
        return seconds * 1_000_000_000L + nanos
    }

    internal fun writeTimestamp(data: ByteArray, offset: Int, nanos: Long) {
        val seconds = nanos / 1_000_000_000L
        val rest = (nanos % 1_000_000_000L).toInt()
        for (i in 0 until 6) data[offset + i] = ((seconds shr (8 * (5 - i))) and 0xFF).toByte()
        putBe32(data, offset + 6, rest)
    }

    private const val CONTROL_DELAY_REQ = 0x01
    /** 0x7F means "this message is not periodic" — correct for an on-demand Delay_Req. */
    private const val LOG_INTERVAL_ONE_SHOT = 0x7F

    private fun be16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun putBe16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = ((v shr (8 * (3 - i))) and 0xFF).toByte()
    }

    private fun putBe64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
    }
}
