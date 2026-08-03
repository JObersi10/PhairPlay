package com.phairplay.ui

import android.app.AlertDialog
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
import com.phairplay.R
import com.phairplay.settings.AppSettings
import com.phairplay.settings.SettingsRepository
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
    private lateinit var headerDisplay: TextView
    private lateinit var headerProtocols: TextView
    private lateinit var headerAirPlay: TextView
    private lateinit var headerNowPlaying: TextView
    private lateinit var headerService: TextView
    private lateinit var headerDeveloper: TextView
    private lateinit var headerAbout: TextView

    // Settings rows
    private lateinit var rowDisplayName: LinearLayout
    private lateinit var textDisplayNameValue: TextView
    private lateinit var rowAirPlay: View
    private lateinit var rowMiracast: View
    private lateinit var rowDlna: View
    private lateinit var rowMirrorAudio: View
    private lateinit var rowPinAuth: View
    private lateinit var rowStartOnBoot: View
    private lateinit var rowDebugOverlay: View
    private lateinit var rowBackQuits: View
    private lateinit var rowBackHome: View
    private lateinit var rowPip: View
    private lateinit var rowBeatPulse: LinearLayout
    private lateinit var textBeatPulseValue: TextView
    private lateinit var rowAudioDelay: LinearLayout
    private lateinit var textAudioDelayValue: TextView
    private lateinit var rowForceHighRes: View
    private lateinit var rowRememberPin: View
    private lateinit var rowForgetPairings: LinearLayout
    private lateinit var rowSenderVolume: LinearLayout
    private lateinit var textSenderVolumeValue: TextView
    private lateinit var rowScreensaver: View
    private lateinit var rowScreensaverTimeout: LinearLayout
    private lateinit var textScreensaverTimeoutValue: TextView
    private lateinit var textVersionValue: TextView
    private lateinit var rowReset: LinearLayout
    private lateinit var rowQuit: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsRepository = SettingsRepository(requireContext())
        bindViews(view)
        setSectionTitles()
        setRowLabels()
        loadAndPopulate()
    }

    // ─── View Binding ────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        // Each header is an <include> of settings_section_header.xml (a bare
        // TextView). The include's android:id IS the TextView's id, so look it up
        // directly — no nested lookup.
        headerDisplay   = view.findViewById(R.id.header_display)
        headerProtocols = view.findViewById(R.id.header_protocols)
        headerAirPlay   = view.findViewById(R.id.header_airplay)
        headerNowPlaying = view.findViewById(R.id.header_now_playing)
        headerService   = view.findViewById(R.id.header_service)
        headerDeveloper = view.findViewById(R.id.header_developer)
        headerAbout     = view.findViewById(R.id.header_about)

        rowDisplayName      = view.findViewById(R.id.row_display_name)
        textDisplayNameValue = view.findViewById(R.id.text_display_name_value)
        rowAirPlay          = view.findViewById(R.id.row_airplay)
        rowMiracast         = view.findViewById(R.id.row_miracast)
        rowDlna             = view.findViewById(R.id.row_dlna)
        rowMirrorAudio      = view.findViewById(R.id.row_mirror_audio)
        rowPinAuth          = view.findViewById(R.id.row_pin_auth)
        rowStartOnBoot      = view.findViewById(R.id.row_start_on_boot)
        rowDebugOverlay     = view.findViewById(R.id.row_debug_overlay)
        rowBackQuits        = view.findViewById(R.id.row_back_quits)
        rowBackHome         = view.findViewById(R.id.row_back_home)
        rowPip              = view.findViewById(R.id.row_pip)
        rowBeatPulse        = view.findViewById(R.id.row_beat_pulse)
        textBeatPulseValue  = view.findViewById(R.id.text_beat_pulse_value)
        rowAudioDelay       = view.findViewById(R.id.row_audio_delay)
        textAudioDelayValue = view.findViewById(R.id.text_audio_delay_value)
        rowForceHighRes     = view.findViewById(R.id.row_force_high_res)
        rowRememberPin      = view.findViewById(R.id.row_remember_pin)
        rowForgetPairings   = view.findViewById(R.id.row_forget_pairings)
        rowSenderVolume     = view.findViewById(R.id.row_sender_volume)
        textSenderVolumeValue = view.findViewById(R.id.text_sender_volume_value)
        rowScreensaver      = view.findViewById(R.id.row_screensaver)
        rowScreensaverTimeout = view.findViewById(R.id.row_screensaver_timeout)
        textScreensaverTimeoutValue = view.findViewById(R.id.text_screensaver_timeout_value)
        textVersionValue    = view.findViewById(R.id.text_version_value)
        rowReset            = view.findViewById(R.id.row_reset)
        rowQuit             = view.findViewById(R.id.row_quit)
    }

    /** Sets all section header titles from string resources. */
    private fun setSectionTitles() {
        headerDisplay.setText(R.string.settings_section_display)
        headerProtocols.setText(R.string.settings_section_protocols)
        headerAirPlay.setText(R.string.settings_section_airplay)
        headerNowPlaying.setText(R.string.settings_section_now_playing)
        headerService.setText(R.string.settings_section_service)
        headerDeveloper.setText(R.string.settings_section_developer)
        headerAbout.setText(R.string.settings_section_about)
    }

    /** Sets all row labels and subtitles from string resources. */
    private fun setRowLabels() {
        configureToggleRow(rowAirPlay,      R.string.setting_airplay_enabled,    R.string.setting_airplay_subtitle)
        configureToggleRow(rowMiracast,     R.string.setting_miracast_enabled,   R.string.setting_miracast_subtitle)
        configureToggleRow(rowDlna,         R.string.setting_dlna_enabled,       R.string.setting_dlna_subtitle)
        configureToggleRow(rowMirrorAudio,  R.string.setting_mirror_audio,       R.string.setting_mirror_audio_subtitle)
        configureToggleRow(rowPinAuth,      R.string.setting_pin_auth,           R.string.setting_pin_auth_subtitle)
        configureToggleRow(rowRememberPin,   R.string.setting_remember_pin,       R.string.setting_remember_pin_subtitle)
        configureToggleRow(rowScreensaver,  R.string.setting_screensaver,        R.string.setting_screensaver_subtitle)
        configureToggleRow(rowStartOnBoot,  R.string.setting_start_on_boot,      0)
        configureToggleRow(rowDebugOverlay, R.string.setting_debug_overlay,      R.string.setting_debug_overlay_subtitle)
        configureToggleRow(rowBackQuits,    R.string.setting_back_quits,         R.string.setting_back_quits_subtitle)
        configureToggleRow(rowBackHome,     R.string.setting_back_home,          R.string.setting_back_home_subtitle)
        configureToggleRow(rowPip,         R.string.setting_pip,                R.string.setting_pip_subtitle)
        configureToggleRow(rowForceHighRes, R.string.setting_force_high_res,      R.string.setting_force_high_res_subtitle)

        textVersionValue.text = BuildConfig.VERSION_NAME
        showNetworkInfo()
    }

    private fun showNetworkInfo() {
        val ip = getWifiIp() ?: return
        val port = com.phairplay.diagnostic.DiagnosticServer.PORT
        val tailPort = com.phairplay.diagnostic.DiagnosticServer.TAIL_PORT
        textVersionValue.text = "${BuildConfig.VERSION_NAME}\nLogs: http://$ip:$port  Tail: http://$ip:$tailPort"
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
        setToggle(rowMirrorAudio,  settings.mirrorAudioEnabled)
        setToggle(rowPinAuth,      settings.airPlayPinAuthEnabled)
        setToggle(rowStartOnBoot,  settings.startOnBoot)
        setToggle(rowDebugOverlay, settings.showDebugOverlay)
        setToggle(rowBackQuits, settings.backQuitsApp)
        setToggle(rowBackHome, settings.backGoesHome)
        setToggle(rowPip, settings.pipEnabled)
        showAudioDelay(settings.audioDelayMs)
        showBeatPulse(settings.beatPulse)
        setToggle(rowForceHighRes, settings.forceHighResolution)
        setToggle(rowRememberPin,  settings.rememberPinPairing)
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
        setToggleListener(rowMirrorAudio)  { enabled -> saveAndRestart { it.copy(mirrorAudioEnabled = enabled) } }
        setToggleListener(rowPinAuth)      { enabled -> saveAndRestart { it.copy(airPlayPinAuthEnabled = enabled) } }
        setToggleListener(rowStartOnBoot)  { enabled -> save { it.copy(startOnBoot = enabled) } }
        setToggleListener(rowDebugOverlay) { enabled -> save { it.copy(showDebugOverlay = enabled) } }
        setToggleListener(rowBackQuits) { enabled -> save { it.copy(backQuitsApp = enabled) } }
        setToggleListener(rowBackHome) { enabled ->
            Logger.i("Back-returns-to-Home toggled to $enabled")
            save { it.copy(backGoesHome = enabled) }
        }
        setToggleListener(rowPip) { enabled -> save { it.copy(pipEnabled = enabled) } }
        rowAudioDelay.setOnClickListener { pickAudioDelay() }
        rowBeatPulse.setOnClickListener { pickBeatPulse() }
        setToggleListener(rowForceHighRes) { enabled -> save { it.copy(forceHighResolution = enabled) } }
        setToggleListener(rowScreensaver)  { enabled -> save { it.copy(screensaverEnabled = enabled) } }
        rowScreensaverTimeout.setOnClickListener { showScreensaverTimeoutDialog() }
        rowSenderVolume.setOnClickListener { showSenderVolumeDialog() }
        setToggleListener(rowRememberPin) { enabled -> saveAndRestart { it.copy(rememberPinPairing = enabled) } }
        rowForgetPairings.setOnClickListener { forgetPairings() }

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
        2 -> R.string.setting_beat_pulse_strong
        3 -> R.string.setting_beat_pulse_insane
        else -> R.string.setting_beat_pulse_normal
    })

    private fun showBeatPulse(level: Int) { textBeatPulseValue.text = beatPulseLabel(level) }

    private fun pickBeatPulse() {
        val labels = arrayOf(beatPulseLabel(1), beatPulseLabel(2), beatPulseLabel(3))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_beat_pulse)
            .setItems(labels) { _, which ->
                val level = which + 1
                save { it.copy(beatPulse = level) }
                showBeatPulse(level)
                Logger.i("Beat pulse set to ${beatPulseLabel(level)}")
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

    private fun resetSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.resetToDefaults()
            val defaults = AppSettings.DEFAULT
            populateUI(defaults)
            Logger.i("Settings reset to defaults")
        }
    }

    private companion object {
        val SCREENSAVER_TIMEOUT_CHOICES = listOf(1, 2, 5, 10, 15, 30, 60)

        /** A/V sync trim options, in milliseconds added to the sender's requested latency. */
        val AUDIO_DELAY_CHOICES = listOf(0, 250, 500, 750, 1000, 1250, 1500, 2000, 2500, 3000)
    }
}
