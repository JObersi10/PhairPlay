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
     * Which tile each admitted sender owns, for as long as it is connected.
     *
     * A STABLE slot, not a position in [active]. Everything downstream — the mirror server, its
     * decoder, the SurfaceView it draws into — is addressed by this number, so it must not shift
     * when some other sender disconnects: renumbering would move a live mirror to a different tile
     * mid-stream and hand it a Surface belonging to someone else. The slot is chosen as the lowest
     * free index on admit and released only when that socket goes.
     */
    private val slots = HashMap<Socket, Int>()

    /**
     * How many senders may be served at once. Clamped to at least 1 — a capacity of 0 would
     * advertise the receiver over mDNS and then refuse every sender that answered.
     */
    @Volatile
    var capacity: Int = capacity.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    /**
     * What each admitted connection turned out to be, once its SETUP said so.
     *
     * SEPARATE FROM ADMISSION, AND IT HAS TO BE. [admit] runs at `accept()`, and nothing on the wire
     * at that point says whether the connection will become a mirror or an audio session — that
     * arrives later, in the SETUP plist. So the "how many of each" policy cannot be expressed where
     * the capacity limit lives; it is applied in [claimType] instead, once the answer exists.
     */
    private val kinds = HashMap<Socket, Kind>()

    enum class Kind { MIRROR, AUDIO }

    /**
     * Records what a connection is, and says whether the policy allows it.
     *
     * The policy, in one place:
     *
     *  - **Mirroring may be shared.** Several senders can be decoded to several tiles; that is what
     *    multi-screen is, and the ceiling on it is decoder throughput, not this.
     *  - **Audio may not.** One set of speakers, one stream through them. A second audio sender is
     *    refused rather than silently mixed or silently ignored.
     *  - **The two do not mix.** An audio session arriving while a mirror is running (or the
     *    reverse) is refused, because the mirror carries its own audio and the result is two
     *    senders fighting over the same output.
     *
     * Returns false when the caller must end this session. Idempotent for a connection that has
     * already claimed the same kind, because SETUP arrives more than once per session.
     */
    fun claimType(socket: Socket, kind: Kind): Boolean = synchronized(lock) {
        kinds.keys.removeAll { it.isClosed || it !in active }
        if (kinds[socket] == kind) return@synchronized true
        val others = kinds.filterKeys { it != socket }.values
        val allowed = when (kind) {
            Kind.MIRROR -> others.none { it == Kind.AUDIO }
            Kind.AUDIO -> others.isEmpty()
        }
        if (allowed) kinds[socket] = kind
        allowed
    }

    /** What a connection was classified as, or null if its SETUP has not said yet. */
    fun kindOf(socket: Socket): Kind? = synchronized(lock) { kinds[socket] }

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
        slots.keys.removeAll { it.isClosed }
        if (active.size >= capacity) return@synchronized false
        active.add(socket)
        slots[socket] = (0 until capacity).first { it !in slots.values }
        true
    }

    /** The tile this sender owns, or -1 if it holds no slot. */
    fun slotOf(socket: Socket): Int = synchronized(lock) { slots[socket] ?: -1 }

    /**
     * Gives up [socket]'s slot, but only if it still holds one.
     *
     * Each client runs on its own coroutine, so a newcomer can be admitted in the window between
     * one socket erroring and its cleanup running. Releasing unconditionally would hand that
     * newcomer's slot away and let an extra connection in behind it.
     */
    fun release(socket: Socket): Boolean = synchronized(lock) {
        slots.remove(socket)
        kinds.remove(socket)
        active.remove(socket)
    }

    /** Closes and forgets every session. Used by teardown and by the user's "disconnect" action. */
    fun closeAll() {
        val doomed = synchronized(lock) {
            val copy = active.toList()
            active.clear()
            slots.clear()
            kinds.clear()
            copy
        }
        doomed.forEach { runCatching { it.close() } }
        if (doomed.isNotEmpty()) Logger.i("Dropped ${doomed.size} RTSP client(s)")
    }
}
