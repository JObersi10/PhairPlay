package com.phairplay.settings

/**
 * Where the Fire TV's audio is actually coming out right now.
 *
 * Pure data, deliberately: [com.phairplay.media.AudioRouteMonitor] does the Android-side detection,
 * and the shared model stays compilable on a plain JVM for the protocol tests.
 */
data class AudioRoute(
    /**
     * Stable identity used to look up a saved trim. Survives reboots and re-pairings, which the
     * transient integer id on an AudioDeviceInfo does not.
     */
    val key: String,

    /** What to put in front of the user: "Echo-7NM", "TV speakers", "HDMI". */
    val label: String,

    /**
     * True for an A2DP sink. Worth singling out because it is the only route with a delay Android
     * refuses to report — see [AvTrim.BLUETOOTH_SEED_BEAT_MS].
     */
    val isBluetooth: Boolean,
) {
    companion object {
        /** Stand-in before the first detection completes, and on a device with no AudioManager. */
        val UNKNOWN = AudioRoute(key = "unknown", label = "", isBluetooth = false)
    }
}

/**
 * The A/V-sync trim remembered for one output.
 *
 * Two numbers rather than one because they fix different things: [audioMs] holds the audio back to
 * meet the sender's timeline, [beatMs] holds the *visuals* back to meet audio that has already left
 * the building. A Bluetooth link needs the second one — the sound is genuinely late by the time it
 * reaches the speaker, and delaying the audio further would only make it later.
 */
data class AvTrim(val audioMs: Int = 0, val beatMs: Int = 0) {

    /** `key<US>audio<US>beat`, for the DataStore string set. Unit separator: no key can contain it. */
    fun serialize(key: String): String = "$key$SEP$audioMs$SEP$beatMs"

    companion object {
        private const val SEP = '\u001F'

        /**
         * What a Bluetooth speaker gets the first time it is seen, in milliseconds of *visual* delay.
         *
         * 350ms, measured by ear on this Fire TV against an SBC speaker — which is the codec it
         * negotiates (`codecConfigPriorities: SBC`) — and noticeably more than the 150-250ms that
         * gets quoted for SBC in the abstract, because the quoted figure is the encoder and the link
         * and stops there, while the speaker's own jitter buffer is real and is on the end of it.
         *
         * A starting point, not a claim: the picker still offers the full range, and whatever the
         * user lands on is remembered for that speaker. But the alternative default of zero is not
         * neutral. Zero is a confident claim that a Bluetooth speaker is as immediate as the TV's
         * own, which is wrong by most of a bar at 120 BPM and leaves the user tuning from scratch
         * with no starting point. Every other route seeds at zero, where zero is the right answer.
         *
         * Android exposes no API for the real figure, which is why this is seeded rather than
         * measured. AudioTrack.getTimestamp() covers the mixer and the HAL and stops at the point
         * where the audio leaves the box; the encode, the radio link and the speaker's buffer are
         * all past it, and they are the majority of the delay.
         *
         * Must stay a member of SettingsFragment.BEAT_DELAY_CHOICES, or the picker cannot show the
         * seeded value as the current selection.
         */
        const val BLUETOOTH_SEED_BEAT_MS = 350

        /** Parses one serialized entry back, or null if the string is from an older format / junk. */
        fun parse(entry: String): Pair<String, AvTrim>? {
            val parts = entry.split(SEP)
            if (parts.size != 3) return null
            val key = parts[0].takeIf { it.isNotEmpty() } ?: return null
            val audio = parts[1].toIntOrNull() ?: return null
            val beat = parts[2].toIntOrNull() ?: return null
            return key to AvTrim(audio, beat)
        }
    }
}
