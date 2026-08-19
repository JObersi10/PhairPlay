package com.phairplay.airplay

import com.phairplay.airplay.handshake.PtpClock
import com.phairplay.airplay.handshake.PtpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PTP is the multi-room blocker, and it is exactly the kind of protocol that "works" in a way that
 * is silently 100ms wrong. These tests pin the wire format byte-by-byte and drive the clock through
 * a simulated exchange with a KNOWN offset and delay, so the arithmetic is checked against truth
 * rather than against itself.
 */
class PtpTest {

    private val gmIdentity = 0x0011223344556677L
    private val ourIdentity = 0x00AABBCCDDEEFF00L
    private val ourPort = 1

    // ─── Wire format ─────────────────────────────────────────────────────────

    @Test
    fun `parses a two-step Sync header`() {
        val msg = sync(sequenceId = 42, twoStep = true, timestampNanos = 0)
        val h = PtpMessage.parse(msg)

        assertNotNull(h)
        assertEquals(PtpMessage.TYPE_SYNC, h!!.messageType)
        assertEquals(2, h.version)
        assertEquals(42, h.sequenceId)
        assertEquals(gmIdentity, h.clockIdentity)
        assertTrue("twoStep flag must be decoded — a one-step misread is an epoch-sized error", h.twoStep)
    }

    @Test
    fun `rejects a non-PTPv2 datagram`() {
        // The PTP port is multicast and shared; foreign traffic is expected, not exceptional.
        val v1 = ByteArray(64).also { it[1] = 1 }
        assertNull(PtpMessage.parse(v1))
        assertNull(PtpMessage.parse(ByteArray(10)))
    }

    @Test
    fun `round-trips a 48-bit seconds timestamp`() {
        // Well past 2^32 seconds, which is where a naive 32-bit seconds field silently wraps.
        val nanos = 1_800_000_000L * 1_000_000_000L + 123_456_789L
        val buf = ByteArray(10)
        PtpMessage.writeTimestamp(buf, 0, nanos)
        assertEquals(nanos, PtpMessage.readTimestamp(buf, 0))
    }

    @Test
    fun `correction field is scaled by two to the sixteen`() {
        // correctionField is nanoseconds << 16. Forgetting the shift inflates it 65536x.
        val msg = sync(sequenceId = 1, twoStep = false, timestampNanos = 0)
        val correctionNanos = 1_500L
        var v = correctionNanos shl 16
        for (i in 0 until 8) msg[8 + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()

        assertEquals(correctionNanos, PtpMessage.parse(msg)!!.correctionNanos)
    }

    @Test
    fun `builds a Delay_Req that parses back`() {
        val req = PtpMessage.buildDelayReq(ourIdentity, ourPort, sequenceId = 7)
        val h = PtpMessage.parse(req)

        assertNotNull(h)
        assertEquals(PtpMessage.TYPE_DELAY_REQ, h!!.messageType)
        assertEquals(ourIdentity, h.clockIdentity)
        assertEquals(7, h.sequenceId)
    }

    @Test
    fun `parses the requesting port identity out of a Delay_Resp`() {
        val resp = delayResp(sequenceId = 5, receiveNanos = 999, reqClock = ourIdentity, reqPort = ourPort)
        val h = PtpMessage.parse(resp)!!
        assertEquals(ourIdentity, h.requestingClockIdentity)
        assertEquals(ourPort, h.requestingPortNumber)
    }

    // ─── Clock arithmetic ────────────────────────────────────────────────────

    @Test
    fun `recovers a known offset and path delay from a full exchange`() {
        val clock = PtpClock()
        val trueOffset = 5_000_000_000L      // grandmaster is 5s ahead of us
        val trueDelay = 500_000L             // 500us each way

        exchange(clock, seq = 1, gmSendNanos = 100_000_000_000L, trueOffset = trueOffset, trueDelay = trueDelay)

        assertTrue(clock.synchronised)
        assertEquals(trueDelay, clock.pathDelayNanos)
        assertEquals(trueOffset, clock.offsetNanos)
    }

    @Test
    fun `two-step Sync needs its Follow_Up before the offset is right`() {
        val clock = PtpClock()
        val t1 = 50_000_000_000L
        val trueOffset = 2_000_000_000L
        val trueDelay = 300_000L
        val t2 = t1 - trueOffset + trueDelay

        // A two-step Sync carries a placeholder timestamp; consuming it as real would be wrong by
        // the whole epoch, so nothing may be concluded until the Follow_Up arrives.
        clock.onSync(PtpMessage.parse(sync(1, twoStep = true, timestampNanos = 0))!!, t2)
        assertFalse("must not synchronise on a two-step Sync alone", clock.synchronised)

        clock.onFollowUp(PtpMessage.parse(followUp(1, t1))!!)
        assertTrue(clock.synchronised)
    }

    @Test
    fun `ignores a Follow_Up whose sequence does not match`() {
        val clock = PtpClock()
        clock.onSync(PtpMessage.parse(sync(1, twoStep = true, timestampNanos = 0))!!, 1_000)
        clock.onFollowUp(PtpMessage.parse(followUp(99, 500))!!)
        assertFalse("a mismatched Follow_Up must not pair with our Sync", clock.synchronised)
    }

    @Test
    fun `ignores a Delay_Resp addressed to another receiver`() {
        // The whole group hears every Delay_Resp. Consuming a peer's response yields a path delay
        // computed from their request time and ours -- pure nonsense.
        val clock = PtpClock()
        val t1 = 10_000_000_000L
        val trueOffset = 1_000_000_000L
        val trueDelay = 400_000L

        clock.onSync(PtpMessage.parse(sync(1, twoStep = true, 0))!!, t1 - trueOffset + trueDelay)
        clock.onFollowUp(PtpMessage.parse(followUp(1, t1))!!)
        val before = clock.offsetNanos

        clock.onDelayReqSent(1, 20_000_000_000L)
        val other = delayResp(1, receiveNanos = 12345, reqClock = 0xDEADBEEFL, reqPort = 9)
        clock.onDelayResp(PtpMessage.parse(other)!!, ourIdentity, ourPort)

        assertEquals("offset must be untouched by another receiver's response", before, clock.offsetNanos)
        assertEquals(0L, clock.pathDelayNanos)
    }

    @Test
    fun `ignores a second grandmaster`() {
        // Another AirPlay group in the next room is another master on the same multicast group.
        val clock = PtpClock()
        clock.onSync(PtpMessage.parse(sync(1, twoStep = false, timestampNanos = 1_000_000_000L))!!, 500_000_000L)
        val firstOffset = clock.offsetNanos

        val foreign = sync(2, twoStep = false, timestampNanos = 9_000_000_000L, clockIdentity = 0x999L)
        clock.onSync(PtpMessage.parse(foreign)!!, 600_000_000L)

        assertEquals("a foreign master must not move our estimate", firstOffset, clock.offsetNanos)
    }

    @Test
    fun `discards a measurement implying negative path delay`() {
        val clock = PtpClock()
        clock.onSync(PtpMessage.parse(sync(1, twoStep = false, timestampNanos = 10_000_000_000L))!!, 10_000_000_000L)
        clock.onDelayReqSent(1, 20_000_000_000L)
        // t4 far earlier than t3 → the two directions were timed against inconsistent clocks.
        clock.onDelayResp(
            PtpMessage.parse(delayResp(1, receiveNanos = 1_000_000_000L, reqClock = ourIdentity, reqPort = ourPort))!!,
            ourIdentity, ourPort,
        )
        assertEquals("a physically impossible delay must not be averaged in", 0L, clock.pathDelayNanos)
    }

    @Test
    fun `converts grandmaster time to local time`() {
        val clock = PtpClock()
        val trueOffset = 3_000_000_000L
        exchange(clock, seq = 1, gmSendNanos = 80_000_000_000L, trueOffset = trueOffset, trueDelay = 250_000L)

        val gmTime = 90_000_000_000L
        assertEquals(gmTime - trueOffset, clock.localNanosAt(gmTime))
        assertEquals(gmTime, clock.ptpNanosAt(gmTime - trueOffset))
    }

    @Test
    fun `tracks offset across repeated exchanges with jitter`() {
        val clock = PtpClock()
        val trueOffset = 7_000_000_000L
        val baseDelay = 400_000L

        for (seq in 1..20) {
            val jitter = if (seq % 3 == 0) 120_000L else 0L    // occasional slow packet
            exchange(
                clock, seq = seq,
                gmSendNanos = 100_000_000_000L + seq * 1_000_000_000L,
                trueOffset = trueOffset, trueDelay = baseDelay + jitter,
            )
        }

        // Jitter shows up as delay-symmetry error, so the offset should stay within about half of it.
        assertTrue(
            "offset drifted to ${clock.offsetNanos - trueOffset}ns",
            abs(clock.offsetNanos - trueOffset) <= 60_000,
        )
        assertEquals("best path delay should settle on the clean samples", baseDelay, clock.bestPathDelayNanos)
    }

    @Test
    fun `reset clears the grandmaster lock`() {
        val clock = PtpClock()
        clock.onSync(PtpMessage.parse(sync(1, twoStep = false, timestampNanos = 1_000))!!, 500)
        assertTrue(clock.synchronised)
        clock.reset()
        assertFalse(clock.synchronised)
        assertNull(clock.grandmasterIdentity)
    }

    // ─── Helpers: build real PTP datagrams ───────────────────────────────────

    /** Runs one Sync / Follow_Up / Delay_Req / Delay_Resp cycle against a simulated grandmaster. */
    private fun exchange(
        clock: PtpClock, seq: Int, gmSendNanos: Long, trueOffset: Long, trueDelay: Long,
    ) {
        val t1 = gmSendNanos
        val t2 = t1 - trueOffset + trueDelay          // our clock lags the master by trueOffset
        clock.onSync(PtpMessage.parse(sync(seq, twoStep = true, timestampNanos = 0))!!, t2)
        clock.onFollowUp(PtpMessage.parse(followUp(seq, t1))!!)

        val t3 = t2 + 1_000_000L                       // we reply 1ms later, local time
        val t4 = t3 + trueOffset + trueDelay           // master receives it, master time
        clock.onDelayReqSent(seq, t3)
        clock.onDelayResp(
            PtpMessage.parse(delayResp(seq, t4, ourIdentity, ourPort))!!,
            ourIdentity, ourPort,
        )
    }

    private fun header(
        type: Int, sequenceId: Int, bodyBytes: Int, twoStep: Boolean = false,
        clockIdentity: Long = gmIdentity,
    ): ByteArray {
        val out = ByteArray(PtpMessage.HEADER_BYTES + bodyBytes)
        out[0] = type.toByte()
        out[1] = PtpMessage.VERSION.toByte()
        out[2] = ((out.size shr 8) and 0xFF).toByte()
        out[3] = (out.size and 0xFF).toByte()
        // twoStepFlag is bit 1 of the flag field's FIRST octet (byte 6), so the 16-bit flag value
        // is 0x0200. Setting byte 7 instead yields 0x0002 and the flag reads as clear.
        if (twoStep) out[6] = 0x02
        for (i in 0 until 8) out[20 + i] = ((clockIdentity shr (8 * (7 - i))) and 0xFF).toByte()
        out[29] = 1                              // port number 1
        out[30] = ((sequenceId shr 8) and 0xFF).toByte()
        out[31] = (sequenceId and 0xFF).toByte()
        return out
    }

    private fun sync(
        sequenceId: Int, twoStep: Boolean, timestampNanos: Long, clockIdentity: Long = gmIdentity,
    ): ByteArray = header(PtpMessage.TYPE_SYNC, sequenceId, 10, twoStep, clockIdentity)
        .also { PtpMessage.writeTimestamp(it, PtpMessage.HEADER_BYTES, timestampNanos) }

    private fun followUp(sequenceId: Int, originNanos: Long): ByteArray =
        header(PtpMessage.TYPE_FOLLOW_UP, sequenceId, 10)
            .also { PtpMessage.writeTimestamp(it, PtpMessage.HEADER_BYTES, originNanos) }

    private fun delayResp(sequenceId: Int, receiveNanos: Long, reqClock: Long, reqPort: Int): ByteArray =
        header(PtpMessage.TYPE_DELAY_RESP, sequenceId, 20).also {
            PtpMessage.writeTimestamp(it, PtpMessage.HEADER_BYTES, receiveNanos)
            val o = PtpMessage.HEADER_BYTES + 10
            for (i in 0 until 8) it[o + i] = ((reqClock shr (8 * (7 - i))) and 0xFF).toByte()
            it[o + 8] = ((reqPort shr 8) and 0xFF).toByte()
            it[o + 9] = (reqPort and 0xFF).toByte()
        }
}
