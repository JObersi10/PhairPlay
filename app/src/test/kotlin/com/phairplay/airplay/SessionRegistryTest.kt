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
    private class FakeSocket : Socket() {
        private var closed = false
        override fun isClosed(): Boolean = closed
        override fun close() { closed = true }
    }

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
}
