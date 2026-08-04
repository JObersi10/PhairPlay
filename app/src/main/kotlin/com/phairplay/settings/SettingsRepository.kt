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
        airPlayPinAuthEnabled = this[Keys.AIRPLAY_PIN_AUTH]     ?: false,
        startOnBoot        = this[Keys.START_ON_BOOT]           ?: false,
        showDebugOverlay   = this[Keys.SHOW_DEBUG_OVERLAY]      ?: false,
        backQuitsApp       = this[Keys.BACK_QUITS_APP]           ?: false,
        audioDelayMs       = this[Keys.AUDIO_DELAY_MS]           ?: 0,
        backGoesHome       = this[Keys.BACK_GOES_HOME]           ?: false,
        pipEnabled         = this[Keys.PIP_ENABLED]              ?: true,
        beatPulse          = this[Keys.BEAT_PULSE]               ?: 0,
        forceHighResolution = this[Keys.FORCE_HIGH_RESOLUTION]  ?: false,
        mirrorAudioEnabled = this[Keys.MIRROR_AUDIO_ENABLED]    ?: true,
        screensaverEnabled = this[Keys.SCREENSAVER_ENABLED]     ?: true,
        screensaverTimeoutMin = this[Keys.SCREENSAVER_TIMEOUT]  ?: 15,
        senderVolumeMode = com.phairplay.media.VolumeControlMode.fromKey(this[Keys.SENDER_VOLUME_MODE]),
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        lastSenderName = this[Keys.LAST_SENDER_NAME] ?: "",
        lastSenderAtMs = this[Keys.LAST_SENDER_AT] ?: 0L,
        rememberPinPairing = this[Keys.REMEMBER_PIN_PAIRING] ?: true
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
        this[Keys.AIRPLAY_PIN_AUTH]     = settings.airPlayPinAuthEnabled
        this[Keys.START_ON_BOOT]        = settings.startOnBoot
        this[Keys.SHOW_DEBUG_OVERLAY]   = settings.showDebugOverlay
        this[Keys.BACK_QUITS_APP]       = settings.backQuitsApp
        this[Keys.AUDIO_DELAY_MS]       = settings.audioDelayMs
        this[Keys.BACK_GOES_HOME]       = settings.backGoesHome
        this[Keys.PIP_ENABLED]          = settings.pipEnabled
        this[Keys.BEAT_PULSE]           = settings.beatPulse
        this[Keys.FORCE_HIGH_RESOLUTION] = settings.forceHighResolution
        this[Keys.MIRROR_AUDIO_ENABLED] = settings.mirrorAudioEnabled
        this[Keys.SCREENSAVER_ENABLED]  = settings.screensaverEnabled
        this[Keys.SCREENSAVER_TIMEOUT]  = settings.screensaverTimeoutMin
        this[Keys.SENDER_VOLUME_MODE]   = settings.senderVolumeMode.name
        this[Keys.ONBOARDING_COMPLETE]  = settings.onboardingComplete
        this[Keys.LAST_SENDER_NAME]     = settings.lastSenderName
        this[Keys.LAST_SENDER_AT]       = settings.lastSenderAtMs
        this[Keys.REMEMBER_PIN_PAIRING] = settings.rememberPinPairing
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
        val AIRPLAY_PIN_AUTH    = booleanPreferencesKey("airplay_pin_auth")
        val START_ON_BOOT       = booleanPreferencesKey("start_on_boot")
        val SHOW_DEBUG_OVERLAY  = booleanPreferencesKey("show_debug_overlay")
        val BACK_QUITS_APP      = booleanPreferencesKey("back_quits_app")
        val AUDIO_DELAY_MS      = intPreferencesKey("audio_delay_ms")
        val BACK_GOES_HOME      = booleanPreferencesKey("back_goes_home")
        val PIP_ENABLED         = booleanPreferencesKey("pip_enabled")
        val BEAT_PULSE          = intPreferencesKey("beat_pulse")
        val FORCE_HIGH_RESOLUTION = booleanPreferencesKey("force_high_resolution")
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
