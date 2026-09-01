package com.phairplay.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phairplay.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Extension property: creates a single DataStore instance per Context.
// The name "phairplay_settings" is the file name for the preferences store.
private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "phairplay_settings")

/**
 * SettingsRepository — Persists and reads [AppSettings] using Android DataStore.
 *
 * WHY: SharedPreferences is not coroutine-friendly and not type-safe.
 * DataStore is the modern replacement: async-by-default, type-safe with Kotlin,
 * and handles concurrent writes safely.
 *
 * HOW: Inject this into any ViewModel or component that needs settings.
 * Subscribe to [settingsFlow] for reactive updates. Call [update] to change a setting.
 *
 * Example:
 *   val repo = SettingsRepository(context)
 *
 *   // Observe settings reactively
 *   repo.settingsFlow.collect { settings ->
 *       applySettings(settings)
 *   }
 *
 *   // Change a setting
 *   repo.update { current -> current.copy(displayName = "Living Room TV") }
 */
class SettingsRepository(private val context: Context) {

    /**
     * A [Flow] that emits the current [AppSettings] and re-emits whenever any
     * setting changes. Never emits null — falls back to [AppSettings.DEFAULT].
     *
     * This flow is backed by DataStore, so it reads from disk asynchronously
     * and caches the value in memory.
     */
    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            // If DataStore fails to read (e.g., corrupt file), emit defaults
            // rather than crashing. Log the error so we can investigate.
            Logger.e("Failed to read settings from DataStore — using defaults", exception)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { prefs -> prefs.toAppSettings() }

    /**
     * Updates settings by applying the given [transform] function.
     *
     * The transform receives the current [AppSettings] and returns a new one.
     * DataStore writes the changes to disk asynchronously.
     *
     * RULE 5 (PERFORMANCE): This is a suspend function — call it from a coroutine.
     * It should NOT be called from the Main thread directly (though DataStore
     * handles the dispatching internally).
     *
     * @param transform A function that takes the current settings and returns the updated settings.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.dataStore.edit { prefs ->
                val current = prefs.toAppSettings()
                val updated = transform(current)
                prefs.fromAppSettings(updated)
            }
        } catch (e: Exception) {
            Logger.e("Failed to save settings to DataStore", e)
        }
    }

    /**
     * Resets all settings to their default values.
     *
     * Used in "reset to defaults" functionality. This is a destructive operation —
     * all user-configured settings will be lost.
     */
    suspend fun resetToDefaults() {
        try {
            context.dataStore.edit { prefs ->
                prefs.clear()
            }
            Logger.i("Settings reset to defaults")
        } catch (e: Exception) {
            Logger.e("Failed to reset settings", e)
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    /**
     * Maps a raw DataStore [Preferences] snapshot to an [AppSettings] data class.
     * Missing keys fall back to their default values in [AppSettings].
     */
    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        displayName        = this[Keys.DISPLAY_NAME]            ?: "",
        airPlayEnabled     = this[Keys.AIRPLAY_ENABLED]         ?: true,
        miracastEnabled    = this[Keys.MIRACAST_ENABLED]        ?: true,
        dlnaEnabled        = this[Keys.DLNA_ENABLED]            ?: true,
        homeKitEnabled     = this[Keys.HOMEKIT_ENABLED]         ?: false,
        airPlayPinAuthEnabled = this[Keys.AIRPLAY_PIN_AUTH]     ?: false,
        startOnBoot        = this[Keys.START_ON_BOOT]           ?: false,
        showDebugOverlay   = this[Keys.SHOW_DEBUG_OVERLAY]      ?: false,
        // Migration: BACK_ACTION replaced two overlapping booleans. Honour the old keys when the
        // new one has never been written, so an upgrade keeps whatever the user had chosen.
        backAction         = this[Keys.BACK_ACTION]?.let { BackAction.fromName(it) }
            ?: when {
                this[Keys.BACK_QUITS_APP] == true -> BackAction.EXIT_APP
                this[Keys.BACK_GOES_HOME] == true -> BackAction.GO_HOME
                else -> BackAction.STOP_STREAM
            },
        audioDelayMs       = this[Keys.AUDIO_DELAY_MS]           ?: 0,
        audioBufferMs      = this[Keys.AUDIO_BUFFER_MS]          ?: AppSettings.DEFAULT_AUDIO_BUFFER_MS,
        pipEnabled         = this[Keys.PIP_ENABLED]              ?: true,
        beatPulse          = this[Keys.BEAT_PULSE]               ?: 0,
        orbSpeed           = this[Keys.ORB_SPEED]                ?: 1,
        multiScreen        = this[Keys.MULTI_SCREEN]             ?: false,
        betaUpdates        = this[Keys.BETA_UPDATES]             ?: false,
        autoUpdateCheck    = this[Keys.AUTO_UPDATE_CHECK]        ?: true,
        lastUpdateCheckAtMs = this[Keys.LAST_UPDATE_CHECK]       ?: 0L,
        pendingUpdateTag   = this[Keys.PENDING_UPDATE_TAG]       ?: "",
        currentAudioRoute  = this[Keys.CURRENT_AUDIO_ROUTE]      ?: "",
        currentRouteCompensationMs = this[Keys.CURRENT_ROUTE_COMPENSATION] ?: 0,
        forceHighResolution = this[Keys.FORCE_HIGH_RESOLUTION]  ?: false,
        // Stored as one delimited string rather than a DataStore string set, because slot ORDER is
        // the identity here — slot 2 is a different HomeKit input from slot 1 — and a set has none.
        inputApps          = this[Keys.INPUT_APPS]?.split('\u0000')?.filter { it.isNotBlank() } ?: emptyList(),
        mirrorAudioEnabled = this[Keys.MIRROR_AUDIO_ENABLED]    ?: true,
        screensaverEnabled = this[Keys.SCREENSAVER_ENABLED]     ?: true,
        screensaverTimeoutMin = this[Keys.SCREENSAVER_TIMEOUT]  ?: 15,
        senderVolumeMode = com.phairplay.media.VolumeControlMode.fromKey(this[Keys.SENDER_VOLUME_MODE]),
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        lastSenderName = this[Keys.LAST_SENDER_NAME] ?: "",
        lastSenderAtMs = this[Keys.LAST_SENDER_AT] ?: 0L,
        rememberPinPairing = this[Keys.REMEMBER_PIN_PAIRING] ?: true,
        // remoteEnabled was declared in AppSettings but never appeared here or in
        // fromAppSettings, so the Settings toggle wrote a value that was thrown away and the
        // field always read its default. Persisted now.
        remoteEnabled      = this[Keys.REMOTE_ENABLED]          ?: false,
        // Migration: BACKDROP_THEME replaced the projectorMode boolean, which could only say
        // "orbs on black" or "not that" and had no way to express "nothing at all".
        backdropTheme      = this[Keys.BACKDROP_THEME]?.let { BackdropTheme.fromName(it) }
            ?: if (this[Keys.PROJECTOR_MODE] == true) BackdropTheme.PROJECTOR
               else BackdropTheme.DYNAMIC,
        artworkLookup      = this[Keys.ARTWORK_LOOKUP]          ?: false,
        streamEndAction    = StreamEndAction.fromName(this[Keys.STREAM_END_ACTION])
    )

    /**
     * Writes an [AppSettings] data class into a mutable [MutablePreferences].
     * Called inside a DataStore edit transaction.
     */
    private fun MutablePreferences.fromAppSettings(settings: AppSettings) {
        this[Keys.DISPLAY_NAME]         = settings.displayName
        this[Keys.AIRPLAY_ENABLED]      = settings.airPlayEnabled
        this[Keys.MIRACAST_ENABLED]     = settings.miracastEnabled
        this[Keys.DLNA_ENABLED]         = settings.dlnaEnabled
        this[Keys.HOMEKIT_ENABLED]      = settings.homeKitEnabled
        this[Keys.AIRPLAY_PIN_AUTH]     = settings.airPlayPinAuthEnabled
        this[Keys.START_ON_BOOT]        = settings.startOnBoot
        this[Keys.SHOW_DEBUG_OVERLAY]   = settings.showDebugOverlay
        this[Keys.BACK_ACTION]          = settings.backAction.name
        this[Keys.AUDIO_DELAY_MS]       = settings.audioDelayMs
        this[Keys.AUDIO_BUFFER_MS]      = settings.audioBufferMs
        this[Keys.PIP_ENABLED]          = settings.pipEnabled
        this[Keys.BEAT_PULSE]           = settings.beatPulse
        this[Keys.ORB_SPEED]            = settings.orbSpeed
        this[Keys.MULTI_SCREEN]         = settings.multiScreen
        this[Keys.BETA_UPDATES]         = settings.betaUpdates
        this[Keys.AUTO_UPDATE_CHECK]    = settings.autoUpdateCheck
        this[Keys.LAST_UPDATE_CHECK]    = settings.lastUpdateCheckAtMs
        this[Keys.PENDING_UPDATE_TAG]   = settings.pendingUpdateTag
        this[Keys.CURRENT_AUDIO_ROUTE]  = settings.currentAudioRoute
        this[Keys.CURRENT_ROUTE_COMPENSATION] = settings.currentRouteCompensationMs
        this[Keys.FORCE_HIGH_RESOLUTION] = settings.forceHighResolution
        this[Keys.INPUT_APPS] = settings.inputApps.joinToString("\u0000")
        this[Keys.MIRROR_AUDIO_ENABLED] = settings.mirrorAudioEnabled
        this[Keys.SCREENSAVER_ENABLED]  = settings.screensaverEnabled
        this[Keys.SCREENSAVER_TIMEOUT]  = settings.screensaverTimeoutMin
        this[Keys.SENDER_VOLUME_MODE]   = settings.senderVolumeMode.name
        this[Keys.ONBOARDING_COMPLETE]  = settings.onboardingComplete
        this[Keys.LAST_SENDER_NAME]     = settings.lastSenderName
        this[Keys.LAST_SENDER_AT]       = settings.lastSenderAtMs
        this[Keys.REMEMBER_PIN_PAIRING] = settings.rememberPinPairing
        this[Keys.REMOTE_ENABLED]       = settings.remoteEnabled
        this[Keys.BACKDROP_THEME]       = settings.backdropTheme.name
        this[Keys.ARTWORK_LOOKUP]       = settings.artworkLookup
        this[Keys.STREAM_END_ACTION]    = settings.streamEndAction.name
    }

    /**
     * DataStore preference keys.
     *
     * WHY a separate object: centralizing keys prevents typos and makes
     * it easy to see all stored preferences in one place.
     */
    private object Keys {
        val DISPLAY_NAME        = stringPreferencesKey("display_name")
        val AIRPLAY_ENABLED     = booleanPreferencesKey("airplay_enabled")
        val MIRACAST_ENABLED    = booleanPreferencesKey("miracast_enabled")
        val DLNA_ENABLED        = booleanPreferencesKey("dlna_enabled")
        val HOMEKIT_ENABLED     = booleanPreferencesKey("homekit_enabled")
        val AIRPLAY_PIN_AUTH    = booleanPreferencesKey("airplay_pin_auth")
        val START_ON_BOOT       = booleanPreferencesKey("start_on_boot")
        val SHOW_DEBUG_OVERLAY  = booleanPreferencesKey("show_debug_overlay")
        // Legacy, read-only: superseded by BACK_ACTION but still consulted when migrating.
        val BACK_QUITS_APP      = booleanPreferencesKey("back_quits_app")
        val REMOTE_ENABLED      = booleanPreferencesKey("remote_enabled")
        /** Legacy, read-only: superseded by BACKDROP_THEME but still consulted when migrating. */
        val PROJECTOR_MODE      = booleanPreferencesKey("projector_mode")
        val BACKDROP_THEME      = stringPreferencesKey("backdrop_theme")
        val ARTWORK_LOOKUP      = booleanPreferencesKey("artwork_lookup")
        val STREAM_END_ACTION   = androidx.datastore.preferences.core.stringPreferencesKey("stream_end_action")
        val BACK_ACTION         = stringPreferencesKey("back_action")
        val AUDIO_DELAY_MS      = intPreferencesKey("audio_delay_ms")
        val AUDIO_BUFFER_MS     = intPreferencesKey("audio_buffer_ms")
        val BACK_GOES_HOME      = booleanPreferencesKey("back_goes_home")
        val PIP_ENABLED         = booleanPreferencesKey("pip_enabled")
        val BEAT_PULSE          = intPreferencesKey("beat_pulse")
        val ORB_SPEED           = intPreferencesKey("orb_speed")
        val MULTI_SCREEN        = booleanPreferencesKey("multi_screen")
        val BETA_UPDATES        = booleanPreferencesKey("beta_updates")
        val AUTO_UPDATE_CHECK   = booleanPreferencesKey("auto_update_check")
        val LAST_UPDATE_CHECK   = longPreferencesKey("last_update_check")
        val PENDING_UPDATE_TAG  = stringPreferencesKey("pending_update_tag")
        // beat_delay_ms and av_trim_profiles were briefly settings and are now neither read nor
        // written: the Bluetooth compensation is a property of the transport, not a preference, so
        // it is derived from the route every time rather than stored. Any leftover values sit
        // harmlessly in the store.
        val CURRENT_AUDIO_ROUTE = stringPreferencesKey("current_audio_route")
        val CURRENT_ROUTE_COMPENSATION = intPreferencesKey("current_route_compensation_ms")
        val FORCE_HIGH_RESOLUTION = booleanPreferencesKey("force_high_resolution")
        val INPUT_APPS = stringPreferencesKey("input_apps")
        val MIRROR_AUDIO_ENABLED = booleanPreferencesKey("mirror_audio_enabled")
        val SCREENSAVER_ENABLED = booleanPreferencesKey("screensaver_enabled")
        val SCREENSAVER_TIMEOUT = intPreferencesKey("screensaver_timeout_min")
        val SENDER_VOLUME_MODE = stringPreferencesKey("sender_volume_mode")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val LAST_SENDER_NAME = stringPreferencesKey("last_sender_name")
        val LAST_SENDER_AT = longPreferencesKey("last_sender_at")
        val REMEMBER_PIN_PAIRING = booleanPreferencesKey("remember_pin_pairing")
    }
}
