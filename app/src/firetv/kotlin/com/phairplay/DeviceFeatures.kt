package com.phairplay

/**
 * Per-flavour hardware capabilities, resolved at compile time.
 *
 * Fire TV builds: Miracast is off. Fire OS ships Wi-Fi Direct behind Amazon's own display stack,
 * and [com.phairplay.miracast.MiracastReceiver] can advertise the `_wfd._tcp` P2P service but never
 * completes a WFD session — senders find the receiver and then time out. Advertising a protocol the
 * device cannot finish is worse than not offering it: it also costs a runtime ACCESS_FINE_LOCATION
 * prompt on API < 33 for a feature that was never going to work.
 *
 * Google TV keeps it — stock Android TV Wi-Fi Direct is functional there.
 */
object DeviceFeatures {
    const val MIRACAST_SUPPORTED = false
}
