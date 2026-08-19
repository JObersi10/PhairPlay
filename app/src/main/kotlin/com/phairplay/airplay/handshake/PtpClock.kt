package com.phairplay.airplay.handshake

import com.phairplay.util.Logger

/**
 * PtpClock — the offset/delay state machine behind PTP synchronisation.
 *
 * Deliberately free of sockets so the arithmetic can be tested exactly. The transport feeds it
 * four facts and it maintains the estimate:
 *
 *   t1  grandmaster's send time of Sync  (from the Sync, or its Follow_Up if two-step)
 *   t2  our local receive time of that Sync
 *   t3  our local send time of Delay_Req
 *   t4  grandmaster's receive time of that Delay_Req (from Delay_Resp)
 *
 *   meanPathDelay = ((t2 - t1) + (t4 - t3)) / 2
 *   offset        = meanPathDelay - (t2 - t1)
 *
 * Note the sign. The standard's `offsetFromMaster` is (t2 - t1) - meanPathDelay, i.e. slave minus
 * master. [offsetNanos] is the NEGATION of that — grandmaster minus ours — so that it matches
 * [AirPlayNtpClient.offsetNanos] and `local + offset == remote` reads the same way in both clocks.
 * Mixing the two conventions puts playback out by twice the offset, in the wrong direction.
 *
 * The symmetry assumption in that mean is why PTP wants hardware timestamping and why a busy or
 * asymmetric Wi-Fi link degrades it: the error in `offset` is half the asymmetry. Nothing here can
 * fix that, but [bestPathDelayNanos] exposes it so a caller can tell a good sync from a bad one
 * instead of trusting every estimate equally.
 *
 * Like [SenderClockModel], this tracks RATE as well as offset — for the same reason, only more so.
 * Grouped receivers must agree with each other over minutes, and crystal skew is what pulls them
 * apart.
 *
 * Not thread-safe; the timing thread owns it.
 */
class PtpClock {

    /** Grandmaster clock minus ours, nanoseconds. Meaningful once [synchronised]. */
    var offsetNanos: Long = 0L
        private set

    /** One-way delay estimate; also the quality signal for [offsetNanos]. */
    var pathDelayNanos: Long = 0L
        private set

    var bestPathDelayNanos: Long = Long.MAX_VALUE
        private set

    var synchronised: Boolean = false
        private set

    /** Identity of the grandmaster we are following, so foreign traffic can be ignored. */
    var grandmasterIdentity: Long? = null
        private set

    /** Offset AND rate, so a group holds together over minutes rather than seconds. */
    val model = SenderClockModel()

    private var pendingSyncSequence: Int? = null
    private var pendingSyncReceiveNanos: Long = 0L
    private var pendingSyncOriginNanos: Long? = null
    private var pendingSyncCorrection: Long = 0L

    private var delayReqSequence: Int? = null
    private var delayReqSendNanos: Long = 0L

    /** t2−t1 for the most recent Sync, held until a Delay_Resp lets us split it into delay+offset. */
    private var lastSyncDeltaNanos: Long? = null

    /**
     * Handles a Sync.
     *
     * A two-step Sync's own timestamp is a placeholder — the real one arrives in the Follow_Up —
     * so it is recorded as pending and nothing is computed yet.
     */
    fun onSync(header: PtpMessage.Header, localReceiveNanos: Long) {
        if (!accept(header)) return
        pendingSyncSequence = header.sequenceId
        pendingSyncReceiveNanos = localReceiveNanos
        pendingSyncCorrection = header.correctionNanos
        pendingSyncOriginNanos = if (header.twoStep) null else header.timestampNanos
        if (!header.twoStep) completeSync()
    }

    /** Handles a Follow_Up, which supplies the origin time for the Sync of the same sequence. */
    fun onFollowUp(header: PtpMessage.Header) {
        if (!accept(header)) return
        // Sequence matching matters: on a lossy link the Follow_Up for a Sync we never saw would
        // otherwise be paired with an unrelated receive time.
        if (header.sequenceId != pendingSyncSequence) {
            Logger.w("PTP: Follow_Up seq=${header.sequenceId} does not match pending Sync ${pendingSyncSequence}")
            return
        }
        pendingSyncOriginNanos = header.timestampNanos
        pendingSyncCorrection += header.correctionNanos
        completeSync()
    }

    private fun completeSync() {
        val origin = pendingSyncOriginNanos ?: return
        lastSyncDeltaNanos = pendingSyncReceiveNanos - origin - pendingSyncCorrection

        // Before any Delay_Resp there is no path-delay estimate, so the best available reading is
        // t2−t1 with delay assumed zero. That is biased late by the one-way delay, but it is a
        // usable starting point and is corrected as soon as the first Delay_Resp lands.
        if (!synchronised) {
            // Negated: `forward` is t2-t1, which is (ours - grandmaster). offsetNanos is documented
            // the other way round -- grandmaster minus ours -- to match AirPlayNtpClient, so that
            // local + offset == remote reads the same way in both clocks.
            offsetNanos = -lastSyncDeltaNanos!!
            synchronised = true
            Logger.i("PTP: first sync, offset≈${offsetNanos / 1000}us (path delay not yet measured)")
        } else {
            refine()
        }
    }

    /** Records that we sent a Delay_Req, so its response can be matched and timed. */
    fun onDelayReqSent(sequenceId: Int, localSendNanos: Long) {
        delayReqSequence = sequenceId
        delayReqSendNanos = localSendNanos
    }

    /**
     * Handles a Delay_Resp, completing one full measurement.
     *
     * @param ourClockIdentity our own clock identity — a Delay_Resp addressed to a different port
     *   belongs to another receiver in the group and must not be consumed.
     */
    fun onDelayResp(header: PtpMessage.Header, ourClockIdentity: Long, ourPortNumber: Int) {
        if (!accept(header)) return
        if (header.requestingClockIdentity != ourClockIdentity ||
            header.requestingPortNumber != ourPortNumber
        ) return
        if (header.sequenceId != delayReqSequence) return

        val t4 = header.timestampNanos ?: return
        val reverse = t4 - delayReqSendNanos - header.correctionNanos
        val forward = lastSyncDeltaNanos ?: return

        val delay = (forward + reverse) / 2
        // A negative one-way delay is physically impossible; it means the two directions were
        // timed against inconsistent clocks. Averaging it in would corrupt the estimate silently.
        if (delay < 0) {
            Logger.w("PTP: discarding measurement with negative path delay (${delay / 1000}us)")
            return
        }

        pathDelayNanos = delay
        if (delay < bestPathDelayNanos) bestPathDelayNanos = delay
        offsetNanos = delay - forward        // grandmaster minus ours; see completeSync()
        synchronised = true

        // Feed the rate model only clean measurements, for the same reason NTP does: a delayed
        // sample carries up to half its excess delay as offset error, which tilts a regression far
        // more than it moves a single estimate.
        if (delay <= bestPathDelayNanos * CLEAN_SAMPLE_FACTOR) {
            model.addSample(pendingSyncReceiveNanos, pendingSyncReceiveNanos + offsetNanos)
        }
    }

    private fun refine() {
        val forward = lastSyncDeltaNanos ?: return
        // Between Delay_Resps, hold the measured path delay and let Sync updates track the offset.
        offsetNanos = pathDelayNanos - forward
    }

    /** Converts a grandmaster timestamp into our local timebase — the direction playback needs. */
    fun localNanosAt(ptpNanos: Long): Long =
        if (model.confident) model.localNanosAt(ptpNanos) else ptpNanos - offsetNanos

    fun ptpNanosAt(localNanos: Long): Long =
        if (model.confident) model.senderNanosAt(localNanos) else localNanos + offsetNanos

    fun reset() {
        offsetNanos = 0
        pathDelayNanos = 0
        bestPathDelayNanos = Long.MAX_VALUE
        synchronised = false
        grandmasterIdentity = null
        pendingSyncSequence = null
        pendingSyncOriginNanos = null
        lastSyncDeltaNanos = null
        delayReqSequence = null
        model.reset()
    }

    /**
     * Locks onto the first grandmaster seen and ignores the rest.
     *
     * PTP traffic is multicast, so on a network with more than one PTP domain — or simply another
     * AirPlay group playing in the next room — messages from several masters arrive on the same
     * socket. Mixing two masters' timestamps yields an offset that belongs to neither.
     */
    private fun accept(header: PtpMessage.Header): Boolean {
        val current = grandmasterIdentity
        if (current == null) {
            grandmasterIdentity = header.clockIdentity
            Logger.i("PTP: following grandmaster ${header.clockIdentity.toString(16)}")
            return true
        }
        return current == header.clockIdentity
    }

    companion object {
        private const val CLEAN_SAMPLE_FACTOR = 2
    }
}
