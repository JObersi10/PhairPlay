package com.phairplay.ui

import android.app.AlertDialog
import com.phairplay.util.SystemPermissions
import com.phairplay.MainActivity
import android.os.Bundle
import com.phairplay.service.ServiceController
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.BuildConfig
import com.phairplay.DeviceFeatures
import com.phairplay.R
import com.phairplay.settings.AppSettings
import com.phairplay.settings.BackAction
import com.phairplay.settings.BackdropTheme
import com.phairplay.settings.SettingsRepository
import com.phairplay.settings.StreamEndAction
import com.phairplay.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SettingsFragment — Settings screen for PhairPlay.
 *
 * WHY: Centralizes all user-configurable options in one screen. By separating
 * settings into their own Fragment, we keep MainActivity lean and make it easy
 * to navigate to/from settings via the nav panel.
 *
 * HOW: Reads current settings from [SettingsRepository] and populates the UI.
 * Each toggle/row saves immediately when changed (no "Save" button needed).
 * Settings changes take effect on the next service restart.
 *
 * Navigation: accessed via the "Settings" item in MainActivity's nav panel.
 */
class SettingsFragment : Fragment() {

    private lateinit var settingsRepository: SettingsRepository

    // Section header TextViews — set via include layout tag IDs

    // Settings rows
    private lateinit var rowDisplayName: LinearLayout
    private lateinit var textDisplayNameValue: TextView
    private lateinit var rowAirPlay: View
    private lateinit var rowMiracast: View
    private lateinit var rowDlna: View
    private lateinit var rowHomeKit: View
    private lateinit var rowHomeKitReset: View
    private lateinit var rowMirrorAudio: View
    private lateinit var rowMultiScreen: View
    private lateinit var rowBetaUpdates: View
    /** Cached so the update check does not have to suspend on DataStore to know its channel. */
    private var betaUpdates = false
    private lateinit var rowPinAuth: View
    private lateinit var rowStartOnBoot: View
    private lateinit var rowDebugOverlay: View
    private lateinit var rowBackdropTheme: View
    private lateinit var textBackdropThemeValue: TextView
    private lateinit var rowArtworkLookup: View
    private lateinit var rowIdentifyTracks: View
    private lateinit var rowStreamEndAction: View
    private lateinit var rowBackAction: LinearLayout
    private lateinit var textBackActionValue: TextView
    private lateinit var rowPip: View
    private lateinit var textAudioDelaySubtitle: TextView
    private lateinit var rowBeatPulse: LinearLayout
    private lateinit var textBeatPulseValue: TextView
    private lateinit var rowOrbSpeed: LinearLayout
    private lateinit var textOrbSpeedValue: TextView
    private lateinit var rowAudioDelay: LinearLayout
    private lateinit var textAudioDelayValue: TextView
    private lateinit var rowForceHighRes: View
    private lateinit var rowRememberPin: View
    private lateinit var rowRemoteEnabled: View
    private lateinit var rowInputApps: LinearLayout
    private lateinit var textInputAppsValue: TextView
    private lateinit var rowForgetPairings: LinearLayout
    private lateinit var rowPermRemote: LinearLayout
    private lateinit var rowPermOverlay: LinearLayout
    private lateinit var rowPermSleep: LinearLayout
    private lateinit var textPermRemoteValue: TextView
    private lateinit var textPermOverlayValue: TextView
    private lateinit var textPermSleepValue: TextView
    private lateinit var rowSenderVolume: LinearLayout
    private lateinit var textSenderVolumeValue: TextView
    private lateinit var rowScreensaver: View
    private lateinit var rowScreensaverTimeout: LinearLayout
    private lateinit var textScreensaverTimeoutValue: TextView
    private lateinit var textVersionValue: TextView
    private lateinit var rowAudioBuffer: LinearLayout
    private lateinit var textAudioBufferValue: TextView
    private lateinit var rowUpdate: LinearLayout
    private lateinit var textUpdateValue: TextView
    private lateinit var rowReset: LinearLayout
    private lateinit var rowQuit: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsRepository = SettingsRepository(requireContext())
        bindViews(view)
        setRowLabels()
        loadAndPopulate()
    }

    private lateinit var paneScroll: android.widget.ScrollView
    private lateinit var categories: List<Category>

    // ─── View Binding ────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        bindCategories(view)

        rowDisplayName      = view.findViewById(R.id.row_display_name)
        textDisplayNameValue = view.findViewById(R.id.text_display_name_value)
        rowAirPlay          = view.findViewById(R.id.row_airplay)
        rowMiracast         = view.findViewById(R.id.row_miracast)
        rowDlna             = view.findViewById(R.id.row_dlna)
        rowHomeKit          = view.findViewById(R.id.row_homekit)
        rowHomeKitReset     = view.findViewById(R.id.row_homekit_reset)
        rowMirrorAudio      = view.findViewById(R.id.row_mirror_audio)
        rowMultiScreen      = view.findViewById(R.id.row_multi_screen)
        rowBetaUpdates      = view.findViewById(R.id.row_beta_updates)
        rowPinAuth          = view.findViewById(R.id.row_pin_auth)
        rowStartOnBoot      = view.findViewById(R.id.row_start_on_boot)
        rowDebugOverlay     = view.findViewById(R.id.row_debug_overlay)
        rowBackdropTheme    = view.findViewById(R.id.row_backdrop_theme)
        textBackdropThemeValue = view.findViewById(R.id.text_backdrop_theme_value)
        rowArtworkLookup    = view.findViewById(R.id.row_artwork_lookup)
        rowIdentifyTracks   = view.findViewById(R.id.row_identify_tracks)
        rowStreamEndAction  = view.findViewById(R.id.row_stream_end_action)
        rowBackAction       = view.findViewById(R.id.row_back_action)
        textBackActionValue = view.findViewById(R.id.text_back_action_value)
        rowPip              = view.findViewById(R.id.row_pip)
        textAudioDelaySubtitle = view.findViewById(R.id.text_audio_delay_subtitle)
        rowBeatPulse        = view.findViewById(R.id.row_beat_pulse)
        textBeatPulseValue  = view.findViewById(R.id.text_beat_pulse_value)
        rowOrbSpeed         = view.findViewById(R.id.row_orb_speed)
        textOrbSpeedValue   = view.findViewById(R.id.text_orb_speed_value)
        rowAudioDelay       = view.findViewById(R.id.row_audio_delay)
        textAudioDelayValue = view.findViewById(R.id.text_audio_delay_value)
        rowForceHighRes     = view.findViewById(R.id.row_force_high_res)
        rowRememberPin      = view.findViewById(R.id.row_remember_pin)
        rowRemoteEnabled    = view.findViewById(R.id.row_remote_enabled)
        rowInputApps        = view.findViewById(R.id.row_input_apps)
        textInputAppsValue  = view.findViewById(R.id.text_input_apps_value)
        rowForgetPairings   = view.findViewById(R.id.row_forget_pairings)
        rowPermRemote       = view.findViewById(R.id.row_perm_remote)
        rowPermOverlay      = view.findViewById(R.id.row_perm_overlay)
        rowPermSleep        = view.findViewById(R.id.row_perm_sleep)
        textPermRemoteValue = view.findViewById(R.id.text_perm_remote_value)
        textPermOverlayValue = view.findViewById(R.id.text_perm_overlay_value)
        textPermSleepValue  = view.findViewById(R.id.text_perm_sleep_value)
        rowSenderVolume     = view.findViewById(R.id.row_sender_volume)
        textSenderVolumeValue = view.findViewById(R.id.text_sender_volume_value)
        rowScreensaver      = view.findViewById(R.id.row_screensaver)
        rowScreensaverTimeout = view.findViewById(R.id.row_screensaver_timeout)
        textScreensaverTimeoutValue = view.findViewById(R.id.text_screensaver_timeout_value)
        textVersionValue    = view.findViewById(R.id.text_version_value)
        rowAudioBuffer      = view.findViewById(R.id.row_audio_buffer)
        textAudioBufferValue = view.findViewById(R.id.text_audio_buffer_value)
        rowUpdate           = view.findViewById(R.id.row_update)
        textUpdateValue     = view.findViewById(R.id.text_update_value)
        rowReset            = view.findViewById(R.id.row_reset)
        rowQuit             = view.findViewById(R.id.row_quit)
    }

    /**
     * Wires the shortcut list to the one continuous scroller.
     *
     * The shortcuts scroll; they do not hide anything. Focusing one glides the list to that
     * section, so moving down the shortcuts previews the whole page, and moving RIGHT drops you
     * into the rows exactly where you were looking.
     */
    private fun bindCategories(view: View) {
        paneScroll = view.findViewById(R.id.settings_pane_scroll)
        categories = CATEGORY_IDS.map { (navId, sectionId) ->
            Category(view.findViewById(navId), view.findViewById(sectionId))
        }
        categories.forEach { category ->
            category.nav.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) scrollTo(category)
            }
            category.nav.setOnClickListener {
                scrollTo(category)
                // OK jumps into the section's first row rather than doing nothing visible.
                (category.section.parent as? ViewGroup)
                    ?.let { parent -> parent.indexOfChild(category.section) }
                    ?.let { idx -> (category.section.parent as ViewGroup).getChildAt(idx + 1) }
                    ?.requestFocus()
            }
        }
        // Focus motion on the shortcuts and on every row in the list.
        (view.findViewById<ViewGroup>(R.id.settings_categories))?.let(FocusMotion::attachToChildren)
        (view.findViewById<ViewGroup>(R.id.settings_content))?.let { content ->
            for (i in 0 until content.childCount) {
                (content.getChildAt(i) as? ViewGroup)?.let(FocusMotion::attachToChildren)
            }
        }
        categories.firstOrNull()?.nav?.requestFocus()
    }

    /** Glides the scroller so [category]'s heading sits at the top of the visible area. */
    private fun scrollTo(category: Category) {
        categories.forEach { it.nav.isSelected = it === category }
        // smoothScrollTo, not scrollTo: the jump is what makes a shortcut list feel like it
        // teleported you somewhere unrelated. The glide shows you how far you moved.
        paneScroll.smoothScrollTo(0, (category.section.top - SECTION_TOP_GAP_PX).coerceAtLeast(0))
    }

    private data class Category(val nav: View, val section: View)


    /** Sets all row labels and subtitles from string resources. */
    private fun setRowLabels() {
        configureToggleRow(rowAirPlay,      R.string.setting_airplay_enabled,    R.string.setting_airplay_subtitle)
        // Hidden rather than disabled on Fire TV: a greyed-out row still reads as "this device could
        // do Miracast", and it cannot. See DeviceFeatures.
        if (DeviceFeatures.MIRACAST_SUPPORTED) {
            configureToggleRow(rowMiracast, R.string.setting_miracast_enabled, R.string.setting_miracast_subtitle)
        } else {
            rowMiracast.visibility = View.GONE
        }
        configureToggleRow(rowDlna,         R.string.setting_dlna_enabled,       R.string.setting_dlna_subtitle)
        configureToggleRow(rowHomeKit,      R.string.setting_homekit_enabled,    R.string.setting_homekit_subtitle)
        configureToggleRow(rowHomeKitReset, R.string.setting_homekit_reset,      R.string.setting_homekit_reset_subtitle)
        // The reset row is an action, not a toggle; leaving its switch visible would imply HomeKit
        // has two independent on/off states.
        rowHomeKitReset.findViewById<SwitchCompat>(R.id.switch_setting)?.visibility = View.GONE
        configureToggleRow(rowMirrorAudio,  R.string.setting_mirror_audio,       R.string.setting_mirror_audio_subtitle)
        configureToggleRow(rowMultiScreen,  R.string.setting_multi_screen,       R.string.setting_multi_screen_subtitle)
        configureToggleRow(rowBetaUpdates,  R.string.setting_beta_updates,       R.string.setting_beta_updates_subtitle)
        configureToggleRow(rowPinAuth,      R.string.setting_pin_auth,           R.string.setting_pin_auth_subtitle)
        configureToggleRow(rowRememberPin,   R.string.setting_remember_pin,       R.string.setting_remember_pin_subtitle)
        configureToggleRow(rowRemoteEnabled, R.string.setting_remote,             R.string.setting_remote_subtitle)
        configureToggleRow(rowScreensaver,  R.string.setting_screensaver,        R.string.setting_screensaver_subtitle)
        configureToggleRow(rowStartOnBoot,  R.string.setting_start_on_boot,      0)
        configureToggleRow(rowDebugOverlay, R.string.setting_debug_overlay,      R.string.setting_debug_overlay_subtitle)
        configureToggleRow(rowArtworkLookup, R.string.setting_artwork_lookup,    R.string.setting_artwork_lookup_subtitle)
        configureToggleRow(rowIdentifyTracks, R.string.setting_identify_tracks,  R.string.setting_identify_tracks_subtitle)
        configureToggleRow(rowStreamEndAction, R.string.setting_stream_end,     R.string.setting_stream_end_subtitle)
        configureToggleRow(rowPip,         R.string.setting_pip,                R.string.setting_pip_subtitle)
        configureToggleRow(rowForceHighRes, R.string.setting_force_high_res,      R.string.setting_force_high_res_subtitle)

        textVersionValue.text =
            getString(R.string.setting_version_value, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
        showNetworkInfo()
    }

    private fun showNetworkInfo() {
        val ip = getWifiIp() ?: return
        // One port now: / is the dump and /tail is the live stream.
        val port = com.phairplay.diagnostic.DiagnosticServer.PORT
        textVersionValue.text =
            getString(R.string.settings_version_logs, BuildConfig.VERSION_NAME, ip, port.toString())
    }

    private fun getWifiIp(): String? {
        val wm = requireContext().applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return null
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    /**
     * Sets the label and optional subtitle on a toggle row view.
     *
     * @param row       The row view (from settings_toggle_row.xml)
     * @param labelRes  String resource for the main label
     * @param subtitleRes String resource for the subtitle, or 0 to hide it
     */
    private fun configureToggleRow(row: View, labelRes: Int, subtitleRes: Int) {
        row.findViewById<TextView>(R.id.text_setting_label)?.setText(labelRes)
        val subtitle = row.findViewById<TextView>(R.id.text_setting_subtitle)
        if (subtitleRes != 0) {
            subtitle?.setText(subtitleRes)
            subtitle?.visibility = View.VISIBLE
        } else {
            subtitle?.visibility = View.GONE
        }
    }

    // ─── Settings Load & Save ────────────────────────────────────────────────

    /**
     * Loads the current settings and populates the UI.
     * Then sets up click/toggle listeners for each row.
     */
    private fun loadAndPopulate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            populateUI(settings)
            setupListeners()
        }
    }

    /** Populates all UI elements with values from [settings]. */
    private fun populateUI(settings: AppSettings) {
        textDisplayNameValue.text = settings.effectiveDisplayName.ifEmpty {
            getString(R.string.setting_display_name_system_default)
        }
        setToggle(rowAirPlay,      settings.airPlayEnabled)
        setToggle(rowMiracast,     settings.miracastEnabled)
        setToggle(rowDlna,         settings.dlnaEnabled)
        setToggle(rowHomeKit,      settings.homeKitEnabled)
        renderHomeKitStatus(settings.homeKitEnabled)
        setToggle(rowMirrorAudio,  settings.mirrorAudioEnabled)
        setToggle(rowMultiScreen,  settings.multiScreen)
        setToggle(rowBetaUpdates,  settings.betaUpdates)
        // What the background check last found, so a waiting update is visible without pressing
        // anything. Blank clears it, which is what happens once the check says we are current.
        textUpdateValue.text = settings.pendingUpdateTag.ifBlank { "" }
        betaUpdates = settings.betaUpdates
        setToggle(rowPinAuth,      settings.airPlayPinAuthEnabled)
        setToggle(rowStartOnBoot,  settings.startOnBoot)
        setToggle(rowDebugOverlay, settings.showDebugOverlay)
        showBackdropTheme(settings.backdropTheme)
        setToggle(rowArtworkLookup, settings.artworkLookup)
        setToggle(rowIdentifyTracks, settings.identifyTracks)
        setToggle(rowStreamEndAction, settings.streamEndAction == StreamEndAction.EXIT_APP)
        showBackAction(settings.backAction)
        setToggle(rowPip, settings.pipEnabled)
        showAudioDelay(settings.audioDelayMs)
        showTrimOutput(settings.currentAudioRoute, settings.currentRouteCompensationMs)
        showAudioBuffer(settings.audioBufferMs)
        showBeatPulse(settings.beatPulse)
        showOrbSpeed(settings.orbSpeed)
        setToggle(rowForceHighRes, settings.forceHighResolution)
        textInputAppsValue.text = describeInputApps(settings.inputApps)
        setToggle(rowRememberPin,  settings.rememberPinPairing)
        setToggle(rowRemoteEnabled, settings.remoteEnabled)
        showSenderVolumeMode(settings.senderVolumeMode)
        setToggle(rowScreensaver,  settings.screensaverEnabled)
        showScreensaverTimeout(settings.screensaverTimeoutMin)
    }

    /**
     * Drops every stored pairing so the PIN is demanded again. Done through the store directly rather
     * than the service so it works whether or not a receiver is currently running.
     */
    private fun forgetPairings() {
        com.phairplay.airplay.handshake.PairingStore(requireContext()).clearAll()
        Logger.i("Paired senders forgotten")
        android.widget.Toast.makeText(
            requireContext(), R.string.setting_forget_pairings_done, android.widget.Toast.LENGTH_LONG
        ).show()
        ServiceController.restart(requireContext())
    }

    private fun showSenderVolumeMode(mode: com.phairplay.media.VolumeControlMode) {
        textSenderVolumeValue.setText(volumeModeLabel(mode))
    }

    private fun volumeModeLabel(mode: com.phairplay.media.VolumeControlMode) = when (mode) {
        com.phairplay.media.VolumeControlMode.OFF           -> R.string.setting_sender_volume_off
        com.phairplay.media.VolumeControlMode.EXTERNAL_ONLY -> R.string.setting_sender_volume_external
        com.phairplay.media.VolumeControlMode.ALWAYS        -> R.string.setting_sender_volume_always
    }

    private fun showSenderVolumeDialog() {
        val modes = com.phairplay.media.VolumeControlMode.entries.toList()
        val labels = modes.map { getString(volumeModeLabel(it)) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_sender_volume)
            .setItems(labels) { _, which ->
                val mode = modes[which]
                save { it.copy(senderVolumeMode = mode) }
                showSenderVolumeMode(mode)
                Logger.i("Sender volume mode set to $mode")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showScreensaverTimeout(minutes: Int) {
        textScreensaverTimeoutValue.text = resources.getQuantityString(
            R.plurals.setting_screensaver_timeout_value, minutes, minutes)
    }

    private fun setToggle(row: View, value: Boolean) {
        row.findViewById<SwitchCompat>(R.id.switch_setting)?.isChecked = value
    }

    /**
     * Sets up click and toggle listeners for all settings rows.
     * Each listener immediately persists the change via [SettingsRepository.update].
     *
     * No "Save" button is needed — settings are saved on every interaction.
     * A restart prompt is shown after protocol-affecting changes.
     */
    private fun setupListeners() {
        rowDisplayName.setOnClickListener { showDisplayNameDialog() }

        setToggleListener(rowAirPlay)      { enabled -> save { it.copy(airPlayEnabled = enabled) } }
        setToggleListener(rowMiracast)     { enabled -> save { it.copy(miracastEnabled = enabled) } }
        setToggleListener(rowDlna)         { enabled -> save { it.copy(dlnaEnabled = enabled) } }
        setToggleListener(rowHomeKit)      { enabled ->
            save { it.copy(homeKitEnabled = enabled) }
            renderHomeKitStatus(enabled)
        }
        rowHomeKitReset.setOnClickListener { confirmResetHomeKit() }
        setToggleListener(rowMirrorAudio)  { enabled -> saveAndRestart { it.copy(mirrorAudioEnabled = enabled) } }
        // Restart required: the session capacity is fixed when the RTSP handler is constructed.
        setToggleListener(rowMultiScreen)  { enabled -> saveAndRestart { it.copy(multiScreen = enabled) } }
        // No restart: this only changes which endpoint the next update check reads.
        setToggleListener(rowBetaUpdates)  { enabled ->
            betaUpdates = enabled
            save { it.copy(betaUpdates = enabled) }
        }
        setToggleListener(rowPinAuth)      { enabled -> saveAndRestart { it.copy(airPlayPinAuthEnabled = enabled) } }
        setToggleListener(rowStartOnBoot)  { enabled -> save { it.copy(startOnBoot = enabled) } }
        setToggleListener(rowDebugOverlay) { enabled -> save { it.copy(showDebugOverlay = enabled) } }
        rowBackdropTheme.setOnClickListener { pickBackdropTheme() }
        setToggleListener(rowArtworkLookup) { enabled -> save { it.copy(artworkLookup = enabled) } }
        setToggleListener(rowIdentifyTracks) { enabled -> save { it.copy(identifyTracks = enabled) } }
        setToggleListener(rowStreamEndAction) { enabled ->
            save { it.copy(streamEndAction = if (enabled) StreamEndAction.EXIT_APP else StreamEndAction.STAY_IN_APP) }
        }
        rowBackAction.setOnClickListener { pickBackAction() }
        setToggleListener(rowPip) { enabled -> save { it.copy(pipEnabled = enabled) } }
        rowAudioDelay.setOnClickListener { pickAudioDelay() }
        rowAudioBuffer.setOnClickListener { pickAudioBuffer() }
        rowBeatPulse.setOnClickListener { pickBeatPulse() }
        rowOrbSpeed.setOnClickListener { pickOrbSpeed() }
        // Restart, not a plain save: the resolution is baked into the /info response and the mirror
        // video server at receiver startup, so a plain save left the toggle looking broken — it
        // flipped in the UI and nothing changed until the service happened to restart later.
        setToggleListener(rowForceHighRes) { enabled -> saveAndRestart { it.copy(forceHighResolution = enabled) } }
        rowInputApps.setOnClickListener { showInputAppSlotDialog() }
        setToggleListener(rowScreensaver)  { enabled -> save { it.copy(screensaverEnabled = enabled) } }
        rowScreensaverTimeout.setOnClickListener { showScreensaverTimeoutDialog() }
        rowSenderVolume.setOnClickListener { showSenderVolumeDialog() }
        setToggleListener(rowRememberPin) { enabled -> saveAndRestart { it.copy(rememberPinPairing = enabled) } }
        setToggleListener(rowRemoteEnabled) { enabled -> saveAndRestart { it.copy(remoteEnabled = enabled) } }
        rowForgetPairings.setOnClickListener { forgetPairings() }
        rowPermRemote.setOnClickListener { grantRemoteControl() }
        rowPermOverlay.setOnClickListener { grantOverlay() }
        rowPermSleep.setOnClickListener { grantSleep() }

        rowUpdate.setOnClickListener { checkForUpdate() }
        rowReset.setOnClickListener { confirmResetSettings() }
        rowQuit.setOnClickListener { confirmQuit() }
    }

    private fun setToggleListener(row: View, onChanged: (Boolean) -> Unit) {
        // The whole row is clickable (better TV UX than just the Switch widget)
        row.setOnClickListener {
            val switch = row.findViewById<SwitchCompat>(R.id.switch_setting) ?: return@setOnClickListener
            val newValue = !switch.isChecked
            switch.isChecked = newValue
            onChanged(newValue)
        }
    }

    /**
     * Saves an updated [AppSettings] via the repository.
     * Runs in a coroutine so it doesn't block the UI thread.
     *
     * @param transform A function that takes the current settings and returns updated settings.
     */
    private fun save(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            Logger.d("Settings saved")
        }
    }

    /**
     * Saves a setting that the AirPlay receiver only reads at startup (mirror-audio, PIN auth), then
     * restarts the service so the change applies immediately instead of on the next manual restart.
     */
    private fun saveAndRestart(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            ServiceController.restart(requireContext())
            Logger.i("Settings saved — restarting receivers to apply")
        }
    }

    /**
     * Shows a dialog allowing the user to edit the AirPlay display name.
     *
     * WHY: The display name is what appears in the macOS/iOS AirPlay picker.
     * Changing it is infrequent but important for multi-TV households.
     *
     * TV UX notes:
     * - The EditText is pre-filled with the current name (empty = system default)
     * - Max length is enforced to [AppSettings.DISPLAY_NAME_MAX_LENGTH] (63 chars, mDNS limit)
     * - "OK" saves the new name; "Reset to default" clears to "" (system name); "Cancel" = no-op
     * - Name trimming is applied on save — pure-whitespace names are treated as blank
     *
     * Collision detection: Android's NsdManager automatically appends " (2)", " (3)" etc. if
     * another device on the network already uses the same mDNS name. This is transparent to
     * the user at save-time; the actual registered name is logged at registration.
     */
    private fun showDisplayNameDialog() {
        val currentName = viewLifecycleOwner.lifecycleScope.run {
            // Read directly from the displayed value (already loaded)
            val displayed = textDisplayNameValue.text?.toString() ?: ""
            if (displayed == getString(R.string.setting_display_name_system_default)) "" else displayed
        }

        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = getString(R.string.setting_display_name_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(AppSettings.DISPLAY_NAME_MAX_LENGTH))
            setSingleLine(true)
            // Move cursor to end so user can append rather than overwrite
            setSelection(currentName.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_display_name)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text?.toString()?.trim() ?: ""
                save { it.copy(displayName = newName) }
                textDisplayNameValue.text = newName.ifEmpty {
                    getString(R.string.setting_display_name_system_default)
                }
                Logger.i("Display name updated to: '${newName.ifEmpty { "(system default)" }}'")
            }
            .setNeutralButton(R.string.setting_display_name_reset) { _, _ ->
                save { it.copy(displayName = "") }
                textDisplayNameValue.text = getString(R.string.setting_display_name_system_default)
                Logger.i("Display name reset to system default")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Lets the user pick how long the Now Playing screen sits idle before the screensaver starts.
     * A fixed list rather than free entry: a TV remote makes number entry painful, and these cover
     * the useful range from "album sleeve for one track" to "leave it up all evening".
     */
    private fun showScreensaverTimeoutDialog() {
        val labels = SCREENSAVER_TIMEOUT_CHOICES.map {
            resources.getQuantityString(R.plurals.setting_screensaver_timeout_value, it, it)
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_screensaver_timeout)
            .setItems(labels) { _, which ->
                val minutes = SCREENSAVER_TIMEOUT_CHOICES[which]
                save { it.copy(screensaverTimeoutMin = minutes) }
                showScreensaverTimeout(minutes)
                Logger.i("Screensaver timeout set to $minutes min")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beatPulseLabel(level: Int): String = getString(when (level) {
        1 -> R.string.setting_beat_pulse_normal
        2 -> R.string.setting_beat_pulse_strong
        3 -> R.string.setting_beat_pulse_insane
        else -> R.string.setting_beat_pulse_calm
    })

    private fun showBeatPulse(level: Int) { textBeatPulseValue.text = beatPulseLabel(level) }

    private fun orbSpeedLabel(level: Int): String = getString(when (level) {
        0 -> R.string.setting_orb_speed_slow
        2 -> R.string.setting_orb_speed_fast
        else -> R.string.setting_orb_speed_normal
    })

    private fun showOrbSpeed(level: Int) { textOrbSpeedValue.text = orbSpeedLabel(level) }

    private fun pickOrbSpeed() {
        val labels = arrayOf(orbSpeedLabel(0), orbSpeedLabel(1), orbSpeedLabel(2))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_orb_speed)
            .setItems(labels) { _, which ->
                save { it.copy(orbSpeed = which) }
                showOrbSpeed(which)
                Logger.i("Orb speed set to ${orbSpeedLabel(which)}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickBeatPulse() {
        val labels = arrayOf(beatPulseLabel(0), beatPulseLabel(1), beatPulseLabel(2), beatPulseLabel(3))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_beat_pulse)
            .setItems(labels) { _, which ->
                val level = which
                save { it.copy(beatPulse = level) }
                showBeatPulse(level)
                Logger.i("Beat pulse set to ${beatPulseLabel(level)}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Says out loud that a Bluetooth speaker is already being compensated for.
     *
     * The compensation is not a setting and the row above still reads whatever the user chose, so
     * without this line the only evidence of it is that things happen to be in sync. Stating it is
     * also what stops someone dialling in another 350ms by hand on top of it.
     */
    private fun showTrimOutput(routeLabel: String, compensationMs: Int) {
        if (routeLabel.isBlank() || compensationMs <= 0) {
            textAudioDelaySubtitle.setText(R.string.setting_audio_delay_subtitle)
            return
        }
        textAudioDelaySubtitle.text =
            getString(R.string.setting_audio_delay_bluetooth, routeLabel, compensationMs)
    }

    private fun backActionLabel(action: BackAction): String = getString(when (action) {
        BackAction.STOP_STREAM -> R.string.back_action_stop_stream
        BackAction.GO_HOME     -> R.string.back_action_go_home
        BackAction.EXIT_APP    -> R.string.back_action_exit_app
    })

    private fun backActionDetail(action: BackAction): String = getString(when (action) {
        BackAction.STOP_STREAM -> R.string.back_action_stop_stream_detail
        BackAction.GO_HOME     -> R.string.back_action_go_home_detail
        BackAction.EXIT_APP    -> R.string.back_action_exit_app_detail
    })

    private fun showBackAction(action: BackAction) {
        textBackActionValue.text = backActionLabel(action)
    }

    /**
     * One ordered choice instead of two overlapping switches.
     *
     * Each option spells out its consequence, because the distinction that matters — whether the
     * receiver keeps advertising after you leave — is invisible from the screen you land on.
     */
    private fun backdropLabel(theme: BackdropTheme): String = getString(when (theme) {
        BackdropTheme.DYNAMIC   -> R.string.backdrop_dynamic
        BackdropTheme.PROJECTOR -> R.string.backdrop_projector
        BackdropTheme.BLACK     -> R.string.backdrop_black
    })

    private fun backdropDetail(theme: BackdropTheme): String = getString(when (theme) {
        BackdropTheme.DYNAMIC   -> R.string.backdrop_dynamic_detail
        BackdropTheme.PROJECTOR -> R.string.backdrop_projector_detail
        BackdropTheme.BLACK     -> R.string.backdrop_black_detail
    })

    private fun showBackdropTheme(theme: BackdropTheme) {
        textBackdropThemeValue.text = backdropLabel(theme)
    }

    private fun pickBackdropTheme() {
        val options = BackdropTheme.entries
        val labels = options.map { "${backdropLabel(it)}\n${backdropDetail(it)}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_backdrop)
            .setItems(labels) { _, which ->
                val theme = options[which]
                save { it.copy(backdropTheme = theme) }
                showBackdropTheme(theme)
                Logger.i("Backdrop set to $theme")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickBackAction() {
        val options = BackAction.entries
        val labels = options.map { "${backActionLabel(it)}\n${backActionDetail(it)}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_back_action)
            .setItems(labels) { _, which ->
                val action = options[which]
                save { it.copy(backAction = action) }
                showBackAction(action)
                Logger.i("Back action set to $action")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAudioDelay(ms: Int) {
        textAudioDelayValue.text = if (ms == 0) getString(R.string.setting_audio_delay_none) else "+$ms ms"
    }

    /**
     * Lets the user trim A/V sync by ear.
     *
     * The sender's requested latency is honoured automatically, but the residual offset depends on
     * the sender, the codec and the output path — Bluetooth in particular adds its own delay that
     * no amount of protocol correctness can predict. A dial beats a guessed constant.
     */
    private fun showAudioBuffer(ms: Int) {
        textAudioBufferValue.text =
            if (ms == AppSettings.DEFAULT_AUDIO_BUFFER_MS) getString(R.string.setting_audio_buffer_default, ms)
            else "$ms ms"
    }

    /**
     * Sets the AudioTrack hardware buffer.
     *
     * This is a real trade, not a "bigger is better" dial: the buffer and the packet queue are both
     * charged against the latency the sender asks for, so every millisecond added here is taken off
     * the queue, and the delay you hear barely moves. What changes is WHICH failure you get -- a
     * small buffer clicks when the CPU is busy, a large one leaves the queue too shallow to ride out
     * Wi-Fi jitter. Restart is required because the buffer is sized once when the track is created.
     */
    private fun pickAudioBuffer() {
        val labels = AppSettings.AUDIO_BUFFER_CHOICES.map {
            if (it == AppSettings.DEFAULT_AUDIO_BUFFER_MS) getString(R.string.setting_audio_buffer_default, it)
            else "$it ms"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_audio_buffer)
            .setItems(labels) { _, which ->
                val ms = AppSettings.AUDIO_BUFFER_CHOICES[which]
                save { it.copy(audioBufferMs = ms) }
                showAudioBuffer(ms)
                Logger.i("Audio buffer set to $ms ms — restarting receivers to apply")
                ServiceController.restart(requireContext())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickAudioDelay() {
        val labels = AUDIO_DELAY_CHOICES.map {
            if (it == 0) getString(R.string.setting_audio_delay_none) else "+$it ms"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_audio_delay)
            .setItems(labels) { _, which ->
                val ms = AUDIO_DELAY_CHOICES[which]
                save { it.copy(audioDelayMs = ms) }
                showAudioDelay(ms)
                Logger.i("Audio delay set to $ms ms — restarting receivers to apply")
                ServiceController.restart(requireContext())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Fully stops PhairPlay: receivers down, service stopped, task removed.
     *
     * Confirmed first because it is not obvious from outside that closing the app normally leaves the
     * receiver advertising — that is the whole point of the foreground service — so a user who wants
     * it actually off has no other way to get there.
     */
    private fun confirmQuit() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_quit)
            .setMessage(R.string.setting_quit_confirm)
            .setPositiveButton(R.string.setting_quit) { _, _ ->
                Logger.i("User quit — stopping service and finishing task")
                ServiceController.stop(requireContext())
                requireActivity().finishAndRemoveTask()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Resets all settings to defaults, behind a confirmation.
     *
     * This wipes onboardingComplete too, so the next launch replays the whole first-run flow — an
     * expensive thing to trigger by accidentally pressing OK on a focused row.
     */
    private fun confirmResetSettings() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_reset_defaults)
            .setMessage(R.string.setting_reset_confirm)
            .setPositiveButton(R.string.setting_reset_defaults) { _, _ -> resetSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Summarises the configured shortcuts for the settings row. */
    private fun describeInputApps(packages: List<String>): String {
        val configured = packages.count { it.isNotBlank() }
        return if (configured == 0) getString(R.string.setting_input_apps_none) else "$configured"
    }

    /**
     * Step one of assigning a shortcut: which of the slots to change.
     *
     * Slots rather than a free list because each one is a distinct HomeKit input identifier — slot 2
     * is a different entry in the Home app's input list from slot 1, and reordering them would point
     * an already-configured input at a different app.
     */
    private fun showInputAppSlotDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val current = settingsRepository.settingsFlow.first().inputApps
            val labels = (0 until AppSettings.INPUT_APP_SLOTS).map { slot ->
                val pkg = current.getOrNull(slot).orEmpty()
                val app = if (pkg.isBlank()) getString(R.string.setting_input_apps_none) else appLabel(pkg)
                "${getString(R.string.setting_input_apps_slot, slot + 1)}: $app"
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.setting_input_apps)
                .setItems(labels.toTypedArray()) { _, slot -> showInputAppPickerDialog(slot) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Step two: which app that slot should open. */
    private fun showInputAppPickerDialog(slot: Int) {
        val apps = launchableApps()
        // "None" first so clearing a slot is always the same gesture regardless of how many apps
        // are installed.
        val labels = listOf(getString(R.string.setting_input_apps_clear)) + apps.map { it.second }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.setting_input_apps_slot, slot + 1))
            .setItems(labels.toTypedArray()) { _, index ->
                val pkg = if (index == 0) "" else apps[index - 1].first
                assignInputApp(slot, pkg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Everything on this device with a TV launcher entry, by package and display name.
     *
     * LEANBACK_LAUNCHER rather than the phone launcher category: on a Fire TV that is the set of
     * things the user can actually see and open, and it excludes the background services and
     * phone-only apps that would otherwise flood the list.
     *
     * The lint warning is suppressed because the `<queries>` declaration it asks for IS in the
     * manifest, matching these two intents exactly; lint cannot tie a dynamically built Intent back
     * to it. Removing the manifest block would silently empty this list on Android 11+, so the
     * suppression covers the check, not the requirement.
     */
    @android.annotation.SuppressLint("QueryPermissionsNeeded")
    private fun launchableApps(): List<Pair<String, String>> {
        val pm = requireContext().packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        val leanback = pm.queryIntentActivities(intent, 0)
        // BOTH categories, unioned -- this used to fall back to CATEGORY_LAUNCHER only when the
        // leanback query came back completely empty. Sideloaded apps routinely ship a plain
        // LAUNCHER entry and no leanback one (Apple Music TV is exactly this), so as long as ONE
        // proper TV app was installed the list looked healthy while silently omitting every
        // sideloaded app the user most likely wanted a shortcut to.
        val plain = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
            0,
        )
        return (leanback + plain)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != requireContext().packageName }   // opening ourselves is not a shortcut
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun assignInputApp(slot: Int, packageName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update { settings ->
                // Pad to a fixed length so a value can be written into any slot, including one past
                // the end of a shorter saved list.
                val slots = MutableList(AppSettings.INPUT_APP_SLOTS) { settings.inputApps.getOrNull(it).orEmpty() }
                slots[slot] = packageName
                settings.copy(inputApps = slots)
            }
            textInputAppsValue.text =
                describeInputApps(settingsRepository.settingsFlow.first().inputApps)
            // The HomeKit accessory database is built at startup, so a new shortcut only appears in
            // the Home app after the receiver restarts.
            ServiceController.restart(requireContext())
            Logger.i("HomeKit shortcut slot ${slot + 1} set to ${packageName.ifBlank { "none" }} — restarting")
        }
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun resetSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.resetToDefaults()
            // Pairings are state the user configured just as much as any toggle, and they live in
            // their own stores rather than in the settings DataStore — so clearing the DataStore
            // alone left the device still joined to a Home and still trusting old senders, which is
            // not what "reset to defaults" says on the tin.
            com.phairplay.homekit.HapStore(requireContext()).reset()
            com.phairplay.airplay.handshake.PairingStore(requireContext()).clearAll()
            val defaults = AppSettings.DEFAULT
            populateUI(defaults)
            // Everything the receivers read at startup just changed underneath them.
            ServiceController.restart(requireContext())
            Logger.i("Settings reset to defaults — pairings cleared, receivers restarting")
        }
    }

    private companion object {
        /** Shortcut list order: the nav item and the section heading it scrolls to. */
        val CATEGORY_IDS = listOf(
            R.id.cat_connection   to R.id.section_connection,
            R.id.cat_homekit      to R.id.section_homekit,
            R.id.cat_mirroring    to R.id.section_mirroring,
            R.id.cat_nowplaying   to R.id.section_nowplaying,
            R.id.cat_system       to R.id.section_system,
        )

        /** Breathing room above a section heading once scrolled to, so it is not flush at the top. */
        const val SECTION_TOP_GAP_PX = 24

        val SCREENSAVER_TIMEOUT_CHOICES = listOf(1, 2, 5, 10, 15, 30, 60)

        /** A/V sync trim options, in milliseconds added to the sender's requested latency. */
        val AUDIO_DELAY_CHOICES = listOf(0, 250, 500, 750, 1000, 1250, 1500, 2000, 2500, 3000)

        /** Connect and read timeout for the update download. */
        private const val UPDATE_TIMEOUT_MS = 15_000
        // 350 is here because it is AvTrim.BLUETOOTH_SEED_BEAT_MS: a seeded value the picker cannot
        // display as selected is a value the user cannot get back after changing it.
    }

    /**
     * Shows the pairing code under the HomeKit row, or why there isn't one.
     *
     * The code comes from the running service rather than from settings: it is generated once and
     * stored with the accessory identity, and showing a code the accessory is not currently
     * advertising would send the user to type digits that cannot work.
     */
    private fun renderHomeKitStatus(enabled: Boolean) {
        val subtitle = rowHomeKit.findViewById<TextView>(R.id.text_setting_subtitle) ?: return
        rowHomeKitReset.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) {
            subtitle.setText(R.string.setting_homekit_subtitle)
            return
        }
        val service = (activity as? MainActivity)?.boundService
        val code = service?.homeKitSetupCode()
        subtitle.text = when {
            code == null -> getString(R.string.setting_homekit_subtitle)
            service.isHomeKitPaired() -> getString(R.string.setting_homekit_paired)
            else -> getString(R.string.setting_homekit_code, code)
        }
    }

    override fun onResume() {
        super.onResume()
        // Granting either permission means leaving for a system screen and returning, so the status
        // has to be re-read here — reading it once at bind shows a stale value at the exact moment
        // the user comes back to check whether it worked.
        renderPermissionStatus()
        // Pick the install back up. The user left for the unknown-sources screen mid-update, and
        // making them run the whole check-and-download again after granting it is the kind of
        // small indignity that makes a feature feel broken. The APK is still in the cache dir.
        if (restoreUpdateFocus) {
            restoreUpdateFocus = false
            // Posted, not immediate: focus cannot land on a row the list has not measured yet, and
            // on return from another activity this runs before layout. requestRectangleOnScreen
            // does the scrolling -- requestFocus alone leaves the row focused off-screen when the
            // container has not settled.
            rowUpdate.post {
                rowUpdate.requestFocus()
                rowUpdate.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, rowUpdate.width, rowUpdate.height), true)
            }
        }
        val resume = pendingUpdateIntent
        if (resume != null &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            requireContext().packageManager.canRequestPackageInstalls()
        ) {
            pendingUpdateIntent = null
            Logger.i("Update: unknown-sources granted — resuming the install")
            runCatching { startActivity(resume) }
                .onFailure { Logger.w("No installer on this device — ${it.message}") }
        }
    }

    /** Install intent parked while the user grants unknown-sources. Cleared once it is launched. */
    private var pendingUpdateIntent: android.content.Intent? = null

    /** Set when we send the user to a system screen from the update row, so focus can come back. */
    private var restoreUpdateFocus = false

    /**
     * Shows whether each privileged capability is currently granted.
     *
     * Refreshed from [onResume] rather than once at bind: granting either one means leaving for a
     * system screen and coming back, so a value read at bind time is stale exactly when the user is
     * looking at it.
     */
    private fun renderPermissionStatus() {
        val ctx = context ?: return
        textPermRemoteValue.setText(
            if (SystemPermissions.isAccessibilityGranted(ctx)) R.string.setting_perm_on
            else R.string.setting_perm_off,
        )
        textPermOverlayValue.setText(
            if (SystemPermissions.isOverlayGranted(ctx)) R.string.setting_perm_on
            else R.string.setting_perm_off,
        )
        textPermSleepValue.setText(
            if (SystemPermissions.isDeviceAdminGranted(ctx)) R.string.setting_perm_on
            else R.string.setting_perm_off,
        )
    }

    /**
     * Sends the user to the system Accessibility screen to enable PhairPlay's service.
     *
     * There is no self-grant for this one: switching an accessibility service on requires
     * WRITE_SECURE_SETTINGS, which is signature-level. Opening the screen is the whole of what an
     * app may do, and some Fire OS builds do not list third-party services there at all — so the
     * adb command stays reachable rather than being replaced by a button that might lead nowhere.
     */
    private fun grantRemoteControl() {
        val ctx = context ?: return
        if (SystemPermissions.isAccessibilityGranted(ctx)) {
            showPermissionInfo(
                R.string.setting_perm_remote,
                getString(R.string.setting_perm_remote_granted),
            )
            return
        }
        // Fire TV does not list third-party accessibility services in its Settings menu at all, so
        // opening that screen sends the user somewhere PhairPlay will never appear. Show the adb
        // command as the primary instruction on Fire TV and offer the screen only as a second
        // option, rather than the other way round.
        AlertDialog.Builder(ctx)
            .setTitle(R.string.setting_perm_remote)
            .setMessage(
                getString(
                    R.string.setting_perm_remote_adb,
                    SystemPermissions.accessibilityAdbCommand(ctx),
                ),
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.setting_perm_open_settings) { _, _ ->
                SystemPermissions.openAccessibilitySettings(ctx)
            }
            .show()
    }

    /**
     * Sends the user to "Display over other apps" so the remote can draw its own focus ring.
     *
     * Unlike the accessibility service this one IS reachable from a normal system screen, and the
     * package-scoped Intent lands on PhairPlay's own entry rather than an alphabetical list of every
     * installed app — which on a TV, navigated by D-pad, is the difference between an instruction
     * someone follows and one they abandon.
     */
    private fun grantOverlay() {
        val ctx = context ?: return
        if (SystemPermissions.isOverlayGranted(ctx)) {
            showPermissionInfo(
                R.string.setting_perm_overlay,
                getString(R.string.setting_perm_overlay_granted),
            )
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.setting_perm_overlay)
            .setMessage(R.string.setting_perm_overlay_explanation)
            .setPositiveButton(R.string.setting_perm_open_settings) { _, _ ->
                SystemPermissions.openOverlaySettings(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Asks for device admin so HomeKit "off" can actually blank the display.
     *
     * This one WAS self-grantable the whole time. PhairPlay told users to run `dpm set-active-admin`
     * from a computer for a permission that ACTION_ADD_DEVICE_ADMIN grants with one button on the
     * device.
     */
    /**
     * Asks GitHub for the latest release and offers to install it.
     *
     * Only ever runs on a click. There is no background check and no automatic install: a sideloaded
     * app that silently replaces itself on someone's television is not a feature.
     */
    private fun checkForUpdate() {
        val ctx = context ?: return
        textUpdateValue.setText(R.string.setting_update_checking)
        rowUpdate.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // The beta channel is compared by COMMIT, the stable one by version — see UpdateChecker.
            val result = if (betaUpdates) {
                com.phairplay.util.UpdateChecker.checkBeta(BuildConfig.GIT_SHA, BuildConfig.FLAVOR)
            } else {
                com.phairplay.util.UpdateChecker.check(BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
            }
            rowUpdate.isEnabled = true
            when (result) {
                is com.phairplay.util.UpdateChecker.Result.UpToDate ->
                    // With the build SHA, not just the tag. The version name does not move between
                    // dev builds, so "Up to date (1.0.0)" is true of every build ever made and tells
                    // you nothing about which one is on the television.
                    textUpdateValue.text =
                        getString(R.string.setting_update_uptodate, result.tag, BuildConfig.GIT_SHA)

                is com.phairplay.util.UpdateChecker.Result.Failed -> {
                    // Shown, not swallowed -- "Check failed" with no reason is what makes people
                    // assume the app is broken rather than that the network is.
                    textUpdateValue.setText(R.string.setting_update_failed)
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.setting_update)
                        .setMessage(result.reason)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }

                is com.phairplay.util.UpdateChecker.Result.Available -> {
                    textUpdateValue.text =
                        getString(R.string.setting_update_available, result.tag, BuildConfig.GIT_SHA)
                    val asset = result.assetUrl
                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle(getString(R.string.setting_update_available, result.tag, BuildConfig.GIT_SHA))
                        .setMessage(
                            if (asset == null) getString(R.string.setting_update_no_asset, result.tag)
                            else result.notes,
                        )
                    if (asset == null) {
                        dialog.setPositiveButton(android.R.string.ok, null)
                    } else {
                        dialog.setPositiveButton(R.string.setting_update_install) { _, _ ->
                            installUpdate(asset)
                        }
                        dialog.setNegativeButton(android.R.string.cancel, null)
                    }
                    dialog.show()
                }
            }
        }
    }

    /**
     * Downloads [url] and returns an install Intent for it.
     *
     * Lives here rather than in UpdateChecker because FileProvider is AndroidX, and UpdateChecker is
     * compiled by the JVM protocol test module, which has no AndroidX on its classpath.
     *
     * @return null if the download failed, so the caller says so rather than opening an installer
     *   for a half-written file.
     */
    private suspend fun downloadUpdate(context: android.content.Context, url: String): android.content.Intent? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val target = java.io.File(context.cacheDir, "update.apk")
                (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = UPDATE_TIMEOUT_MS
                    readTimeout = UPDATE_TIMEOUT_MS
                }.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }

                // THE AUTHORITY IS INTERPOLATED, and it was not.
                //
                // This read "${'$'}{context.packageName}.updates" -- the Kotlin idiom for a LITERAL
                // dollar sign -- so the authority handed to FileProvider was the source text
                // itself rather than "com.phairplay.firetv.updates". getUriForFile then threw
                // "couldn't find meta-data for provider with authority ...", the download was
                // reported as failed, and the log line below carried the same escape, so it
                // printed the template instead of the exception and said nothing at all.
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.updates", target)
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                    )
                }
            }.getOrElse {
                Logger.w("Update download failed — ${it.javaClass.simpleName}: ${it.message}")
                null
            }
        }

    private fun installUpdate(assetUrl: String) {
        val ctx = context ?: return
        textUpdateValue.setText(R.string.setting_update_downloading)
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = downloadUpdate(ctx, assetUrl)
            if (intent == null) {
                textUpdateValue.setText(R.string.setting_update_failed)
                showPermissionInfo(
                    R.string.setting_update,
                    getString(R.string.setting_update_download_failed),
                )
                return@launch
            }
            // ASK FOR THE UNKNOWN-SOURCES GRANT OURSELVES.
            //
            // The assumption here was that the system installer would prompt for it on the way
            // past. Since Android 8 it does not: the grant is per-app, and an app that does not
            // hold it gets its install intent dropped with no dialog and nothing in the log --
            // which reads exactly like the update silently doing nothing. Declaring
            // REQUEST_INSTALL_PACKAGES in the manifest, which we do, only makes the grant
            // *available* to ask for; it does not ask.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !ctx.packageManager.canRequestPackageInstalls()
            ) {
                Logger.i("Update: unknown-sources not granted — sending the user to grant it")
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.setting_update)
                    .setMessage(R.string.setting_update_needs_install_permission)
                    .setPositiveButton(R.string.onboarding_grant) { _, _ ->
                        // Carries our package, so the settings screen lands on PhairPlay's own
                        // toggle rather than the full list of installed apps.
                        val grant = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                        )
                        // Fire OS does not always carry the per-app screen. Fall back to the
                        // global one rather than throwing ActivityNotFoundException at the user.
                        runCatching { startActivity(grant) }.onFailure {
                            runCatching {
                                startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                            }.onFailure { e -> Logger.w("No unknown-sources screen — ${e.message}") }
                        }
                        pendingUpdateIntent = intent
                        // Remember where the user was. Coming back from a system screen rebuilds
                        // the list at the top, and Check for updates is the LAST row on the page --
                        // so without this the reward for granting the permission is scrolling the
                        // whole way down again to press the same button.
                        restoreUpdateFocus = true
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                textUpdateValue.setText(R.string.setting_update_needs_permission_short)
                return@launch
            }
            runCatching { startActivity(intent) }
                .onFailure { Logger.w("No installer on this device — ${it.message}") }
        }
    }

    private fun grantSleep() {
        val ctx = context ?: return
        if (SystemPermissions.isDeviceAdminGranted(ctx)) {
            // Offer to hand it back. Granting device admin blocks uninstall, and the failure a user
            // hits then (DELETE_FAILED_DEVICE_POLICY_MANAGER) does not mention PhairPlay at all.
            AlertDialog.Builder(ctx)
                .setTitle(R.string.setting_perm_sleep)
                .setMessage(R.string.setting_perm_sleep_granted)
                .setPositiveButton(R.string.setting_perm_revoke) { _, _ ->
                    SystemPermissions.revokeDeviceAdmin(ctx)
                    renderPermissionStatus()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val explanation = getString(R.string.setting_perm_sleep_explanation)
        if (!SystemPermissions.requestDeviceAdmin(ctx, explanation)) {
            showPermissionInfo(
                R.string.setting_perm_sleep,
                getString(
                    R.string.setting_perm_no_screen,
                    SystemPermissions.deviceAdminAdbCommand(ctx),
                ),
            )
        }
    }

    private fun showPermissionInfo(titleRes: Int, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmResetHomeKit() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_homekit_reset)
            .setMessage(R.string.setting_homekit_reset_subtitle)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                (activity as? MainActivity)?.boundService?.resetHomeKitPairings()
                renderHomeKitStatus(enabled = true)
                // Resetting leaves the accessory unpaired and discoverable again, which is exactly
                // the state the setup flow exists for. Dropping the user back on a settings list
                // with a silently-changed subtitle made the reset look like it had done nothing.
                openHomeKitSetup()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opens the guided pairing flow, straight to the code page — the question is already answered. */
    private fun openHomeKitSetup() {
        (activity as? MainActivity)?.showHomeKitSetup(startAtCode = true)
    }
}
