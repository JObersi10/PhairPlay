package com.phairplay.settings

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
     * Whether leaving the app during a video stream enters picture-in-picture instead of simply
     * backgrounding. Only affects mirroring — audio-only sessions have no video to shrink.
     */
    val pipEnabled: Boolean = true,

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
        get() = airPlayEnabled || miracastEnabled || dlnaEnabled

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

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
