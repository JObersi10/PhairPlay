package com.phairplay.airplay.handshake

/**
 * The AirPlay source version this receiver advertises — in mDNS (`srcvers`), in `GET /info`
 * (`srcvers` and `sourceVersion`), and nowhere else. One constant, because it is not cosmetic:
 * **iOS decides whether to grant the receiver remote-control authority based on this number.**
 *
 * A sender that trusts the receiver puts `DACP-ID` and `Active-Remote` on its RTSP requests, which
 * is the address [com.phairplay.airplay.DacpClient] needs to send play/pause/skip back. Above
 * roughly version 350 — the AirPlay 2 multi-room generation — iOS stops sending those headers
 * entirely, and no amount of correctness further along the session brings them back. That is why
 * the TV remote's skip button does nothing for an iPhone while it still works for TikTok and the
 * Mac's Music app, both of which negotiate as classic senders.
 *
 * The observation is not ours: it was reported against shairport-sync (issue #2014), where
 * dropping the advertised version below the threshold made an iOS 18 phone start sending
 * `DACP-ID`/`Active-Remote` again. Their blocker was that the phone then insists on an NTP timing
 * stream, which shairport-sync does not implement. We do —
 * [com.phairplay.airplay.handshake.AirPlayNtpClient] has been running since mirroring was fixed —
 * so the path that was closed to them may be open to us.
 *
 * The trade is real and not yet measured: a lower version may cost AirPlay 2 features that depend
 * on being the newer generation. Mirroring is the thing to watch. Restore [APPLE_TV_CURRENT] to
 * undo the experiment in one edit.
 */
object AirPlayVersion {

    /** A current Apple TV. Above the threshold: no `DACP-ID`, so no remote control from an iPhone. */
    const val APPLE_TV_CURRENT = "377.40.00"

    /** Below the threshold, where iOS still grants DACP reverse control. */
    const val DACP_CAPABLE = "350.0"

    /**
     * What `_raop._tcp` and the RTSP layer advertise.
     *
     * Stays low, because this is the service that carries audio and it is the RTSP session where
     * iOS decides whether to send `DACP-ID`/`Active-Remote`. Remote control is the thing the user
     * touches; losing it to gain a feature we cannot yet test would be a bad trade.
     */
    const val ADVERTISED = DACP_CAPABLE

    /**
     * What `_airplay._tcp` advertises.
     *
     * The two services carry SEPARATE TXT records, so they are not obliged to agree — and the
     * question each answers is different. `_raop` is where the DACP decision is made; `_airplay` is
     * where the sender decides which generation of features to offer.
     *
     * WHAT IS ALREADY KNOWN, so this is not re-run blind. `_raop` has been pinned to 350.0 for a
     * while, which is exactly the shairport-sync #2014 workaround, and the device log still says
     * "GET /info WITHOUT DACP-ID — sender withheld remote authority (srcvers 350.0)" against
     * iPhone/iPad on iOS 26.1 and 27. That number in the log is OUR advertised value, not the
     * sender's, so it confirms the workaround is applied and ineffective: whatever hole iOS 18 left
     * open is closed on current iOS.
     *
     * The one variable never tested is this constant. While `_raop` claimed 350.0, `_airplay` was
     * still claiming a current Apple TV, so a sender reading both saw a receiver announcing the
     * modern generation on one service and a legacy one on the other. Agreeing on 350.0 removes
     * that inconsistency. It is a genuine experiment with a real mechanism, not a guess at a wire
     * format — the values are ours to set and their meaning is documented.
     *
     * The cost if it fails is AirPlay 2 features gated on the newer generation: multi-room grouping
     * first, possibly buffered audio. Mirroring is the thing to watch, since it is the feature most
     * likely to regress and the easiest to notice. Set this back to [APPLE_TV_CURRENT] to undo.
     */
    const val ADVERTISED_AIRPLAY = DACP_CAPABLE
}
