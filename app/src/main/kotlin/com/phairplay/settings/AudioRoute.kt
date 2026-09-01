package com.phairplay.settings

/**
 * Where the Fire TV's audio is actually coming out right now.
 *
 * Pure data, deliberately: [com.phairplay.media.AudioRouteMonitor] does the Android-side detection,
 * and the shared model stays compilable on a plain JVM for the protocol tests.
 */
data class AudioRoute(
    /** Stable identity for the output. Not persisted as a setting; used for change detection. */
    val key: String,

    /** What to put in front of the user: "Echo-7NM", "Built-in speakers", "HDMI / TV". */
    val label: String,

    /**
     * True for an A2DP sink. The only route that gets an automatic compensation, and the only one
     * with a delay Android refuses to report — see [BLUETOOTH_COMPENSATION_MS].
     */
    val isBluetooth: Boolean,
) {
    /** Milliseconds the beat visuals are held back on this route, before the user's own trim. */
    val compensationMs: Int get() = if (isBluetooth) BLUETOOTH_COMPENSATION_MS else 0

    companion object {
        /** Stand-in before the first detection completes, and on a device with no AudioManager. */
        val UNKNOWN = AudioRoute(key = "unknown", label = "", isBluetooth = false)

        /**
         * How late a Bluetooth speaker is, in milliseconds. Applied to the *visuals*, automatically,
         * and never shown as a setting.
         *
         * 350ms, measured by ear on this hardware against an SBC speaker — which is the codec this
         * Fire TV negotiates (`codecConfigPriorities: SBC`) — and well above the 150–250ms usually
         * quoted for SBC, because the quoted figure covers the encoder and the link and stops there,
         * while the speaker's own jitter buffer is real and sits on the end of it.
         *
         * It is baked in rather than exposed because it is not a preference. It is a property of the
         * transport: a Bluetooth speaker is late by roughly this much whether or not anyone has an
         * opinion about it, and it goes away the moment the speaker does. Putting it in front of the
         * user as a number they have to discover and dial in would be handing them our homework.
         * The Audio delay setting sits on top of this and stays the user's, reading 0 by default
         * because 0 extra is genuinely what they have asked for.
         *
         * Android exposes no API for the real figure, which is why this is a constant rather than a
         * measurement. `AudioTrack.getTimestamp()` — which `AudioStreamServer.outputLatencyMs()`
         * already consults — covers the mixer and the HAL and stops at the point where audio leaves
         * the box. The encode, the radio link and the speaker's buffer are all past it, and they are
         * the majority of the delay.
         *
         * Delaying the *audio* instead would be the wrong direction: the sound is already late, and
         * holding it back further would only make it later. The visuals are the side that can move.
         */
        /**
         * Trimmed 350 -> 325 on 2026-09-01: at 350 the visuals read slightly LATE against the
         * sound on an Echo, both for the beat and for mirrored video. Link latency is not
         * measurable (`getTimestamp()` stops at the HAL), so this figure is only ever calibrated by
         * watching it, and it is speaker-specific — a different Bluetooth device will want a
         * different number. Nudge it here; every consumer reads this one constant.
         */
        const val BLUETOOTH_COMPENSATION_MS = 325
    }
}
