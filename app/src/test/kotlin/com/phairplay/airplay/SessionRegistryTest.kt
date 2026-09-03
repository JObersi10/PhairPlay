package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * The registry is what enforces "how many senders at once", so the behaviour that matters is who
 * gets refused and when a slot comes back.
 */
class SessionRegistryTest {

    /** A socket whose only interesting property is whether it has been closed. */
    private class FakeSocket(private val addr: java.net.InetAddress? = null) : Socket() {
        private var closed = false
        override fun isClosed(): Boolean = closed
        override fun close() { closed = true }
        override fun getInetAddress(): java.net.InetAddress? = addr
    }

    private fun addr(s: String): java.net.InetAddress = java.net.InetAddress.getByName(s)

    @Test
    fun `at capacity one, the second sender is refused`() {
        val registry = SessionRegistry(capacity = 1)
        val first = FakeSocket()
        val second = FakeSocket()

        assertTrue(registry.admit(first))
        assertFalse("second sender must be refused, not queued", registry.admit(second))
        assertEquals(1, registry.size())
    }

    @Test
    fun `releasing frees the slot for the next sender`() {
        val registry = SessionRegistry(capacity = 1)
        val first = FakeSocket()
        val second = FakeSocket()

        registry.admit(first)
        assertTrue(registry.release(first))
        assertTrue(registry.admit(second))
    }

    @Test
    fun `releasing a socket that no longer holds a slot does not free someone else's`() {
        // Each client runs on its own coroutine: a newcomer can be admitted in the window between
        // one socket erroring and its cleanup running. Cleanup must not evict the newcomer.
        val registry = SessionRegistry(capacity = 1)
        val stale = FakeSocket()
        val newcomer = FakeSocket()

        registry.admit(stale)
        registry.release(stale)
        registry.admit(newcomer)

        assertFalse(registry.release(stale))
        assertEquals(1, registry.size())
        assertSame(newcomer, registry.primary())
    }

    @Test
    fun `a sender that vanished without a teardown does not hold its slot forever`() {
        val registry = SessionRegistry(capacity = 1)
        val gone = FakeSocket()
        registry.admit(gone)
        gone.close()

        assertTrue("a closed socket must be reclaimed so the sender's own retry gets in",
                   registry.admit(FakeSocket()))
        assertEquals(1, registry.size())
    }

    @Test
    fun `capacity above one admits that many and no more`() {
        val registry = SessionRegistry(capacity = 3)
        val admitted = (1..3).map { FakeSocket() }
        admitted.forEach { assertTrue(registry.admit(it)) }

        assertFalse(registry.admit(FakeSocket()))
        assertEquals(3, registry.size())
    }

    @Test
    fun `the first sender admitted is the primary`() {
        val registry = SessionRegistry(capacity = 2)
        val first = FakeSocket()
        val second = FakeSocket()
        registry.admit(first)
        registry.admit(second)

        assertSame(first, registry.primary())
        assertTrue(registry.isPrimary(first))
        assertFalse(registry.isPrimary(second))
    }

    @Test
    fun `the primary role moves on when the primary leaves`() {
        val registry = SessionRegistry(capacity = 2)
        val first = FakeSocket()
        val second = FakeSocket()
        registry.admit(first)
        registry.admit(second)

        registry.release(first)
        assertSame(second, registry.primary())
    }

    @Test
    fun `capacity zero is clamped so the receiver cannot advertise and refuse everyone`() {
        val registry = SessionRegistry(capacity = 0)
        assertEquals(1, registry.capacity)
        assertTrue(registry.admit(FakeSocket()))
    }

    @Test
    fun `closeAll closes every session and empties the registry`() {
        val registry = SessionRegistry(capacity = 2)
        val first = FakeSocket()
        val second = FakeSocket()
        registry.admit(first)
        registry.admit(second)

        registry.closeAll()

        assertTrue(first.isClosed())
        assertTrue(second.isClosed())
        assertTrue(registry.isEmpty())
        assertNull(registry.primary())
    }

    @Test
    fun `each admitted sender gets its own slot`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket(); val c = FakeSocket()
        registry.admit(a); registry.admit(b); registry.admit(c)

        val assigned = listOf(registry.slotOf(a), registry.slotOf(b), registry.slotOf(c))
        assertEquals("slots must be distinct", 3, assigned.toSet().size)
        assertTrue(assigned.all { it in 0..2 })
    }

    @Test
    fun `a slot survives another sender leaving`() {
        // The slot addresses a tile, a decoder and a Surface. Renumbering on someone else's
        // disconnect would move a live mirror onto a different tile mid-stream.
        val registry = SessionRegistry(capacity = 3)
        val first = FakeSocket(); val second = FakeSocket()
        registry.admit(first); registry.admit(second)
        val secondSlot = registry.slotOf(second)

        registry.release(first)

        assertEquals(secondSlot, registry.slotOf(second))
    }

    @Test
    fun `a freed slot is reused by the next sender`() {
        val registry = SessionRegistry(capacity = 2)
        val first = FakeSocket(); val second = FakeSocket()
        registry.admit(first); registry.admit(second)
        val freed = registry.slotOf(first)
        registry.release(first)

        val third = FakeSocket()
        assertTrue(registry.admit(third))
        assertEquals(freed, registry.slotOf(third))
    }

    @Test
    fun `a socket holding no slot reports -1`() {
        val registry = SessionRegistry(capacity = 2)
        assertEquals(-1, registry.slotOf(FakeSocket()))
    }

    // ── The audio/mirror policy ────────────────────────────────────────────────────────────────
    //
    // The rule is: mirroring may be shared, audio may not, and the two do not mix. It lives here
    // rather than in admit() because admission happens at accept(), before anything on the wire says
    // which of the two a connection will become.

    @Test
    fun `several mirrors may share the receiver`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a); registry.admit(b)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.MIRROR))
        assertTrue(registry.claimType(b, SessionRegistry.Kind.MIRROR))
    }

    @Test
    fun `a second audio session is refused`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a); registry.admit(b)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.AUDIO))
        assertFalse(registry.claimType(b, SessionRegistry.Kind.AUDIO))
    }

    /** A mirror carries its own audio; a separate audio sender would fight it for the speakers. */
    @Test
    fun `audio is refused while a mirror is running`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a); registry.admit(b)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.MIRROR))
        assertFalse(registry.claimType(b, SessionRegistry.Kind.AUDIO))
    }

    @Test
    fun `a mirror is refused while audio is playing`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a); registry.admit(b)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.AUDIO))
        assertFalse(registry.claimType(b, SessionRegistry.Kind.MIRROR))
    }

    /** SETUP arrives more than once per session, so re-claiming the same kind must not refuse. */
    @Test
    fun `claiming the same kind twice is idempotent`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket()
        registry.admit(a)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.AUDIO))
        assertTrue(registry.claimType(a, SessionRegistry.Kind.AUDIO))
    }

    /** Releasing a session must free its kind, or the next sender inherits the old policy. */
    @Test
    fun `releasing a session frees its kind`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.AUDIO))
        registry.release(a)
        registry.admit(b)
        assertTrue(registry.claimType(b, SessionRegistry.Kind.AUDIO))
    }

    @Test
    fun `a closed session stops blocking a new one`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a)
        assertTrue(registry.claimType(a, SessionRegistry.Kind.AUDIO))
        a.close()
        registry.admit(b)
        assertTrue(registry.claimType(b, SessionRegistry.Kind.AUDIO))
    }

    /**
     * The count decides whether a disconnect is a full teardown or "others are still connected",
     * and only the full teardown tells the UI the session ended. A socket that died without
     * reaching release() therefore left Now Playing on screen over a dead session, behind a
     * completely clean-looking RTSP log.
     */
    @Test
    fun `a closed session is not counted as still connected`() {
        val registry = SessionRegistry(capacity = 3)
        val a = FakeSocket(); val b = FakeSocket()
        registry.admit(a)
        registry.admit(b)
        assertEquals(2, registry.size())
        // Died without a release() -- the socket-close path never ran.
        a.close()
        assertEquals(1, registry.size())
        registry.release(b)
        assertEquals(0, registry.size())
        assertTrue(registry.isEmpty())
    }

    /**
     * One device is allowed to change what it is doing. A Mac playing audio that then starts
     * screen mirroring opens a SECOND control connection while the first is still up, and keying
     * the policy on sockets made the mirror claim collide with its own device's audio — refused,
     * socket closed, mirroring dead the instant it started.
     */
    @Test
    fun `the same sender may add mirroring to its own audio session`() {
        val registry = SessionRegistry(capacity = 3)
        val mac = addr("192.168.1.50")
        val audio = FakeSocket(mac); val mirror = FakeSocket(mac)
        registry.admit(audio)
        registry.admit(mirror)
        assertTrue(registry.claimType(audio, SessionRegistry.Kind.AUDIO))
        assertTrue(registry.claimType(mirror, SessionRegistry.Kind.MIRROR))
    }

    /** ...but a DIFFERENT device still may not, which is the rule the exemption must not weaken. */
    @Test
    fun `a different sender still cannot mirror against someone else's audio`() {
        val registry = SessionRegistry(capacity = 3)
        val audio = FakeSocket(addr("192.168.1.50"))
        val other = FakeSocket(addr("192.168.1.51"))
        registry.admit(audio)
        registry.admit(other)
        assertTrue(registry.claimType(audio, SessionRegistry.Kind.AUDIO))
        assertFalse(registry.claimType(other, SessionRegistry.Kind.MIRROR))
    }

    @Test
    fun `a different sender still cannot take audio from an existing audio session`() {
        val registry = SessionRegistry(capacity = 3)
        val first = FakeSocket(addr("192.168.1.50"))
        val second = FakeSocket(addr("192.168.1.51"))
        registry.admit(first)
        registry.admit(second)
        assertTrue(registry.claimType(first, SessionRegistry.Kind.AUDIO))
        assertFalse(registry.claimType(second, SessionRegistry.Kind.AUDIO))
    }
}
