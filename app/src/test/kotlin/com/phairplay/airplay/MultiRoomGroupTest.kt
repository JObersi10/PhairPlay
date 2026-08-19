package com.phairplay.airplay

import com.phairplay.airplay.handshake.MultiRoomGroup
import com.phairplay.airplay.handshake.PtpClock
import com.phairplay.airplay.handshake.PtpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MultiRoomGroupTest {

    private val sampleRate = 44100

    // ─── SETPEERS ────────────────────────────────────────────────────────────

    @Test
    fun `parses the plain SETPEERS address array`() {
        val peers = MultiRoomGroup.parsePeers(listOf("192.168.1.10", "192.168.1.11"))
        assertEquals(2, peers.size)
        assertEquals("192.168.1.10", peers[0].address)
        assertNull(peers[0].clockIdentity)
    }

    @Test
    fun `parses SETPEERSX dictionaries with clock identities`() {
        val body = listOf(
            mapOf(
                "Addresses" to listOf("192.168.1.10"),
                "ClockID" to 1234567890L,
                "ClockPorts" to mapOf("abc" to 319),
                "SupportsClockPortMatchingOverride" to true,
            ),
        )
        val peers = MultiRoomGroup.parsePeers(body)
        assertEquals(1, peers.size)
        assertEquals("192.168.1.10", peers[0].address)
        assertEquals(1234567890L, peers[0].clockIdentity)
        assertEquals(319, peers[0].clockPort)
        assertTrue(peers[0].supportsClockPortMatching)
    }

    @Test
    fun `ignores malformed peer entries rather than failing the whole list`() {
        // A group where one entry is junk should still yield the peers we can use.
        val peers = MultiRoomGroup.parsePeers(listOf("192.168.1.10", 42, mapOf("nope" to 1)))
        assertEquals(1, peers.size)
    }

    @Test
    fun `a non-list body yields no peers`() {
        assertTrue(MultiRoomGroup.parsePeers(null).isEmpty())
        assertTrue(MultiRoomGroup.parsePeers("not a list").isEmpty())
    }

    // ─── SETRATEANCHORTIME ───────────────────────────────────────────────────

    @Test
    fun `parses an anchor with a fractional network time`() {
        val anchor = MultiRoomGroup.parseAnchor(
            mapOf(
                "rtpTime" to 1_000_000L,
                "networkTimeSecs" to 1_700_000_000L,
                // Exactly half a second, expressed as a 2^-64 fraction.
                "networkTimeFrac" to Long.MIN_VALUE,   // 0x8000000000000000 as a signed Long
                "rate" to 1,
            ),
        )
        assertNotNull(anchor)
        assertEquals(1_000_000L, anchor!!.rtpTime)
        assertTrue(anchor.playing)

        val expected = 1_700_000_000L * 1_000_000_000L + 500_000_000L
        assertTrue(
            "fraction should decode to ~0.5s, got ${anchor.networkTimeNanos - 1_700_000_000L * 1_000_000_000L}ns",
            abs(anchor.networkTimeNanos - expected) < 1_000_000,
        )
    }

    @Test
    fun `rate zero means paused`() {
        val anchor = MultiRoomGroup.parseAnchor(
            mapOf("rtpTime" to 5L, "networkTimeSecs" to 100L, "networkTimeFrac" to 0L, "rate" to 0),
        )
        assertNotNull(anchor)
        assertFalse(anchor!!.playing)
    }

    @Test
    fun `an anchor without rtpTime is rejected`() {
        assertNull(MultiRoomGroup.parseAnchor(mapOf("networkTimeSecs" to 1L)))
    }

    // ─── Scheduling ──────────────────────────────────────────────────────────

    @Test
    fun `maps an RTP timestamp to a network time one second later`() {
        val group = MultiRoomGroup()
        val base = 1_000_000_000_000L
        group.setAnchor(MultiRoomGroup.Anchor(rtpTime = 0, networkTimeNanos = base, rate = 1.0))

        // Exactly one second of samples ahead of the anchor.
        val t = group.networkTimeForRtp(sampleRate.toLong(), sampleRate)
        assertEquals(base + 1_000_000_000L, t)
    }

    @Test
    fun `handles an RTP timestamp before the anchor`() {
        val group = MultiRoomGroup()
        val base = 1_000_000_000_000L
        group.setAnchor(MultiRoomGroup.Anchor(rtpTime = sampleRate.toLong(), networkTimeNanos = base, rate = 1.0))

        assertEquals(base - 1_000_000_000L, group.networkTimeForRtp(0, sampleRate))
    }

    @Test
    fun `refuses to schedule against a paused anchor`() {
        // The failure this prevents: extrapolating from a frozen anchor produces a deadline that
        // recedes further into the past every second, then a burst of catch-up audio on resume.
        val group = MultiRoomGroup()
        group.setAnchor(MultiRoomGroup.Anchor(rtpTime = 0, networkTimeNanos = 1_000L, rate = 0.0))
        assertNull(group.networkTimeForRtp(44_100, sampleRate))
        assertFalse(group.ready)
    }

    @Test
    fun `refuses to schedule with no anchor at all`() {
        assertNull(MultiRoomGroup().networkTimeForRtp(1000, sampleRate))
    }

    @Test
    fun `sub-second precision survives the division`() {
        // Computing seconds first and scaling afterwards would truncate this to zero.
        val group = MultiRoomGroup()
        group.setAnchor(MultiRoomGroup.Anchor(rtpTime = 0, networkTimeNanos = 0, rate = 1.0))
        // 441 samples at 44.1kHz is exactly 10ms.
        assertEquals(10_000_000L, group.networkTimeForRtp(441, sampleRate))
    }

    @Test
    fun `local deadline goes through the clock offset`() {
        val group = MultiRoomGroup()
        val clock = PtpClock()

        // Synchronise the clock to a known offset: grandmaster 4s ahead of us.
        val trueOffset = 4_000_000_000L
        val t1 = 100_000_000_000L
        val delay = 500_000L
        val t2 = t1 - trueOffset + delay
        clock.onSync(PtpMessage.parse(syncTwoStep(1))!!, t2)
        clock.onFollowUp(PtpMessage.parse(followUp(1, t1))!!)
        clock.onDelayReqSent(1, t2 + 1_000_000L)
        clock.onDelayResp(
            PtpMessage.parse(delayResp(1, t2 + 1_000_000L + trueOffset + delay))!!,
            OUR_ID, OUR_PORT,
        )
        assertEquals(trueOffset, clock.offsetNanos)

        val anchorNetwork = 200_000_000_000L
        group.setAnchor(MultiRoomGroup.Anchor(rtpTime = 0, networkTimeNanos = anchorNetwork, rate = 1.0))

        // One second of samples after the anchor, converted into OUR timebase.
        val local = group.localDeadlineNanos(sampleRate.toLong(), sampleRate, clock)
        assertEquals(anchorNetwork + 1_000_000_000L - trueOffset, local)
    }

    @Test
    fun `two receivers with different offsets land on the same instant`() {
        // The property multi-room actually needs: the same anchor, read through two clocks with
        // different offsets, must resolve to the same real-world moment.
        val anchorNetwork = 500_000_000_000L
        val group = MultiRoomGroup().apply {
            setAnchor(MultiRoomGroup.Anchor(rtpTime = 0, networkTimeNanos = anchorNetwork, rate = 1.0))
        }

        val a = clockWithOffset(3_000_000_000L)
        val b = clockWithOffset(-7_000_000_000L)

        val localA = group.localDeadlineNanos(0, sampleRate, a)!!
        val localB = group.localDeadlineNanos(0, sampleRate, b)!!

        // Each device's local deadline differs, but converting back to network time must agree —
        // that shared instant is what keeps the rooms together.
        assertEquals(anchorNetwork, a.ptpNanosAt(localA))
        assertEquals(anchorNetwork, b.ptpNanosAt(localB))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun clockWithOffset(offset: Long): PtpClock {
        val clock = PtpClock()
        val t1 = 100_000_000_000L
        val delay = 400_000L
        val t2 = t1 - offset + delay
        clock.onSync(PtpMessage.parse(syncTwoStep(1))!!, t2)
        clock.onFollowUp(PtpMessage.parse(followUp(1, t1))!!)
        clock.onDelayReqSent(1, t2 + 1_000_000L)
        clock.onDelayResp(
            PtpMessage.parse(delayResp(1, t2 + 1_000_000L + offset + delay))!!,
            OUR_ID, OUR_PORT,
        )
        return clock
    }

    private fun header(type: Int, seq: Int, body: Int, twoStep: Boolean = false): ByteArray {
        val out = ByteArray(PtpMessage.HEADER_BYTES + body)
        out[0] = type.toByte()
        out[1] = PtpMessage.VERSION.toByte()
        out[2] = ((out.size shr 8) and 0xFF).toByte()
        out[3] = (out.size and 0xFF).toByte()
        if (twoStep) out[6] = 0x02
        for (i in 0 until 8) out[20 + i] = ((GM_ID shr (8 * (7 - i))) and 0xFF).toByte()
        out[29] = 1
        out[30] = ((seq shr 8) and 0xFF).toByte()
        out[31] = (seq and 0xFF).toByte()
        return out
    }

    private fun syncTwoStep(seq: Int) = header(PtpMessage.TYPE_SYNC, seq, 10, twoStep = true)

    private fun followUp(seq: Int, origin: Long) =
        header(PtpMessage.TYPE_FOLLOW_UP, seq, 10)
            .also { PtpMessage.writeTimestamp(it, PtpMessage.HEADER_BYTES, origin) }

    private fun delayResp(seq: Int, receive: Long) =
        header(PtpMessage.TYPE_DELAY_RESP, seq, 20).also {
            PtpMessage.writeTimestamp(it, PtpMessage.HEADER_BYTES, receive)
            val o = PtpMessage.HEADER_BYTES + 10
            for (i in 0 until 8) it[o + i] = ((OUR_ID shr (8 * (7 - i))) and 0xFF).toByte()
            it[o + 8] = ((OUR_PORT shr 8) and 0xFF).toByte()
            it[o + 9] = (OUR_PORT and 0xFF).toByte()
        }

    private companion object {
        const val GM_ID = 0x0011223344556677L
        const val OUR_ID = 0x00AABBCCDDEEFF00L
        const val OUR_PORT = 1
    }
}
