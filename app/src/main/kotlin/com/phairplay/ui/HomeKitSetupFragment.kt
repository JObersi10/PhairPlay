package com.phairplay.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.MainActivity
import com.phairplay.R
import com.phairplay.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HomeKitSetupFragment — the guided flow for adding PhairPlay to the Home app.
 *
 * Split out of the main onboarding rather than living as one more page in it, for two reasons:
 * HomeKit is genuinely optional and burying it mid-flow made it read as a required step, and the
 * user needs to be able to come BACK here later — after a reset, after switching phones, after
 * removing the accessory from a Home. A settings row that opens a dialog cannot do that; a
 * fragment can.
 *
 * Three pages:
 *
 *   0. Do you want it? — with enough of the answer to decide, not a marketing pitch.
 *   1. The instructions, the QR code, and the numeric code as a fallback.
 *   2. Confirmation, reached automatically the moment pairing completes.
 *
 * Page 1 polls for the paired state rather than waiting for a callback. Pairing finishes on the HAP
 * server's own thread with no path back to the UI, and a poll every second is imperceptible next to
 * how long it takes a person to point a phone at a television.
 */
class HomeKitSetupFragment : Fragment() {

    /** Invoked when the flow ends, however it ends. [enabled] is the user's answer on page 0. */
    var onFinished: ((enabled: Boolean) -> Unit)? = null

    /** Set HomeKit on/off. Supplied by the host so this fragment does not own the setting. */
    var onSetEnabled: ((Boolean) -> Unit)? = null

    /** Start at the code page, skipping the yes/no question — the entry point used after a reset. */
    var startAtCode: Boolean = false

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button

    private var page = PAGE_ASK
    private var renderedPage = -1
    private var pollingStarted = false

    private val service get() = (activity as? MainActivity)?.boundService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        if (startAtCode) page = PAGE_CODE

        val root = ScrollView(ctx).apply {
            setBackgroundColor(Color.rgb(16, 16, 18))
            isFillViewport = true
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }

        titleView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        bodyView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.argb(190, 255, 255, 255))
            setPadding(0, dp(12), 0, dp(20))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        itemsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        primaryButton = Button(ctx).apply {
            setTextColor(Color.WHITE)
            background = buttonBackground(primary = true)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { advance() }
        }
        secondaryButton = Button(ctx).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            background = buttonBackground(primary = false)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { decline() }
        }
        buttonRow.addView(primaryButton)
        buttonRow.addView(secondaryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.leftMargin = dp(12) })

        column.addView(titleView)
        column.addView(bodyView)
        column.addView(itemsContainer)
        column.addView(buttonRow)
        root.addView(column)

        render()
        return root
    }

    // ─── Pages ───────────────────────────────────────────────────────────────

    private fun render() {
        itemsContainer.removeAllViews()
        secondaryButton.visibility = View.VISIBLE

        when (page) {
            PAGE_ASK -> {
                titleView.setText(R.string.hk_ask_title)
                bodyView.setText(R.string.hk_ask_body)
                primaryButton.setText(R.string.hk_ask_yes)
                secondaryButton.setText(R.string.hk_ask_no)
            }
            PAGE_CODE -> {
                titleView.setText(R.string.hk_code_title)
                // The accessory name is the part people get stuck on: the Home app lists the Fire
                // TV's hardware name, not the AirPlay name, so the list looks like it contains
                // somebody else's device. Naming it here removes the whole confusion.
                val name = service?.homeKitAccessoryName()
                bodyView.text = if (name != null) {
                    getString(R.string.hk_code_body_named, name)
                } else {
                    getString(R.string.hk_code_body)
                }
                renderSteps(name)
                renderCode()
                primaryButton.setText(R.string.hk_code_done)
                secondaryButton.setText(R.string.hk_later)
                startPollingForPairing()
            }
            PAGE_DONE -> {
                titleView.setText(R.string.hk_done_title)
                bodyView.setText(R.string.hk_done_body)
                renderCapabilityList()
                primaryButton.setText(R.string.hk_finish)
                secondaryButton.visibility = View.GONE
            }
        }

        if (renderedPage != page) {
            renderedPage = page
            primaryButton.requestFocus()
        }
    }

    /** The literal button-by-button path through the Home app, in the order they appear. */
    private fun renderSteps(accessoryName: String?) {
        val steps = listOf(
            getString(R.string.hk_step_open_home),
            getString(R.string.hk_step_add),
            getString(R.string.hk_step_more_options),
            accessoryName?.let { getString(R.string.hk_step_pick_named, it) }
                ?: getString(R.string.hk_step_pick),
            getString(R.string.hk_step_enter_code),
        )
        steps.forEachIndexed { i, text -> addStep(i + 1, text) }
    }

    private fun renderCode() {
        val ctx = requireContext()
        val code = service?.homeKitSetupCode()
        if (code == null) {
            // HomeKit is off, or the service is not bound yet. Say which rather than showing an
            // empty space where a code should be.
            addNote(getString(R.string.hk_code_unavailable))
            return
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(255, 250, 250, 252))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(20) }
        }

        // The QR code sits on the same white panel as the numbers so the quiet zone has somewhere
        // to be. On the dark page background a scanner struggles to find the finder patterns.
        service?.homeKitPairingUri()?.let { uri ->
            QrCode.render(uri, dp(220))?.let { bitmap ->
                panel.addView(ImageView(ctx).apply {
                    setImageBitmap(bitmap)
                    layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
                })
            } ?: Logger.w("HomeKit QR could not be rendered — showing the numeric code only")
        }

        val codeColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        codeColumn.addView(TextView(ctx).apply {
            setText(R.string.hk_code_label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.argb(160, 0, 0, 0))
        })
        codeColumn.addView(TextView(ctx).apply {
            text = code
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        codeColumn.addView(TextView(ctx).apply {
            setText(R.string.hk_code_scan_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.argb(150, 0, 0, 0))
            setPadding(0, dp(8), 0, 0)
        })
        panel.addView(codeColumn)
        itemsContainer.addView(panel)

        // Said plainly because it WILL happen and it looks like a failure: PhairPlay is not an
        // MFi-certified accessory, so iOS warns before pairing. The warning is accurate; it is just
        // not a problem.
        addNote(getString(R.string.hk_uncertified_note))
    }

    private fun renderCapabilityList() {
        listOf(
            R.string.hk_cap_power,
            R.string.hk_cap_remote,
            R.string.hk_cap_volume,
            R.string.hk_cap_siri,
        ).forEach { addNote(getString(it), bullet = true) }
    }

    // ─── Pairing watch ───────────────────────────────────────────────────────

    /**
     * Advances to the confirmation page as soon as the controller finishes pairing.
     *
     * Guarded by [pollingStarted] because [render] runs on every repaint; without it each repaint
     * would leave another loop running and the page would advance several times over.
     */
    private fun startPollingForPairing() {
        if (pollingStarted) return
        pollingStarted = true
        viewLifecycleOwner.lifecycleScope.launch {
            while (page == PAGE_CODE) {
                if (service?.isHomeKitPaired() == true) {
                    Logger.i("HomeKit paired — advancing setup flow")
                    page = PAGE_DONE
                    render()
                    return@launch
                }
                delay(POLL_MS)
            }
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private fun advance() {
        when (page) {
            PAGE_ASK -> {
                onSetEnabled?.invoke(true)
                page = PAGE_CODE
                // The service needs a moment to bring the HAP server up and mint a code; rendering
                // straight away shows "unavailable" for a second and reads like a failure.
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(START_GRACE_MS)
                    if (isAdded && page == PAGE_CODE) render()
                }
                render()
            }
            // Skipping ahead by hand is allowed: someone may pair later, or from another phone.
            PAGE_CODE -> { page = PAGE_DONE; render() }
            PAGE_DONE -> onFinished?.invoke(true)
        }
    }

    private fun decline() {
        when (page) {
            PAGE_ASK -> {
                onSetEnabled?.invoke(false)
                onFinished?.invoke(false)
            }
            // "Later" leaves HomeKit enabled and discoverable — the code stays valid, so pairing
            // can be finished from the phone whenever, without coming back to this screen.
            else -> onFinished?.invoke(true)
        }
    }

    // ─── Small builders ──────────────────────────────────────────────────────

    private fun addStep(number: Int, text: String) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            this.text = "$number"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(FOCUS_BLUE)
            layoutParams = LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.argb(220, 255, 255, 255))
        })
        itemsContainer.addView(row)
    }

    private fun addNote(text: String, bullet: Boolean = false) {
        itemsContainer.addView(TextView(requireContext()).apply {
            this.text = if (bullet) "•  $text" else text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.argb(150, 255, 255, 255))
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun buttonBackground(primary: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (primary) FOCUS_BLUE else Color.argb(40, 255, 255, 255))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_ASK = 0
        const val PAGE_CODE = 1
        const val PAGE_DONE = 2

        const val POLL_MS = 1_000L
        const val START_GRACE_MS = 1_200L

        val FOCUS_BLUE = Color.rgb(26, 115, 232)
    }
}
