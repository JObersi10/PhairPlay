package com.phairplay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.R
import com.phairplay.settings.AppSettings
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * OnboardingFragment — the first-run flow.
 *
 * WHY: PhairPlay's permissions are not self-explanatory, and two of them silently break features
 * when missing. Location is required only because Android gates Wi-Fi Direct discovery behind it,
 * which looks alarming without an explanation. `SYSTEM_ALERT_WINDOW` cannot be granted from a
 * dialog at all on Fire TV — it needs a trip into system settings — and without it auto-open
 * degrades to a notification the user has to click, with no hint as to why.
 *
 * Walking through them once, with a reason attached to each, turns three invisible failure modes
 * into an explicit choice.
 *
 * HOW: four pages driven by [page]; [render] rebuilds the body on each step. Completion is recorded
 * in [com.phairplay.settings.AppSettings.onboardingComplete] so this never shows twice.
 *
 * Art and screen recordings can be dropped into [mediaSlot] later without touching the flow.
 */
class OnboardingFragment : Fragment() {

    /** Called when the flow is finished or skipped, so the host can show the normal UI. */
    var onFinished: (() -> Unit)? = null

    private lateinit var settingsRepository: SettingsRepository

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var mediaSlot: LinearLayout
    private lateinit var itemsContainer: LinearLayout
    private lateinit var stepLabel: TextView
    private lateinit var primaryButton: Button
    private lateinit var skipButton: Button

    private var page = 0

    /**
     * Working copy of the user's settings for the preferences page. Held as a draft and written once
     * on finish so backing out mid-flow doesn't leave half-applied changes behind.
     */
    private var draft: AppSettings? = null

    /**
     * Live handles to the option rows on the preferences page.
     *
     * Kept so a toggle can repaint the affected rows instead of tearing down and rebuilding the whole
     * page. The rebuild was both the lag and the reason the highlight seemed to jump around: new views
     * meant focus was lost every time, and render() then pulled it back to the Next button.
     */
    private val prefRows = mutableListOf<PrefRow>()

    private class PrefRow(
        val row: View,
        val glyph: TextView,
        val label: TextView,
        val isCheckbox: Boolean,
        val isSelected: () -> Boolean,
    )

    /** Page currently laid out, so render() only steals focus when the page actually changes. */
    private var renderedPage = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = buildRoot(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsRepository = SettingsRepository(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            draft = settingsRepository.settingsFlow.first()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // Only the permission pages can change behind our back (the user was just in system settings
        // granting the overlay). Re-rendering the preferences page here would throw away the draft
        // rows for no reason and cost another full layout pass.
        if (page == PAGE_REQUIRED || page == PAGE_OPTIONAL) render()
    }

    // ─── Layout ──────────────────────────────────────────────────────────────

    private fun buildRoot(ctx: Context): View {
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.background_dark))
            isFillViewport = true
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(48), dp(64), dp(48))
        }

        stepLabel = TextView(ctx).apply {
            setTextColor(Color.argb(120, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.24f
        }
        titleView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = lp().also { it.topMargin = dp(10); it.bottomMargin = dp(12) }
        }
        bodyView = TextView(ctx).apply {
            setTextColor(Color.argb(190, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        // Reserved for a screenshot or short recording per page; empty for now so the flow works.
        mediaSlot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp().also { it.topMargin = dp(16) }
            visibility = View.GONE
        }
        itemsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp().also { it.topMargin = dp(24) }
        }

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp().also { it.topMargin = dp(32) }
        }
        primaryButton = Button(ctx).apply {
            isAllCaps = false
            // Focus has to be visible on the buttons too — on the welcome and permission pages they
            // are the only focusable things, so a static white pill left the user with no idea where
            // the highlight was.
            background = buttonBackground(primary = true)
            setTextColor(Color.BLACK)
            setPadding(dp(28), 0, dp(28), 0)
            setOnClickListener { advance() }
            setOnFocusChangeListener { v, hasFocus ->
                (v as Button).setTextColor(if (hasFocus) Color.WHITE else Color.BLACK)
            }
        }
        skipButton = Button(ctx).apply {
            isAllCaps = false
            text = getString(R.string.onboarding_skip)
            setTextColor(Color.argb(150, 255, 255, 255))
            background = buttonBackground(primary = false)
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = dp(12) }
            setOnClickListener { finish() }
        }
        buttonRow.addView(primaryButton)
        buttonRow.addView(skipButton)

        root.addView(stepLabel)
        root.addView(titleView)
        root.addView(bodyView)
        root.addView(mediaSlot)
        root.addView(itemsContainer)
        root.addView(buttonRow)
        scroll.addView(root)
        return scroll
    }

    // ─── Pages ───────────────────────────────────────────────────────────────

    private fun render() {
        if (!isAdded) return
        itemsContainer.removeAllViews()
        prefRows.clear()
        stepLabel.text = "STEP ${page + 1} OF $PAGE_COUNT"

        when (page) {
            PAGE_WELCOME -> {
                titleView.setText(R.string.onboarding_welcome_title)
                bodyView.setText(R.string.onboarding_welcome_body)
                primaryButton.setText(R.string.onboarding_next)
            }
            PAGE_REQUIRED -> {
                titleView.setText(R.string.onboarding_required_title)
                bodyView.setText(R.string.onboarding_required_body)
                primaryButton.setText(R.string.onboarding_next)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    addPermissionRow(
                        R.string.onboarding_perm_notifications,
                        R.string.onboarding_perm_notifications_why,
                        granted = hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                    ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ) }
                }
                addPermissionRow(
                    R.string.onboarding_perm_location,
                    R.string.onboarding_perm_location_why,
                    granted = hasWifiDirectPermission(),
                ) { requestPermissions(wifiDirectPermissions(), REQ) }
            }
            PAGE_OPTIONAL -> {
                titleView.setText(R.string.onboarding_optional_title)
                bodyView.setText(R.string.onboarding_optional_body)
                primaryButton.setText(R.string.onboarding_next)
                addPermissionRow(
                    R.string.onboarding_perm_overlay,
                    R.string.onboarding_perm_overlay_why,
                    granted = canDrawOverlays(),
                    actionLabelRes = R.string.onboarding_open_settings,
                ) { openOverlaySettings() }
            }
            PAGE_PREFS -> {
                titleView.setText(R.string.onboarding_prefs_title)
                bodyView.setText(R.string.onboarding_prefs_body)
                primaryButton.setText(R.string.onboarding_next)
                renderPrefs()
            }
            PAGE_READY -> {
                titleView.setText(R.string.onboarding_ready_title)
                bodyView.setText(R.string.onboarding_ready_body)
                primaryButton.setText(R.string.onboarding_done)
                skipButton.visibility = View.GONE
            }
        }
        // Only pull focus on a genuine page change; doing it on every repaint fought the user for
        // control of the highlight.
        if (renderedPage != page) {
            renderedPage = page
            primaryButton.requestFocus()
        }
    }

    /**
     * The preferences page: the three decisions worth making up front. Everything here is also in
     * Settings, so this is about surfacing the choices rather than being the only way to make them.
     */
    private fun renderPrefs() {
        val d = draft ?: return

        addSectionLabel(R.string.onboarding_prefs_pin)
        // Three states rather than a toggle, because "PIN on" hides a real trade-off: asked once and
        // remembered is receiver-level trust, asked every time is the strict reading.
        addChoiceRow(
            R.string.onboarding_prefs_pin_no,
            isSelectedProvider = { draft?.airPlayPinAuthEnabled == false },
        ) { updateDraft { it.copy(airPlayPinAuthEnabled = false) } }
        addChoiceRow(
            R.string.onboarding_prefs_pin_remember,
            isSelectedProvider = {
                draft?.let { it.airPlayPinAuthEnabled && it.rememberPinPairing } == true
            },
        ) { updateDraft { it.copy(airPlayPinAuthEnabled = true, rememberPinPairing = true) } }
        addChoiceRow(
            R.string.onboarding_prefs_pin_always,
            isSelectedProvider = {
                draft?.let { it.airPlayPinAuthEnabled && !it.rememberPinPairing } == true
            },
        ) { updateDraft { it.copy(airPlayPinAuthEnabled = true, rememberPinPairing = false) } }

        addSectionLabel(R.string.onboarding_prefs_receivers)
        addChoiceRow(
            R.string.protocol_airplay, isCheckbox = true,
            isSelectedProvider = { draft?.airPlayEnabled == true },
        ) { updateDraft { it.copy(airPlayEnabled = !it.airPlayEnabled) } }
        addChoiceRow(
            R.string.protocol_miracast, isCheckbox = true,
            isSelectedProvider = { draft?.miracastEnabled == true },
        ) { updateDraft { it.copy(miracastEnabled = !it.miracastEnabled) } }
        addChoiceRow(
            R.string.protocol_dlna, isCheckbox = true,
            isSelectedProvider = { draft?.dlnaEnabled == true },
        ) { updateDraft { it.copy(dlnaEnabled = !it.dlnaEnabled) } }

        addSectionLabel(R.string.onboarding_prefs_screensaver)
        addChoiceRow(
            R.string.setting_screensaver, isCheckbox = true,
            isSelectedProvider = { draft?.screensaverEnabled == true },
        ) { updateDraft { it.copy(screensaverEnabled = !it.screensaverEnabled) } }

        addSectionLabel(R.string.onboarding_prefs_quality)
        addChoiceRow(
            R.string.setting_force_high_res, isCheckbox = true,
            isSelectedProvider = { draft?.forceHighResolution == true },
        ) { updateDraft { it.copy(forceHighResolution = !it.forceHighResolution) } }
    }

    private fun updateDraft(transform: (AppSettings) -> AppSettings) {
        draft = draft?.let(transform)
        // Repaint only — rebuilding the page here was the lag, and it dropped focus on every press.
        refreshPrefRows()
    }

    /**
     * Background that turns blue on focus.
     *
     * A TV has no cursor, so the focused row is the only thing telling the user where they are — and
     * a translucent-white fill on a dark card was nearly invisible against the unselected rows.
     */
    /** Button background that goes blue on focus, matching the option rows. */
    private fun buttonBackground(primary: Boolean): Drawable {
        val focused = GradientDrawable().apply {
            setColor(FOCUS_BLUE); cornerRadius = dp(10).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(if (primary) Color.WHITE else Color.argb(40, 255, 255, 255))
            cornerRadius = dp(10).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun focusableBackground(selected: Boolean): Drawable {
        val focused = GradientDrawable().apply {
            setColor(FOCUS_BLUE); cornerRadius = dp(10).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(Color.argb(if (selected) 46 else 20, 255, 255, 255))
            cornerRadius = dp(10).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun addSectionLabel(res: Int) {
        itemsContainer.addView(TextView(requireContext()).apply {
            setText(res)
            setTextColor(Color.argb(115, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.16f
            layoutParams = lp().also { it.topMargin = dp(14); it.bottomMargin = dp(8) }
        })
    }

    /**
     * A focusable option row. [isCheckbox] only changes the glyph — radio for one-of-many, tick for
     * independent switches — so a TV remote sees one consistent widget either way.
     */
    private fun addChoiceRow(
        labelRes: Int,
        isCheckbox: Boolean = false,
        isSelectedProvider: () -> Boolean,
        onClick: () -> Unit,
    ) {
        val ctx = requireContext()
        val selected = isSelectedProvider()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = focusableBackground(selected)
            isFocusable = true
            layoutParams = lp().also { it.bottomMargin = dp(8) }
            setOnClickListener { onClick() }
        }
        val glyph = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val label = TextView(ctx).apply {
            setText(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        row.addView(glyph)
        row.addView(label)
        itemsContainer.addView(row)

        val handle = PrefRow(row, glyph, label, isCheckbox, isSelectedProvider)
        prefRows += handle
        paintPrefRow(handle)
    }

    /** Applies the current selection state to one row without recreating any views. */
    private fun paintPrefRow(r: PrefRow) {
        val on = r.isSelected()
        r.glyph.text = if (on) (if (r.isCheckbox) "☑" else "●") else (if (r.isCheckbox) "☐" else "○")
        r.glyph.setTextColor(if (on) Color.WHITE else Color.argb(120, 255, 255, 255))
        r.label.setTextColor(if (on) Color.WHITE else Color.argb(180, 255, 255, 255))
        r.row.background = focusableBackground(on)
    }

    private fun refreshPrefRows() = prefRows.forEach { paintPrefRow(it) }

    private fun advance() {
        if (page >= PAGE_COUNT - 1) finish() else { page++; render() }
    }

    private fun finish() {
        viewLifecycleOwner.lifecycleScope.launch {
            val d = draft
            settingsRepository.update { current ->
                (d ?: current).copy(onboardingComplete = true)
            }
            Logger.i("Onboarding complete")
            onFinished?.invoke()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ─── Rows ────────────────────────────────────────────────────────────────

    /**
     * One permission: what it is, why we want it, and a button that either grants it or says it is
     * already granted. The "why" is not decoration — location in particular looks unreasonable for a
     * screen-mirroring app until you explain that Android, not PhairPlay, is the one demanding it.
     */
    private fun addPermissionRow(
        labelRes: Int,
        whyRes: Int,
        granted: Boolean,
        actionLabelRes: Int = R.string.onboarding_grant,
        onGrant: () -> Unit,
    ) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = focusableBackground(selected = false)
            layoutParams = lp().also { it.bottomMargin = dp(12) }
        }
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(ctx).apply {
            setText(labelRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        text.addView(TextView(ctx).apply {
            setText(whyRes)
            setTextColor(Color.argb(140, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = lp().also { it.topMargin = dp(4); it.rightMargin = dp(16) }
        })
        row.addView(text)

        if (granted) {
            row.addView(TextView(ctx).apply {
                setText(R.string.onboarding_granted)
                setTextColor(Color.parseColor("#7BD88F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
        } else {
            row.addView(Button(ctx).apply {
                isAllCaps = false
                setText(actionLabelRes)
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(20), 0, dp(20), 0)
                setOnClickListener { onGrant() }
            })
        }
        itemsContainer.addView(row)
    }

    // ─── Permission helpers ──────────────────────────────────────────────────

    private fun hasPermission(name: String) =
        ContextCompat.checkSelfPermission(requireContext(), name) == PackageManager.PERMISSION_GRANTED

    /**
     * Wi-Fi Direct moved off location in API 33. Below that, `ACCESS_FINE_LOCATION` is the only way
     * to satisfy the P2P APIs — see the matching split in MainActivity.
     */
    private fun wifiDirectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasWifiDirectPermission() = wifiDirectPermissions().any { hasPermission(it) }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(requireContext())

    /**
     * Fire TV has no in-app path to the overlay grant, so this hands off to system settings. Some
     * Fire OS builds don't expose the per-app screen; fall back to the app details page.
     */
    private fun openOverlaySettings() {
        val pkg = requireContext().packageName
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in attempts) {
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
        Logger.w("No settings screen available for the overlay permission on this build")
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_WELCOME = 0
        const val PAGE_REQUIRED = 1
        const val PAGE_OPTIONAL = 2
        const val PAGE_PREFS = 3
        const val PAGE_READY = 4
        const val PAGE_COUNT = 5
        const val REQ = 2001

        /** Matches the nav-panel highlight so focus reads the same everywhere in the app. */
        val FOCUS_BLUE = Color.rgb(26, 115, 232)
    }
}
