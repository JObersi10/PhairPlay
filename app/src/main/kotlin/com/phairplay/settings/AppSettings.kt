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
