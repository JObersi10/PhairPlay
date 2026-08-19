package com.phairplay.airplay.handshake

import com.phairplay.util.Logger

/**
 * MultiRoomGroup — the receiver's view of an AirPlay 2 group: who else is in it, and when the
 * shared timeline says to play.
 *
 * These are the two RTSP methods that turn independent receivers into a synchronised group, and
 * until now PhairPlay acknowledged both and did nothing with them:
 *
 *  - **SETPEERS / SETPEERSX** — the sender's list of every device in the group. Its real purpose
 *    is PTP: one of these peers is the grandmaster, and every receiver must follow the SAME one or
 *    the group has no common timebase. SETPEERSX additionally carries per-peer clock identities
 *    and addresses.
 *
 *  - **SETRATEANCHORTIME** — the shared playback anchor: "RTP timestamp X corresponds to network
 *    time T, and the rate is R". Every receiver applies the same anchor, so two devices that agree
 *    on the clock necessarily agree on which sample to play now. This is what actually makes
 *    multi-room sync work; the clock protocol only makes it possible.
 *
 * The anchor arithmetic is the part worth being careful with. Given an anchor (rtpTime, networkTime)
 * and a sample rate, the local deadline for any RTP timestamp is:
 *
 *     networkTimeFor(rtp) = anchorNetworkTime + (rtp - anchorRtpTime) / sampleRate
 *     localDeadline       = clock.localNanosAt(networkTimeFor(rtp))
 *
 * A paused stream has rate 0, and an anchor with rate 0 must NOT be used to schedule anything —
 * dividing through it or extrapolating from it produces a deadline in the distant past and a burst
 * of catch-up audio on resume.
 */
class MultiRoomGroup {

    /** One member of the group as named by SETPEERS/SETPEERSX. */
    data class Peer(
        val address: String,
        val clockIdentity: Long? = null,
        val clockPort: Int? = null,
        val supportsClockPortMatching: Boolean = false,
    )

    /**
     * The shared playback anchor.
     *
     * @param rtpTime the RTP timestamp this anchor pins.
     * @param networkTimeNanos the network (PTP/NTP) time that RTP timestamp corresponds to.
     * @param rate 0 while paused, 1 for normal playback.
     */
    data class Anchor(
        val rtpTime: Long,
        val networkTimeNanos: Long,
        val rate: Double,
    ) {
        val playing: Boolean get() = rate != 0.0
    }

    var peers: List<Peer> = emptyList()
        private set

    var anchor: Anchor? = null
        private set

    /** True once we know both who is in the group and when to play. */
    val ready: Boolean get() = anchor?.playing == true

    fun setPeers(newPeers: List<Peer>) {
        peers = newPeers
        Logger.i(
            "Group peers (${newPeers.size}): " +
                newPeers.joinToString { p ->
                    p.address + (p.clockIdentity?.let { "/${it.toString(16)}" } ?: "")
                },
        )
    }

    fun setAnchor(newAnchor: Anchor) {
        anchor = newAnchor
        Logger.i(
            "Playback anchor: rtp=${newAnchor.rtpTime} netTime=${newAnchor.networkTimeNanos} " +
                "rate=${newAnchor.rate}" + if (!newAnchor.playing) " (paused)" else "",
        )
    }

    fun clear() {
        peers = emptyList()
        anchor = null
    }

    /**
     * The network time at which [rtpTime] should play, or null when there is no usable anchor.
     *
     * Returns null rather than a guess when paused: an anchor with rate 0 pins a moment that is no
     * longer advancing, so extrapolating from it yields a deadline further in the past with every
     * passing second.
     */
    fun networkTimeForRtp(rtpTime: Long, sampleRate: Int): Long? {
        val a = anchor ?: return null
        if (!a.playing || sampleRate <= 0) return null
        val deltaSamples = rtpTime - a.rtpTime
        // Nanoseconds first, then divide: doing it the other way truncates to whole seconds.
        return a.networkTimeNanos + (deltaSamples * 1_000_000_000L) / sampleRate
    }

    /**
     * The LOCAL deadline for [rtpTime] — what a scheduler actually needs.
     *
     * Goes through the clock rather than assuming a fixed offset, so a group whose members have
     * different crystal skews still converges on the same wall-clock instant.
     */
    fun localDeadlineNanos(rtpTime: Long, sampleRate: Int, clock: PtpClock): Long? =
        networkTimeForRtp(rtpTime, sampleRate)?.let { clock.localNanosAt(it) }

    companion object {

        /**
         * Parses the SETPEERS body: a plist array of IP address strings.
         *
         * The plain form carries addresses only — the sender expects the receiver to already know
         * the clock identities from PTP Announce messages on the wire.
         */
        fun parsePeers(value: Any?): List<Peer> = when (value) {
            is List<*> -> value.mapNotNull { entry ->
                when (entry) {
                    is String -> Peer(address = entry)
                    // SETPEERSX entries are dictionaries; fall through to the richer parser so one
                    // helper handles both bodies and callers do not have to sniff the shape.
                    is Map<*, *> -> parsePeerDict(entry)
                    else -> null
                }
            }
            else -> emptyList()
        }

        private fun parsePeerDict(dict: Map<*, *>): Peer? {
            // SETPEERSX names the address list "Addresses"; older captures use "address".
            val addresses = dict["Addresses"] as? List<*>
            val address = (addresses?.firstOrNull() as? String)
                ?: dict["address"] as? String
                ?: return null
            val clockId = when (val c = dict["ClockID"]) {
                is Number -> c.toLong()
                is String -> c.toLongOrNull(16) ?: c.toLongOrNull()
                else -> null
            }
            val clockPort = (dict["ClockPorts"] as? Map<*, *>)?.values?.firstOrNull()
                ?.let { (it as? Number)?.toInt() }
            return Peer(
                address = address,
                clockIdentity = clockId,
                clockPort = clockPort,
                supportsClockPortMatching = dict["SupportsClockPortMatchingOverride"] == true,
            )
        }

        /**
         * Parses SETRATEANCHORTIME.
         *
         * `rate` is the field that decides whether this is a play or a pause, and it is the one a
         * naive implementation ignores — leaving a paused group scheduling audio against a frozen
         * anchor. `networkTimeSecs`/`networkTimeFrac` are a seconds + 2^-64 fraction pair, the same
         * shape NTP uses, and the fraction is NOT milliseconds.
         */
        fun parseAnchor(dict: Map<*, *>): Anchor? {
            val rtpTime = (dict["rtpTime"] as? Number)?.toLong() ?: return null
            val secs = (dict["networkTimeSecs"] as? Number)?.toLong() ?: return null
            val frac = (dict["networkTimeFrac"] as? Number)?.toLong() ?: 0L
            val rate = (dict["rate"] as? Number)?.toDouble() ?: 1.0

            // frac is a fraction of a second scaled by 2^64. Shifting right by 32 first keeps the
            // multiply inside Long; doing it in one step overflows and yields a garbage nanosecond
            // component that is worse than dropping the fraction entirely.
            val fracNanos = ((frac ushr 32) * 1_000_000_000L) ushr 32
            return Anchor(
                rtpTime = rtpTime,
                networkTimeNanos = secs * 1_000_000_000L + fracNanos,
                rate = rate,
            )
        }
    }
}
