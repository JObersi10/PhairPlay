package com.phairplay.settings

import com.phairplay.DeviceFeatures
import com.phairplay.media.VolumeControlMode

/**
 * AppSettings — Immutable data model for all user-configurable PhairPlay settings.
 *
 * WHY: Centralizing all settings in one data class gives a single source of truth.
 * Any component that needs a setting reads from here. Any component that changes a
 * setting creates a new copy via [copy]. This makes settings changes explicit and
 * easy to test.
 *
 * HOW: Settings are persisted via [SettingsRepository]. Get the current settings
 * from [SettingsRepository.settingsFlow] and update them via [SettingsRepository.update].
 *
 * Example:
 *   // Read settings
 *   val settings = settingsRepository.settingsFlow.first()
 *   if (settings.airPlayEnabled) { ... }
 *
 *   // Change a setting
 *   settingsRepository.update { it.copy(displayName = "My TV") }
 */
data class AppSettings(

    // ─── Display ───────────────────────────────────────────────────────────
    /**
     * The name shown in sender pickers (AirPlay menu on Mac, Cast picker in Chrome, etc.).
     * If empty, the Android device name is used as a fallback.
     * Validated: max 63 characters, must not be blank after trimming.
     */
    val displayName: String = "",

    // ─── Protocols ─────────────────────────────────────────────────────────
    /**
     * Whether the AirPlay 2 receiver is enabled.
     * When false: mDNS advertisement is stopped, RTSP port 7000 is not opened.
     */
    val airPlayEnabled: Boolean = true,

    /**
     * Whether the Miracast (Wi-Fi Display) receiver is enabled.
     * When false: Wi-Fi P2P service advertisement is stopped.
     */
    val miracastEnabled: Boolean = true,


    /**
     * Whether the DLNA/UPnP MediaRenderer is enabled.
     * When false: SSDP advertisement and HTTP server are stopped.
     */
    val dlnaEnabled: Boolean = true,

    /**
     * Whether the HomeKit accessory is advertised.
     *
     * Off by default: pairing publishes a long-lived accessory identity onto the network and adds
     * PhairPlay to the user's Home, which is a bigger step than turning on a streaming receiver
     * and should be a deliberate one.
     */
    val homeKitEnabled: Boolean = false,

    // ─── AirPlay specific ──────────────────────────────────────────────────
    /**
     * Whether AirPlay connections require PIN authentication.
     * When true: the user must confirm a 4-digit PIN shown on screen.
     * When false (default): any nearby Mac can connect without confirmation.
     */
    val airPlayPinAuthEnabled: Boolean = false,

    // ─── Service behavior ──────────────────────────────────────────────────
    /**
     * Whether PhairPlayService starts automatically on device boot.
     * Requires the RECEIVE_BOOT_COMPLETED permission to be effective.
     */
    val startOnBoot: Boolean = false,

    // ─── Developer / Debug ─────────────────────────────────────────────────
    /**
     * Overlays a debug HUD on the streaming screen showing:
     * - Current frames per second
     * - Estimated A/V latency (ms)
     * - Active protocol name
     * Only useful for development and testing.
     */
    val showDebugOverlay: Boolean = false,

    // ─── Video ─────────────────────────────────────────────────────────────
    /**
     * When true, advertise a higher mirroring resolution (1440p) in the AirPlay `/info`
     * `displays` record so macOS renders/encodes the mirror at 2560×1440 instead of 1920×1080.
     * The TV surface is 1080p, so frames are downscaled (sharper text via supersampling) at
     * the cost of more decode work — heavier on low-end SoCs.
     */
    val forceHighResolution: Boolean = false,

    /**
     * When true, accept the mirroring audio stream (type 96, AAC-ELD). EXPERIMENTAL: macOS uses
     * realtime audio clock-sync (RTCP) that isn't fully implemented yet, which can make macOS tear
     * the whole mirror session down after a couple of seconds — so this defaults OFF to keep video
     * mirroring rock-solid. Turn on to experiment with audio.
     */
    val mirrorAudioEnabled: Boolean = true,

    // ─── Now Playing screen ────────────────────────────────────────────────
    /**
     * When true, the audio Now Playing screen dims to a drifting, breathing card on a black
     * background after [screensaverTimeoutMin] minutes without remote input or a track change.
     * Protects the panel from burn-in during long albums and looks intentional while it does it.
     */
    val screensaverEnabled: Boolean = true,

    /**
     * Minutes of inactivity before the Now Playing screensaver starts. Clamped to at least 1.
     */
    val screensaverTimeoutMin: Int = 15,

    // ─── Volume ────────────────────────────────────────────────────────────
    /**
     * Whether the sender's volume slider drives the real output level, or only the software gain on
     * the decoded PCM. See [VolumeControlMode] for what each mode does.
     *
     * Defaults to [VolumeControlMode.OFF] — software gain always works, whereas driving the device
     * volume proved unreliable on Fire OS even with MODIFY_AUDIO_SETTINGS granted and the route
     * reporting itself as eligible (Bluetooth, `volumeFixed=false`). The other modes remain
     * available for routes where the hardware does follow.
     */
    val senderVolumeMode: VolumeControlMode = VolumeControlMode.OFF,

    /** What the Back button does. See [BackAction]. */
    val backAction: BackAction = BackAction.STOP_STREAM,

    /**
     * Extra delay applied to AirPlay audio, in milliseconds, on top of the latency the sender asks
     * for in its SETUP `latencyMin`.
     *
     * Senders stream ahead of their own playback position and expect the receiver to hold each
     * frame back. Honouring latencyMin alone still left audio running ahead of the phone's lyric
     * timeline on this hardware, and the true offset depends on the sender, the codec and the
     * output path (Bluetooth adds its own). Rather than bake in a guessed constant, expose it.
     */
    val audioDelayMs: Int = 0,

    /**
     * AudioTrack hardware buffer, in milliseconds. Exposed because the right value is a property of
     * the output, not of the app.
     *
     * This buffer only has to survive a scheduling hiccup between writes -- network jitter is the
     * packet queue's job, and the two are charged against the SAME latency budget the sender asks
     * for. So raising this does not add headroom for free: every millisecond here is a millisecond
     * the queue does not get, and the total is audible delay either way.
     *
     * Low (40-60) suits HDMI out on a quiet network: least delay, but an underrun becomes a click.
     * High (200+) rides out a busy Wi-Fi or a Bluetooth speaker at the cost of lip-sync. 100 is the
     * default because it measured as the point where glitches stopped on HDMI.
     */
    val audioBufferMs: Int = DEFAULT_AUDIO_BUFFER_MS,

    /**
     * Whether leaving the app during a video stream enters picture-in-picture instead of simply
     * backgrounding. Only affects mirroring — audio-only sessions have no video to shrink.
     */
    val pipEnabled: Boolean = true,

    /**
     * Whether the HomeKit / iPhone remote may drive the Fire TV at all.
     *
     * On by default. Turning it off stops PhairPlay acting on remote key presses entirely — the
     * accessory stays paired and the power tile keeps working, but the D-pad does nothing. Worth
     * having because on Fire OS the D-pad cannot reliably drive other apps, and a remote that
     * half-works is worse than one that is honestly switched off.
     */
    /**
     * OFF by default. The remote works properly inside PhairPlay, but outside it Fire TV refuses
     * every accessibility focus action, so navigation is faked with a drawn cursor and synthetic
     * swipes. That is unreliable enough on the launcher and in apps like Netflix that shipping it
     * on by default makes the whole app look broken. Users who want it can turn it on and are told
     * plainly what to expect.
     */
    val remoteEnabled: Boolean = false,

    /**
     * Edgeless "projector" look for the now-playing screen: true black instead of the lifted TV
     * base, the beat visual pulled to the middle, and a vignette that dissolves it into black on
     * every side. On a projector black is simply no light, so the picture appears to have no
     * boundary at all. Off by default — on a normal TV it just looks dimmer.
     */
    val backdropTheme: BackdropTheme = BackdropTheme.DYNAMIC,
    /**
     * Look album art up online when the sender did not supply any (DLNA mostly).
     *
     * Off by default deliberately. It is the only feature that makes the receiver contact a third
     * party, and sending the titles someone plays to an outside service is their decision, not a
     * sensible default. Uses MusicBrainz + the Cover Art Archive — see CoverArtFinder for why those.
     */
    val artworkLookup: Boolean = false,

    /** What to do when a stream ends. */
    val streamEndAction: StreamEndAction = StreamEndAction.STAY_IN_APP,

    /** Beat Pulse strength: 0 = Calm, 1 = Normal, 2 = Strong, 3 = Insane. */
    val beatPulse: Int = 0,

    /**
     * Extra delay applied to the beat animation only, on top of [audioDelayMs], in milliseconds.
     *
     * A Bluetooth speaker adds its own output latency that the AudioTrack timestamp cannot see, so
     * the sound the user hears lags the sound we measured. Trimming [audioDelayMs] to compensate
     * would desync the audio itself; this shifts the visuals alone.
     */
    val beatDelayMs: Int = 0,

    /**
     * Remembered A/V trims, keyed by [AudioRoute.key], so a speaker keeps its own sync.
     *
     * [audioDelayMs] and [beatDelayMs] above stay the live values — everything that consumes a trim
     * reads those and needs no idea that routes exist. This map is the memory behind them: when the
     * output changes, the entry for the new route is written into those two fields, and when the
     * user tunes them the new numbers are written back here against whatever is playing at the time.
     *
     * A route with no entry has never been tuned. Bluetooth is seeded on first sight; see
     * [AvTrim.BLUETOOTH_SEED_BEAT_MS] for why that seed is not zero.
     */
    val avTrimProfiles: Map<String, AvTrim> = emptyMap(),

    /**
     * Human-readable name of the output the live trim currently belongs to, or blank before the
     * first detection.
     *
     * Runtime state in a persisted store, which is a fair objection — but Settings has to be able to
     * say *which speaker* the delay it is showing belongs to, and routing it through the settings
     * flow means the rows update themselves when a speaker connects with the screen already open.
     * A second AudioRouteMonitor inside the fragment would answer the same question twice.
     */
    val currentAudioRoute: String = "",

    // ─── First run ─────────────────────────────────────────────────────────
    /** False until the user has been through (or skipped) the onboarding flow. */
    val onboardingComplete: Boolean = false,

    // ─── Last sender ───────────────────────────────────────────────────────
    /** Name of the most recent sender, shown on the waiting card. Blank until one connects. */
    val lastSenderName: String = "",

    /** Wall-clock time of that connection, epoch millis. 0 when there has never been one. */
    val lastSenderAtMs: Long = 0L,

    /**
     * When true, a sender that has already completed PIN pairing does not have to enter the code
     * again on later connections.
     *
     * CAVEAT, deliberately: the AirPlay legacy PIN handshake is SRP across separate TCP connections
     * and carries no stable sender identity at the point pair-verify is gated, so this is
     * receiver-level trust, not per-device. Once any sender has paired, the PIN stops being asked of
     * everyone. Turn it off to make PIN auth strict again.
     */
    val rememberPinPairing: Boolean = true,

    /**
     * Package names shown as extra HomeKit inputs, in slot order.
     *
     * HomeKit's TV accessory already renders an input list, and until now selecting anything in it
     * just opened PhairPlay. Mapping a slot to an app turns that list into a launcher: pick
     * "Netflix" in the Home app and the Fire TV switches to Netflix. A blank entry is an unused
     * slot; [INPUT_APP_SLOTS] caps how many there are.
     */
    val inputApps: List<String> = emptyList(),
) {

    /** Advertised mirroring display size: 2560×1440 when [forceHighResolution], else 1920×1080. */
    val mirrorWidth: Int get() = if (forceHighResolution) 2560 else 1920
    val mirrorHeight: Int get() = if (forceHighResolution) 1440 else 1080

    /**
     * Returns the validated, trimmed display name.
     * If the stored name is blank, returns an empty string so callers
     * can fall back to the system device name.
     */
    val effectiveDisplayName: String
        get() = displayName.trim()

    /**
     * Returns true if at least one protocol is enabled.
     * If all three are disabled, the service has nothing to do.
     */
    val anyProtocolEnabled: Boolean
        get() = airPlayEnabled || (miracastEnabled && DeviceFeatures.MIRACAST_SUPPORTED) || dlnaEnabled

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

        /** Default AudioTrack buffer, in ms — see [audioBufferMs]. */
        const val DEFAULT_AUDIO_BUFFER_MS = 100

        /** The buffer sizes offered in Settings, in ms. */
        val AUDIO_BUFFER_CHOICES = listOf(40, 60, 100, 150, 200, 300)

        /** How many app shortcuts the HomeKit input list offers. */
        const val INPUT_APP_SLOTS = 3

        /**
         * HomeKit input identifier for app slot [index].
         *
         * Starts above the built-in AirPlay (1) and DLNA (2) inputs so the numbering never collides.
         */
        fun inputAppIdentifier(index: Int): Int = FIRST_APP_INPUT_ID + index

        /** Slot index for a HomeKit input identifier, or null if it is one of the built-ins. */
        fun inputAppSlot(identifier: Int): Int? =
            (identifier - FIRST_APP_INPUT_ID).takeIf { it in 0 until INPUT_APP_SLOTS }

        private const val FIRST_APP_INPUT_ID = 3

        /** Maximum allowed length for the display name (mDNS limit). */
        const val DISPLAY_NAME_MAX_LENGTH = 63
    }
}

/**
 * What pressing Back does.
 *
 * This replaces a pair of independent booleans ("Back returns to Home" and "Back exits PhairPlay")
 * that could both be on at once. Their combined behaviour was genuinely undefined from the user's
 * side — the labels described two different questions (where does Back go, and does the receiver
 * keep running) whose answers overlap. One ordered choice says exactly what happens instead.
 */
enum class BackAction {
    /** Back ends the stream and returns to PhairPlay's waiting screen. The receiver keeps running. */
    STOP_STREAM,

    /** Back leaves PhairPlay for the Fire TV home screen. The stream and the receiver keep running. */
    GO_HOME,

    /** Back ends the stream, stops the receiver, and removes PhairPlay from recents. */
    EXIT_APP,
    ;

    companion object {
        fun fromName(name: String?): BackAction =
            entries.firstOrNull { it.name == name } ?: STOP_STREAM
    }
}

/** What fills the screen behind the Now Playing card. */
enum class BackdropTheme {
    /** Album-coloured blobs across the whole screen, breathing with the beat. The TV default. */
    DYNAMIC,

    /** Three band orbs on true black, dissolved at every edge so a projected image has no border. */
    PROJECTOR,

    /** Plain black. No colour, no beat, nothing moving -- just the card. */
    BLACK,
    ;

    companion object {
        fun fromName(name: String?): BackdropTheme =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}

/** What PhairPlay does when the sender stops streaming. */
enum class StreamEndAction {
    /** Stay on the waiting screen with the receiver running, ready for the next sender. */
    STAY_IN_APP,

    /** Leave immediately for the Fire TV home screen. The receiver keeps running in the service. */
    EXIT_APP,
    ;

    companion object {
        fun fromName(name: String?): StreamEndAction =
            entries.firstOrNull { it.name == name } ?: STAY_IN_APP
    }
}
