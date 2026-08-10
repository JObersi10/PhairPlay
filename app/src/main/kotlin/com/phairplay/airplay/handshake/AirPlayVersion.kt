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
     * where the sender decides which generation of features to offer, including whether to offer
     * multi-room grouping at all. Pinning both to 350.0 to protect remote control also, silently,
     * declined every AirPlay 2 feature gated on being the newer generation.
     *
     * Splitting them is the experiment: claim the modern generation where capability is negotiated,
     * keep the older one where reverse control is granted. It may simply not work — a sender that
     * cross-checks the two will see an inconsistency, and the honest expectation is that this
     * either unlocks grouping or changes nothing. It cannot cost DACP, which is decided on the
     * other service.
     *
     * If grouping still is not offered, the next thing to suspect is the feature bits rather than
     * this number.
     */
    const val ADVERTISED_AIRPLAY = APPLE_TV_CURRENT
}
