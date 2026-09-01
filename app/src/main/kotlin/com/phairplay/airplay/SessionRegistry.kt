package com.phairplay.airplay

import com.phairplay.util.Logger
import java.net.Socket

/**
 * The set of RTSP control connections the receiver is currently serving, bounded by [capacity].
 *
 * This replaces the single `activeClient` field that enforced one-sender-at-a-time. At capacity 1
 * it behaves identically — one socket in, everyone else refused immediately — so it is a drop-in
 * for the old policy. Raising the capacity is what multi-screen needs, and is deliberately NOT
 * something the receiver does on its own: see [com.phairplay.media.DecoderCapacity] for the
 * hardware ceiling and `docs/MULTI_SCREEN.md` for the per-connection state that must be untangled
 * in `RtspHandler` before a second sender can safely be admitted.
 *
 * The one behaviour that must never come back is a connection left hanging. A sender that is not
 * admitted has to be told so on the spot; the accept loop stays responsive and [admit] never
 * blocks. (A queued socket adopted minutes stale is the bug this whole design is shaped around.)
 *
 * Closed sockets are pruned on every [admit], because a sender that vanishes without a clean
 * teardown would otherwise hold a slot forever and lock the receiver out of its own retry.
 */
class SessionRegistry(capacity: Int = 1) {

    private val lock = Any()
    /** Insertion-ordered: the first socket admitted is the audio primary. */
    private val active = LinkedHashSet<Socket>()

    /**
     * How many senders may be served at once. Clamped to at least 1 — a capacity of 0 would
     * advertise the receiver over mDNS and then refuse every sender that answered.
     */
    @Volatile
    var capacity: Int = capacity.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /** Sockets currently being served, in the order they were admitted. */
    fun snapshot(): List<Socket> = synchronized(lock) { active.toList() }

    fun size(): Int = synchronized(lock) { active.size }

    fun isEmpty(): Boolean = size() == 0

    /**
     * The session that owns shared, un-duplicable resources — chiefly audio. N senders can be
     * decoded to N video tiles, but only one of them can be playing through the speakers.
     */
    fun primary(): Socket? = synchronized(lock) { active.firstOrNull() }

    fun isPrimary(socket: Socket): Boolean = primary() === socket

    fun contains(socket: Socket): Boolean = synchronized(lock) { active.contains(socket) }

    /**
     * Takes a slot for [socket] if one is free.
     *
     * @return true when the caller may serve this socket; false when it must be refused *now*.
     */
    fun admit(socket: Socket): Boolean = synchronized(lock) {
        // A sender that dropped without a TEARDOWN leaves a closed socket behind. Reclaiming those
        // here — rather than only on the disconnect path — is what lets its own retry get in.
        active.removeAll { it.isClosed }
        if (active.size >= capacity) return@synchronized false
        active.add(socket)
        true
    }

    /**
     * Gives up [socket]'s slot, but only if it still holds one.
     *
     * Each client runs on its own coroutine, so a newcomer can be admitted in the window between
     * one socket erroring and its cleanup running. Releasing unconditionally would hand that
     * newcomer's slot away and let an extra connection in behind it.
     */
    fun release(socket: Socket): Boolean = synchronized(lock) { active.remove(socket) }

    /** Closes and forgets every session. Used by teardown and by the user's "disconnect" action. */
    fun closeAll() {
        val doomed = synchronized(lock) {
            val copy = active.toList()
            active.clear()
            copy
        }
        doomed.forEach { runCatching { it.close() } }
        if (doomed.isNotEmpty()) Logger.i("Dropped ${doomed.size} RTSP client(s)")
    }
}
