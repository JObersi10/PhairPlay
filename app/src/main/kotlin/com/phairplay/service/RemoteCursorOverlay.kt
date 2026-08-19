package com.phairplay.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.phairplay.util.Logger

/**
 * RemoteCursorOverlay — draws the focus highlight the app refuses to draw for us.
 *
 * WHY THIS EXISTS: on the Fire TV launcher both focus APIs are closed to us. The device log is
 * unambiguous — `ACTION_ACCESSIBILITY_FOCUS` returns false on every press, not "returns true and
 * gets reverted". Since no highlight will ever move on our behalf, we stop asking: the service
 * already tracks its own cursor geometrically (`lastMovedTo`), and this draws a ring at that
 * position in a window the app cannot touch. Selection goes through `ACTION_CLICK`, which is the
 * one action apps that ignore both focus systems still honour, because it is what screen readers
 * use to activate things.
 *
 * The visual is the optional half. If the overlay permission is missing this quietly does nothing
 * and navigation still works — the user just cannot see where the cursor is, which is bad but is
 * not broken. That is why every method here is failure-tolerant rather than throwing.
 */
class RemoteCursorOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** Hides the ring after a pause, so it isn't left burned onto an idle screen. */
    private val hideRunnable = Runnable { hide() }

    /**
     * True when we are allowed to draw at all.
     *
     * Checked on every move rather than cached: the user can grant "Display over other apps" while
     * the service is running, and a cached false would keep the ring invisible until a reboot.
     */
    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    /** Moves the ring to [bounds] in screen coordinates, creating the window on first use. */
    fun moveTo(bounds: Rect) {
        if (bounds.isEmpty) return
        main.post {
            if (!canDraw()) return@post
            runCatching {
                val wm = windowManager ?: return@post
                val lp = params ?: newParams().also { params = it }
                lp.x = bounds.left
                lp.y = bounds.top
                lp.width = bounds.width()
                lp.height = bounds.height()

                val existing = view
                if (existing == null) {
                    val ring = View(context).apply { background = ringDrawable() }
                    wm.addView(ring, lp)
                    view = ring
                    Logger.i("Remote cursor overlay shown")
                } else {
                    existing.visibility = View.VISIBLE
                    wm.updateViewLayout(existing, lp)
                }
                main.removeCallbacks(hideRunnable)
                main.postDelayed(hideRunnable, IDLE_HIDE_MS)
            }.onFailure {
                // Losing the overlay must never take navigation down with it.
                Logger.w("Remote cursor overlay failed: ${it.message}")
                view = null
            }
        }
    }

    fun hide() {
        main.post {
            view?.let { it.visibility = View.GONE }
        }
    }

    fun destroy() {
        main.post {
            main.removeCallbacks(hideRunnable)
            runCatching { view?.let { windowManager?.removeView(it) } }
            view = null
            params = null
        }
    }

    /**
     * TYPE_APPLICATION_OVERLAY is the only type an ordinary app may use from API 26 on; the older
     * TYPE_SYSTEM_ALERT is blocked. NOT_FOCUSABLE and NOT_TOUCHABLE together mean the ring never
     * takes input away from the app underneath — it is decoration and nothing else.
     */
    @Suppress("DEPRECATION")
    private fun newParams() = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * Matched to Fire TV's own focus treatment rather than picked to stand out: a plain white
     * border, squared off, no fill. A differently-coloured ring reads as an overlay from a
     * third-party app, which is exactly what it is and exactly what it should not look like.
     */
    private fun ringDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0x00000000)
        setStroke(RING_WIDTH_PX, RING_COLOR)
        cornerRadius = RING_RADIUS_PX
    }

    private companion object {
        const val RING_WIDTH_PX = 4
        const val RING_RADIUS_PX = 4f
        const val RING_COLOR = 0xFFFFFFFF.toInt()
        const val IDLE_HIDE_MS = 8_000L
    }
}
