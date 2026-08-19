package com.phairplay.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * The full-screen streaming overlay, as a view that can be *clicked* and not merely touched.
 *
 * A plain `FrameLayout` carrying an `OnTouchListener` is invisible to accessibility services: the
 * gesture arrives as raw `MotionEvent`s and nothing announces or triggers it, so the tap that
 * reveals the mirror controls or toggles play/pause can only be performed by someone physically
 * touching the panel. Overriding [performClick] gives that gesture a name in the accessibility
 * tree, and the touch listener calls it when a tap completes.
 *
 * This exists as a subclass because the check is on the view class, not the listener — the base
 * `FrameLayout` has nothing to override. Everything it enables is also reachable from the TV
 * remote, which is the point: touch was added alongside the remote, and neither path should be a
 * second-class way to drive the app.
 */
class TouchOverlayFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean = super.performClick()
}
