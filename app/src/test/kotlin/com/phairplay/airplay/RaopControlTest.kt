package com.phairplay.airplay

import com.phairplay.homekit.HomeKitSetupPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three pieces that together explain why macOS Music never played anything: the
 * malformed SETUP Transport reply, the control packets we never listened for, and — separately —
 * the HomeKit QR payload added alongside them.
 */
class RaopControlTest {

    // ─── Transport parsing ───────────────────────────────────────────────────

    @Test
    fun `parses the three ports macOS Music sends`() {
        val t = RtspHandler.parseTransport(
            "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                "control_port=6001;timing_port=6002;client_port=6000",
        )
        assertEquals(6000, t.clientPort)
        assertEquals(6001, t.controlPort)
        assertEquals(6002, t.timingPort)
    }

    @Test
    fun `accepts the hyphenated spellings some senders use`() {
        val t = RtspHandler.parseTransport("RTP/AVP/UDP;unicast;control-port=7001;timing-port=7002")
        assertEquals(7001, t.controlPort)
        assertEquals(7002, t.timingPort)
    }

    @Test
    fun `takes the first port of a range`() {
        assertEquals(6000, RtspHandler.parseTransport("client_port=6000-6001").clientPort)
    }

    @Test
    fun `a malformed transport yields nulls rather than throwing`() {
        // Failing the SETUP outright would end a session that is otherwise perfectly workable.
        val t = RtspHandler.parseTransport("RTP/AVP/UDP;unicast;client_port=;control_port=nonsense;=5")
        assertNull(t.clientPort)
        assertNull(t.controlPort)
        assertNull(RtspHandler.parseTransport(null).clientPort)
        assertNull(RtspHandler.parseTransport("").timingPort)
    }

    // ─── Control packets ─────────────────────────────────────────────────────

    @Test
    fun `a sync packet reports its RTP anchor`() {
        var seen: Triple<Long, Long, Long>? = null
        val control = RaopControlHandler(onSync = { r, n, next -> seen = Triple(r, n, next) })

        control.handlePacket(syncPacket(rtp = 0x11223344L, ntp = 0x0102030405060708L, next = 0x11223400L), 20)

        assertEquals(0x11223344L, seen!!.first)
        assertEquals(0x0102030405060708L, seen!!.second)
        assertEquals(0x11223400L, seen!!.third)
        assertEquals(1L, control.syncCount)
    }

    @Test
    fun `an RTP timestamp with the high bit set stays positive`() {
        // Read as a signed Int this comes back negative and every downstream deadline computed from
        // it lands in 1969.
        var rtp = -1L
        val control = RaopControlHandler(onSync = { r, _, _ -> rtp = r })
        control.handlePacket(syncPacket(rtp = 0xF000_0001L, ntp = 0, next = 0), 20)
        assertTrue("RTP timestamp came back negative: $rtp", rtp > 0)
        assertEquals(0xF000_0001L, rtp)
    }

    @Test
    fun `a truncated sync packet is ignored rather than read past its end`() {
        var fired = false
        val control = RaopControlHandler(onSync = { _, _, _ -> fired = true })
        control.handlePacket(syncPacket(1, 1, 1), 12)
        assertTrue("short packet must not produce a sync", !fired)
    }

    @Test
    fun `a retransmit reply yields the original RTP packet without its control header`() {
        var recovered: ByteArray? = null
        val control = RaopControlHandler(onRetransmit = { recovered = it })

        val packet = ByteArray(12)
        packet[0] = 0x80.toByte()
        packet[1] = 0xD6.toByte()          // 0x56 | marker
        for (i in 4 until 12) packet[i] = (i - 3).toByte()

        control.handlePacket(packet, 12)
        assertEquals(8, recovered!!.size)
        assertEquals(1, recovered!![0].toInt())
    }

    @Test
    fun `resend requests are dropped when no sender has been seen`() {
        // Better to report failure than to address a datagram at nothing and call it sent.
        assertTrue(!RaopControlHandler().requestResend(firstSequence = 10, count = 4))
    }

    // ─── HomeKit setup payload ───────────────────────────────────────────────

    @Test
    fun `builds a fixed-width X-HM setup URI`() {
        val uri = HomeKitSetupPayload.uri("323-41-143", "7OSX")
        assertTrue("wrong scheme: $uri", uri.startsWith("X-HM://"))
        // 7 for the scheme, 9 for the payload, 4 for the setup ID.
        assertEquals(20, uri.length)
        assertTrue("setup ID must terminate the URI: $uri", uri.endsWith("7OSX"))
    }

    @Test
    fun `dashed and undashed codes give the same payload`() {
        assertEquals(
            HomeKitSetupPayload.uri("32341143", "7OSX"),
            HomeKitSetupPayload.uri("323-41-143", "7OSX"),
        )
    }

    @Test
    fun `the largest valid setup code still fits its 27 bits`() {
        // 99999999 needs 27 bits exactly; a narrower field would corrupt the category above it.
        val uri = HomeKitSetupPayload.uri("99999999", "ABCD")
        assertEquals(20, uri.length)
        assertTrue(uri.endsWith("ABCD"))
    }

    @Test
    fun `a code of the wrong length is rejected at the source`() {
        // A short code would encode silently and produce a QR that scans to the wrong accessory.
        runCatching { HomeKitSetupPayload.uri("1234", "ABCD") }
            .fold({ throw AssertionError("expected a rejection") }, { assertTrue(it is IllegalArgumentException) })
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun syncPacket(rtp: Long, ntp: Long, next: Long): ByteArray {
        val p = ByteArray(20)
        p[0] = 0x90.toByte()               // marker set — the first sync of a stream
        p[1] = 0xD4.toByte()               // 0x54 | 0x80
        writeUint32(p, 4, rtp)
        writeUint32(p, 8, ntp ushr 32)
        writeUint32(p, 12, ntp and 0xFFFFFFFFL)
        writeUint32(p, 16, next)
        return p
    }

    private fun writeUint32(p: ByteArray, offset: Int, v: Long) {
        p[offset] = ((v shr 24) and 0xFF).toByte()
        p[offset + 1] = ((v shr 16) and 0xFF).toByte()
        p[offset + 2] = ((v shr 8) and 0xFF).toByte()
        p[offset + 3] = (v and 0xFF).toByte()
    }
}
