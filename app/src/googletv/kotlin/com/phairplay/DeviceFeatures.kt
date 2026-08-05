package com.phairplay

/**
 * Per-flavour hardware capabilities, resolved at compile time.
 *
 * Google TV builds keep Miracast: stock Android TV exposes a working Wi-Fi Direct stack, so the
 * `_wfd._tcp` advertisement in [com.phairplay.miracast.MiracastReceiver] can actually carry a
 * session through. See the firetv variant of this file for why Fire TV does not.
 */
object DeviceFeatures {
    const val MIRACAST_SUPPORTED = true
}
