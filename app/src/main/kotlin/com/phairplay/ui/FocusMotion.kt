package com.phairplay.ui

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * The app's focus motion, in one place.
 *
 * A focused row lifts: it scales up slightly and gains elevation. That is a third focus cue on top
 * of the fill and the ring in `row_focus_selector`, and it is the one that survives being
 * photographed off a television, viewed at an angle, or seen by someone who cannot separate the
 * accent blue from the surface underneath it.
 *
 * ## Why these numbers
 *
 * **Scale 1.03, not 1.1.** Rows are full-width, so a large scale pushes the edges past the
 * container and clips them. Three percent is visible in motion without moving the text enough to
 * be re-read.
 *
 * **140ms.** A TV remote auto-repeats at roughly 8-10 keys a second when a direction is held. An
 * animation longer than about 150ms cannot finish before the next row takes focus, so the list
 * ends up showing a queue of half-finished animations trailing behind where the user actually is.
 * Short enough to complete between repeats is the constraint, not taste.
 *
 * **Cancel before starting.** Without it, holding DOWN leaves views stranded mid-scale when their
 * animation is interrupted, and the list slowly fills with rows that are permanently 1.01x.
 */
object FocusMotion {

    private const val FOCUSED_SCALE = 1.03f
    private const val DURATION_MS = 140L
    private const val FOCUSED_ELEVATION_DP = 8f

    /**
     * Applies the lift to [view] as its focus changes, preserving any listener already set.
     *
     * Keeps `clipChildren=false` off the parent so the scaled edges are not cut off — a row that
     * grows inside a clipping parent looks like it is being trimmed rather than lifted.
     */
    fun attach(view: View) = attach(view, scale = true)

    /**
     * The lift without the scale, for rows that already span their container.
     *
     * A full-width row has nowhere to grow into: scaling it pushes its edges past the scroller and
     * the parent clips them, so the focused row appears to be the one that is *cut off*. Elevation
     * and the ring carry the focus on its own here. Scale is for tiles that have space around them.
     */
    fun attachFlat(view: View) = attach(view, scale = false)

    private fun attach(view: View, scale: Boolean) {
        (view.parent as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        val existing = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            existing?.onFocusChange(v, hasFocus)
            lift(v, hasFocus, scale)
        }
        // Views can already hold focus by the time this runs (restored state, or the first row
        // requesting focus during binding), so settle them rather than waiting for a change.
        lift(view, view.isFocused, scale)
    }

    /** Attaches the flat lift to every direct child of [parent] that can take focus. */
    fun attachToChildren(parent: ViewGroup) {
        parent.clipChildren = false
        parent.clipToPadding = false
        for (i in 0 until parent.childCount) {
            parent.getChildAt(i).takeIf { it.isFocusable }?.let(::attachFlat)
        }
    }

    private fun lift(view: View, focused: Boolean, withScale: Boolean) {
        val scale = if (focused && withScale) FOCUSED_SCALE else 1f
        val elevation = if (focused) FOCUSED_ELEVATION_DP * view.resources.displayMetrics.density else 0f
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withStartAction { if (focused) view.elevation = elevation }
            .withEndAction { if (!focused) view.elevation = 0f }
            .start()
    }
}
