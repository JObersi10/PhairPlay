package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.phairplay.R

/**
 * MirrorControls — the transport bar for screen mirroring and AirPlay URL video.
 *
 * WHY: while a video session owns the screen there was no way to act on it except Back, whose
 * meaning is a setting the user has to remember having chosen. This gives the session an explicit,
 * visible set of actions instead.
 *
 * Deliberately video-only. Audio AirPlay already has its own controls — [NowPlayingScreen] draws
 * transport buttons and the remote's media keys are forwarded to the sender over DACP — so a second
 * bar there would be two competing UIs on one screen.
 *
 * HOW: hidden until the user presses something, then visible for [AUTO_HIDE_MS] of inactivity. That
 * is the TV convention (and the only sane one for mirroring, where any permanent chrome would be
 * burned into the thing being mirrored). Real focusable Buttons, so D-pad left/right and the focus
 * ring come from the platform rather than hand-rolled selection tracking.
 */
class MirrorControls @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Ends the session entirely — the sender stops mirroring. */
    var onStopClick: () -> Unit = {}

    /** Shrinks the stream into a picture-in-picture window. */
    var onPipClick: () -> Unit = {}

    private val stopButton: Button
    private val pipButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideBar() }

    init {
        visibility = GONE
        // The bar itself is transparent; only the strip at the bottom is tinted, so the mirrored
        // picture stays fully visible above it.
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xCC000000.toInt())
            )
        }

        stopButton = makeButton(R.string.mirror_control_stop) {
            cancelAutoHide()
            hideBar()
            onStopClick()
        }
        pipButton = makeButton(R.string.mirror_control_pip) {
            cancelAutoHide()
            hideBar()
            onPipClick()
        }

        val hint = TextView(context).apply {
            setText(R.string.mirror_control_hint)
            setTextColor(color(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

        strip.addView(stopButton)
        strip.addView(pipButton)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(strip)
        column.addView(hint)

        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        )
    }

    private fun makeButton(labelRes: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            setText(labelRes)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBackground()
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { onClick() }
            // A TV user cannot tell which button is selected from the background tint alone at
            // couch distance, so focus also lifts the button and brightens its label.
            setOnFocusChangeListener { v, hasFocus ->
                (v as Button).setTextColor(
                    color(if (hasFocus) R.color.text_on_accent else R.color.text_secondary)
                )
                v.animate().scaleX(if (hasFocus) FOCUS_SCALE else 1f)
                    .scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                    .setDuration(FOCUS_ANIM_MS).start()
                if (hasFocus) scheduleAutoHide()
            }
            setTextColor(color(R.color.text_secondary))
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { layoutParams = it }).marginStart = dp(8)
        }

    /**
     * Focused / unfocused pill. Built in code rather than as a drawable resource so the corner
     * radius and the dp padding above stay in one place.
     *
     * The unfocused fill is deliberately dark and semi-transparent: this sits over live video, and
     * an opaque bar would block more of the picture than it needs to.
     */
    private fun focusBackground(): StateListDrawable {
        val focused = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color(R.color.accent_blue))
        }
        val normal = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0xB32C2C2E.toInt())
            setStroke(dp(1), 0x33FFFFFF)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    /**
     * Reveals the bar and starts the countdown. Returns true when this press was consumed by
     * *opening* the bar, so the caller does not also act on that same key.
     */
    fun reveal(): Boolean {
        val wasHidden = visibility != VISIBLE
        visibility = VISIBLE
        bringToFront()
        // GONE views have no layout, so a requestFocus in the same frame as the reveal is
        // dropped — which is what left the bar visible with nothing highlighted.
        if (wasHidden) post { stopButton.requestFocus() }
        scheduleAutoHide()
        return wasHidden
    }

    /** True when the bar is on screen and therefore owns D-pad input. */
    fun isShowing(): Boolean = visibility == VISIBLE

    /** Hides the bar. Returns true if it had been showing, so Back can be consumed by closing it. */
    fun hideBar(): Boolean {
        cancelAutoHide()
        if (visibility != VISIBLE) return false
        visibility = GONE
        return true
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    private fun cancelAutoHide() = handler.removeCallbacks(hideRunnable)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAutoHide()
    }

    /** Hides PiP where the platform cannot offer it, so the bar never shows a dead button. */
    fun setPipAvailable(available: Boolean) {
        pipButton.visibility = if (available) View.VISIBLE else View.GONE
    }

    private fun color(res: Int) = androidx.core.content.ContextCompat.getColor(context, res)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Long enough to read the two labels and pick one, short enough not to sit over a film. */
        const val AUTO_HIDE_MS = 4_000L

        /** Focused buttons grow slightly — the standard Android TV affordance. */
        const val FOCUS_SCALE = 1.08f
        const val FOCUS_ANIM_MS = 120L
    }
}
