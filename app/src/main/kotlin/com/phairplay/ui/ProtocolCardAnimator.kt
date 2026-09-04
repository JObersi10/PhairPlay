package com.phairplay.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.phairplay.R
import com.phairplay.service.ProtocolState

/**
 * Animates a protocol card between states instead of swapping its text.
 *
 * The point is that a state change should be *noticed* out of the corner of the eye — a device
 * connecting is the single most important thing that happens on this screen — without the card
 * becoming a thing that demands attention while nothing is happening.
 *
 * What moves, and why:
 *
 *  - **the status dot swells** and settles back. It is the smallest element carrying the most
 *    meaning, so it is the one that earns the movement.
 *  - **the icon nudges** by two pixels. Barely visible alone; what it does is make the whole card
 *    feel like one object reacting, rather than a label being rewritten.
 *  - **the detail line crossfades** and rises slightly, because its text genuinely changes and a
 *    hard swap reads as a glitch.
 *  - **nothing bounces.** The interpolator overshoots by a few percent and settles. A spring with
 *    visible oscillation is charming once and irritating by the tenth time.
 *
 * ~320ms total: long enough to read as physical, short enough that a state flapping between
 * advertising and connected does not queue up a backlog of animations.
 */
object ProtocolCardAnimator {

    /** Gentle overshoot, no oscillation. */
    private val SETTLE = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)

    private const val TRANSITION_MS = 320L
    private const val DOT_SWELL = 1.6f
    private const val ICON_NUDGE_DP = 2f

    /**
     * Applies [state] to [card], animating only when it actually changed.
     *
     * The previous state is held on the view itself rather than in a map, so a card that is
     * recycled or re-bound cannot inherit another card's history.
     */
    fun apply(card: View, state: ProtocolState, animate: Boolean) {
        val previous = card.getTag(R.id.tag_protocol_state) as? ProtocolState
        card.setTag(R.id.tag_protocol_state, state)
        if (!animate || previous == null || previous == state) return

        val dot = card.findViewById<View>(R.id.dot_protocol_status) ?: return
        val icon = card.findViewById<ImageView>(R.id.img_protocol_icon)
        val detail = card.findViewById<TextView>(R.id.text_protocol_detail)

        swell(dot)
        icon?.let { nudge(it, becameActive(previous, state)) }
        detail?.let { rise(it) }
    }

    /** True when the card moved *up* the ladder — that is the direction worth celebrating. */
    private fun becameActive(from: ProtocolState, to: ProtocolState): Boolean =
        rank(to) > rank(from)

    private fun rank(s: ProtocolState) = when (s) {
        ProtocolState.DISABLED -> 0
        ProtocolState.ERROR -> 1
        ProtocolState.ADVERTISING -> 2
        ProtocolState.CONNECTED -> 3
    }

    private fun swell(dot: View) {
        dot.animate().cancel()
        ValueAnimator.ofFloat(1f, DOT_SWELL, 1f).apply {
            duration = TRANSITION_MS
            interpolator = SETTLE
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                dot.scaleX = v
                dot.scaleY = v
            }
            start()
        }
    }

    private fun nudge(icon: ImageView, upward: Boolean) {
        val d = ICON_NUDGE_DP * icon.resources.displayMetrics.density
        icon.animate().cancel()
        icon.translationY = if (upward) d else -d
        icon.animate()
            .translationY(0f)
            .setDuration(TRANSITION_MS)
            .setInterpolator(SETTLE)
            .start()
    }

    private fun rise(text: TextView) {
        val d = 4f * text.resources.displayMetrics.density
        text.animate().cancel()
        text.alpha = 0f
        text.translationY = d
        text.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TRANSITION_MS)
            .setInterpolator(SETTLE)
            .start()
    }

    /** Tints the status dot, keeping the drawable shared-state safe. */
    fun tintDot(dot: View, color: Int) {
        dot.backgroundTintList = ColorStateList.valueOf(color)
    }
}
