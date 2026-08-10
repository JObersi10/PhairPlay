package com.phairplay.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phairplay.util.Logger

/**
 * PhairPlayAccessibilityService — lets the HomeKit remote drive the WHOLE Fire TV, not just us.
 *
 * WHY THIS AND NOT KEY INJECTION: `INJECT_EVENTS` is the permission that would let us post events
 * straight into the input pipeline, and it is signature|privileged. `pm grant` refuses it — that
 * command only reaches runtime permissions — so no sideloaded app can hold it on a stock device.
 * An accessibility service is the sanctioned route to the same capability, and unlike INJECT_EVENTS
 * it CAN be turned on entirely over adb:
 *
 *     adb shell settings put secure enabled_accessibility_services \
 *         com.phairplay.firetv/com.phairplay.service.PhairPlayAccessibilityService
 *     adb shell settings put secure accessibility_enabled 1
 *
 * HOW IT MOVES FOCUS: there is no "send a D-pad event" API below Android 14. Instead we ask the
 * active window for its focused node and walk the same focus graph the system would — the node's
 * own `focusSearch`, which respects `nextFocusDown` and every other hint the app author set. On
 * Android 14+ the platform finally exposes GLOBAL_ACTION_DPAD_*, which drives the real input path
 * and handles cases node traversal cannot, so that is preferred where it exists.
 *
 * WHAT IT DELIBERATELY DOES NOT DO: no [AccessibilityEvent] is consumed, no window content is
 * inspected beyond the focused node, and nothing is logged about what is on screen. The service
 * exists to push focus around on request, and an accessibility service that reads more than it
 * needs is a keylogger with good manners.
 */
class PhairPlayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.i("Accessibility service connected — system-wide remote control available")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Logger.i("Accessibility service disconnected — remote falls back to in-app keys")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Required override. We subscribe to nothing, so nothing arrives and nothing is read. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Performs [keyCode] system-wide.
     *
     * @return true if the press was delivered. False means the caller should fall back to its own
     *   window, which is the honest outcome when there is no focused node to move from.
     */
    fun press(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> global(GLOBAL_ACTION_BACK)
        KeyEvent.KEYCODE_HOME -> global(GLOBAL_ACTION_HOME)
        KeyEvent.KEYCODE_DPAD_UP -> dpad(View.FOCUS_UP, ACTION_DPAD_UP)
        KeyEvent.KEYCODE_DPAD_DOWN -> dpad(View.FOCUS_DOWN, ACTION_DPAD_DOWN)
        KeyEvent.KEYCODE_DPAD_LEFT -> dpad(View.FOCUS_LEFT, ACTION_DPAD_LEFT)
        KeyEvent.KEYCODE_DPAD_RIGHT -> dpad(View.FOCUS_RIGHT, ACTION_DPAD_RIGHT)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> click()
        else -> false
    }

    private fun global(action: Int): Boolean =
        runCatching { performGlobalAction(action) }
            .onFailure { Logger.w("Global action $action failed: ${it.message}") }
            .getOrDefault(false)

    /**
     * Moves focus one step in [direction].
     *
     * Prefers the platform's own D-pad action where it exists (API 34+): it goes through the real
     * input path, so it works on surfaces that expose no focusable nodes at all — video players,
     * games, anything drawing its own UI. Node traversal is the fallback and covers everything else.
     */
    private fun dpad(direction: Int, globalAction: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 34 && global(globalAction)) return true

        val root = rootInActiveWindow ?: run {
            Logger.w("No active window — cannot move focus")
            return false
        }
        // FOCUS_INPUT is the real focus a remote moves. FOCUS_ACCESSIBILITY is the green box a
        // screen reader draws, and is often absent; falling back to the root keeps a first press
        // working on a screen where nothing is focused yet.
        val from = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root
        val next = from.focusSearch(direction) ?: run {
            // A real outcome, not a failure: focus is already at the edge in that direction.
            Logger.i("Focus already at the edge (direction $direction)")
            return true
        }
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    /** Activates whatever currently has focus. */
    private fun click(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
        // Walk up to the nearest clickable ancestor: on a TV the focused node is frequently a label
        // inside the card that actually carries the click handler.
        var node: AccessibilityNodeInfo? = focused
        var depth = 0
        while (node != null && depth < MAX_ANCESTOR_WALK) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
            depth++
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    companion object {
        /**
         * The connected service, or null when the user has not enabled it.
         *
         * A static handle to a Service is normally a leak waiting to happen; here it is cleared in
         * both [onUnbind] and [onDestroy], and the alternative — binding to our own accessibility
         * service — is not something the framework supports.
         */
        @Volatile
        private var instance: PhairPlayAccessibilityService? = null

        val isConnected: Boolean get() = instance != null

        /** Sends [keyCode] system-wide, returning false if the service is not enabled. */
        fun sendKey(keyCode: Int): Boolean = instance?.press(keyCode) ?: false

        /** Ancestor hops allowed when hunting for a clickable parent. */
        private const val MAX_ANCESTOR_WALK = 5

        // Named here rather than referenced directly so the file still compiles against an SDK
        // below 34, where these constants do not exist.
        private const val ACTION_DPAD_UP = 16
        private const val ACTION_DPAD_DOWN = 17
        private const val ACTION_DPAD_LEFT = 18
        private const val ACTION_DPAD_RIGHT = 19
    }
}
