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

    /** What we actually advertise. */
    const val ADVERTISED = DACP_CAPABLE
}
