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

    /**
     * Reports which app came to the foreground, and nothing else.
     *
     * The original version of this method was a no-op, and the class comment promised the service
     * "reads nothing". That promise no longer holds and saying so plainly is better than leaving a
     * comment that flatters the code: window-state events now arrive (the config subscribes to
     * typeAllMask so interactive-window retrieval stays populated) and this reads the PACKAGE NAME
     * off them.
     *
     * The narrower promise, which the code does keep: only `event.packageName` is touched. No node
     * tree is walked, no text is read, nothing is retained. It exists so HomeKit's input tile can
     * follow apps the user opens with the physical remote -- previously only PhairPlay's own
     * launches updated it, so opening anything by hand left the Home app showing a stale input.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Follow the app's own focus, so our remembered cursor and reality do not drift apart.
        //
        // This is the finickiness. We move focus, the launcher moves it somewhere else on its own,
        // and our rect still points at the old place -- so the next press is computed from a
        // position nothing is on. That reads exactly as described: a direction that needs several
        // presses to take, or one press that appears to move twice. Whenever anything gains focus,
        // including the app doing it by itself, that becomes the new cursor.
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        ) {
            event.source?.let { node ->
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                // POISONED SYNC FILTER. When the launcher reverts focus it broadcasts
                // TYPE_VIEW_FOCUSED for the root container, and adopting that as the cursor reset
                // our position to the whole screen -- which is the state every "up goes left" and
                // stuck-direction press was computed from. A focusable leaf is never half the
                // screen in both directions; a container claiming focus always is.
                val screen = android.graphics.Rect().also {
                    rootInActiveWindow?.getBoundsInScreen(it)
                }
                val tooWide = screen.width() > 0 && bounds.width() * 2 >= screen.width()
                val tooTall = screen.height() > 0 && bounds.height() * 2 >= screen.height()
                if (!bounds.isEmpty && !(tooWide && tooTall)) lastMovedTo = bounds
            }
            return
        }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return          // our own windows are not an "input"
        if (pkg == lastForegroundPackage) return
        lastForegroundPackage = pkg
        // A remembered rect belongs to the screen it was taken from.
        lastMovedTo = null
        onForegroundApp?.invoke(pkg)
    }

    private var lastForegroundPackage: String? = null

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
        // GLOBAL_ACTION_DPAD_* landed in API 33, not 34 -- the stricter guard cost nothing on Fire
        // TV but needlessly excluded Android 13 devices where it does work.
        //
        // Worth being blunt about the Fire TV case: Fire OS is built on Android 9/11 (API 28/30), so
        // these actions DO NOT EXIST there and this line is always false on the hardware this app
        // targets. Everything below is node manipulation, which is the only tool left, and it is
        // strictly weaker: apps that draw their own UI or use custom focus (Netflix, the Fire TV
        // launcher's rows) often ignore ACTION_FOCUS entirely. There is no API that injects a real
        // D-pad event without INJECT_EVENTS, which is signature-level.
        if (Build.VERSION.SDK_INT >= 33 && global(globalAction)) return true

        val root = rootInActiveWindow ?: run {
            Logger.w("No active window — cannot move focus")
            return false
        }
        // FOCUS_INPUT is the real focus a remote moves. FOCUS_ACCESSIBILITY is the green box a
        // screen reader draws, and is often absent.
        val from = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (from == null) {
            // Nothing is focused yet. Grabbing the first focusable descendant gives the next press
            // something to move FROM. Using the root itself does not work: `root.focusSearch` has
            // no position in the focus graph and always returns null, which is what made every
            // direction report "already at the edge".
            val first = firstFocusable(root) ?: run {
                Logger.i("No focusable node in the active window — cannot move focus")
                return false
            }
            return first.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        // HOSTILE APPS GET ACCESSIBILITY FOCUS FIRST, not as a fallback.
        //
        // An app that reverts input focus does so because ACTION_FOCUS runs its focus manager,
        // which then re-asserts its own choice -- visible on screen as the highlight flicking the
        // wrong way for a frame before snapping back. Accessibility focus is tracked separately by
        // ViewRootImpl and never touches View.isFocused(), so the app's focus manager has nothing
        // to react to and no revert runs. Once a package has been seen reverting, stop sending it
        // ACTION_FOCUS at all.
        val pkg = root.packageName?.toString()
        if (pkg != null && pkg in hostilePackages) {
            val target = nearestInDirection(root, from, direction)
            if (target != null &&
                target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            ) {
                remember(target)
                return true
            }
            if (target != null && scrollToPosition(target)) return true
            // Stop here. Falling through re-ran focusSearch and a second geometric pass, which is
            // why one press produced two "Nothing beyond the remembered position" lines and moved
            // twice as far as intended when both passes happened to succeed.
            Logger.i("Hostile app would not take accessibility focus (direction $direction)")
            return true
        }

        from.focusSearch(direction)?.let { return it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        // focusSearch said there is nothing that way. On the Fire TV launcher that was a lie: the
        // device log showed six presses in a row all landing on "Focus is at the edge and nothing
        // scrolls" while the screen was plainly full of items to the right. focusSearch walks the
        // author's declared focus graph, and apps that manage focus themselves -- which is most TV
        // apps -- never declare one, so it finds nothing to walk.
        //
        // Geometry does not need the app's cooperation. Take every focusable node on screen, keep
        // the ones that actually lie in the direction asked for, and focus the nearest.
        // Computed ONCE. This used to be called again for the accessibility-focus attempt and a
        // third time for select, so a single press walked the whole node tree three times -- with
        // the root-position branch inside it running three times too, which is why the log showed
        // "Nothing beyond the remembered position" twice per press. On a launcher whose tree is
        // re-fetched from another process each time, that is most of the latency behind having to
        // press a direction several times before anything happens.
        val geometric = nearestInDirection(root, from, direction)
        if (geometric != null && geometric.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return true
        }

        // Genuinely nothing that way, so the row or grid probably needs to scroll to reveal more.
        if (scrollNearest(from, direction)) return true

        // Last resort: ACCESSIBILITY focus rather than INPUT focus.
        //
        // They are separate systems. ACTION_FOCUS moves the focus a remote drives; screen readers
        // use ACTION_ACCESSIBILITY_FOCUS, and some custom surfaces -- WebViews, and the React
        // Native and Flutter UIs common in TV apps -- respond to the second while ignoring the
        // first entirely. It is worth one attempt before giving up, and it is harmless where it
        // does nothing.
        if (geometric != null &&
            geometric.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        ) {
            Logger.i("Moved accessibility focus (direction $direction) — input focus refused")
            return true
        }

        // Then ACTION_SELECT, which is the last thing worth trying.
        //
        // Some custom surfaces track selection separately from focus and respond to this when both
        // focus APIs are ignored. It is a long shot and it is harmless where unsupported.
        //
        // ACTION_LONG_CLICK is deliberately NOT in this chain, despite being the obvious companion.
        // Fired blindly at whatever node a direction press lands on, it opens context menus and
        // "remove from list" confirmations on TV launchers. A remote that occasionally deletes
        // something is worse than one that occasionally does not move.
        if (geometric != null && geometric.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
            Logger.i("Selected the adjacent node (direction $direction) — both focus APIs refused")
            return true
        }

        // Counted, because "at the edge" was never a diagnosis. Whether the geometric search saw
        // one focusable node or forty is the difference between "this app exposes no nodes and only
        // real key injection can drive it" and "it exposes plenty and my direction test is wrong" --
        // two completely different fixes, and the old message could not tell them apart.
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, all)
        val bounds = android.graphics.Rect().also { from.getBoundsInScreen(it) }
        Logger.i(
            "Focus stuck (direction $direction): ${all.size} focusable node(s) on screen, " +
                "from=$bounds pkg=${root.packageName}",
        )
        return true
    }

    /**
     * Finds the nearest focusable node lying in [direction] from [from], by screen position.
     *
     * "In that direction" means its centre is past the corresponding edge of [from], so a node that
     * merely overlaps does not count and focus cannot bounce between two boxes forever. Distance is
     * weighted: travel along the requested axis counts once, drift across it counts several times
     * over, which keeps a press moving along the current row instead of jumping diagonally to
     * something marginally closer as the crow flies.
     */
    private fun nearestInDirection(
        root: AccessibilityNodeInfo,
        from: AccessibilityNodeInfo,
        direction: Int,
    ): AccessibilityNodeInfo? {
        val origin = android.graphics.Rect().also { from.getBoundsInScreen(it) }

        // A "focused" node the size of the whole screen is not a focus position, it is a container
        // that took focus because nothing inside it did. The device log caught this exactly:
        //
        //   Focus stuck (direction 66): 8 focusable node(s) on screen, from=Rect(0, 0 - 1920, 1080)
        //
        // Eight candidates present, and not one could be "past the right edge" of a rect that IS the
        // screen -- so every direction failed regardless of what was on screen. Focusing a real child
        // gives the next press somewhere to move from.
        val rootBounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        if (origin.width() >= rootBounds.width() && origin.height() >= rootBounds.height()) {
            // Seed focus onto a real child, then CARRY ON from there in this same press.
            //
            // The first version returned the seeded child and stopped, which spent the press on
            // seeding. That would be fine if the focus stuck -- it does not. The device log shows
            // this branch firing on twelve consecutive presses, every one of them landing on
            // "moving it to the first real child" and nothing on screen moving: the app takes focus
            // straight back to its root, so the next press starts over. Seeding forever is not
            // progress, so the seed now happens and the requested direction is applied immediately,
            // which at least gets one real move out of the press.
            root.packageName?.toString()?.let { hostilePackages += it }

            // RESUME from where we last were, rather than restarting at the first child.
            //
            // This is why the remote could go down and right but never up or left, and why right
            // stopped after about three presses. The app takes focus back to its root after every
            // move, so every press re-seeded to firstFocusable() -- the first node in the tree,
            // which is at the top-left. From there "down" and "right" always have candidates and
            // "up" and "left" never do, and right kept re-measuring from the same origin rather
            // than advancing. The symptom described it exactly.
            //
            // The remembered rect is our own idea of where the cursor is. The app will not maintain
            // one for us, so we maintain it ourselves.
            val resumeFrom = lastMovedTo?.let { rect -> focusableAt(root, rect) }
            if (resumeFrom != null) {
                val next = nearestInDirection(root, resumeFrom, direction)
                if (next != null) {
                    next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    remember(next)
                    Logger.i("Resumed from the remembered position (direction $direction)")
                    return next
                }
                Logger.i("Nothing beyond the remembered position (direction $direction)")
                return null
            }

            val seed = firstFocusable(root)?.takeIf { it != from }
            if (seed == null || !seed.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                Logger.i("Window root holds focus and no child would take it — app manages its own focus")
                return null
            }
            Logger.i("Seeded focus onto a real child — continuing the move from there")
            val moved = nearestInDirection(root, seed, direction) ?: seed
            remember(moved)
            return moved
        }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, candidates)

        var best: AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        val bounds = android.graphics.Rect()
        for (node in candidates) {
            if (node == from) continue
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue
            val dx = bounds.centerX() - origin.centerX()
            val dy = bounds.centerY() - origin.centerY()
            val ahead = when (direction) {
                View.FOCUS_LEFT  -> bounds.centerX() < origin.left
                View.FOCUS_RIGHT -> bounds.centerX() > origin.right
                View.FOCUS_UP    -> bounds.centerY() < origin.top
                View.FOCUS_DOWN  -> bounds.centerY() > origin.bottom
                else -> false
            }
            if (!ahead) continue
            val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
            val along = if (horizontal) dx else dy
            val across = if (horizontal) dy else dx

            // STRICT PROJECTION. Distance scoring alone put "up" on a node up and to the left,
            // because Leanback rows are wide and short -- a neighbour one row up and half a screen
            // sideways scored better than the item directly above. Require the candidate to overlap
            // the origin on the cross axis; anything that does not is a different column or row and
            // is only reachable by pressing in that direction instead.
            val overlaps = if (horizontal) {
                bounds.bottom > origin.top && bounds.top < origin.bottom
            } else {
                bounds.right > origin.left && bounds.left < origin.right
            }
            val penalty = if (overlaps) CROSS_AXIS_PENALTY else NO_OVERLAP_PENALTY
            val score = Math.abs(along).toLong() + Math.abs(across).toLong() * penalty
            if (score < bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    /** Every focusable, visible node under [node]. Depth-capped like [firstFocusable]. */
    private fun collectFocusable(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
    ) {
        if (depth > MAX_TREE_DEPTH) return
        if (node.isFocusable && node.isVisibleToUser) into += node
        for (i in 0 until node.childCount) {
            collectFocusable(node.getChild(i) ?: continue, into, depth + 1)
        }
    }

    /**
     * Where we believe the cursor is, in screen coordinates.
     *
     * Only used for apps that refuse to keep focus on a child. Cleared when the foreground app
     * changes, because a rect from a different screen is worse than no rect at all.
     */
    private var lastMovedTo: android.graphics.Rect? = null

    private fun remember(node: AccessibilityNodeInfo) {
        lastMovedTo = android.graphics.Rect().also { node.getBoundsInScreen(it) }
    }

    /**
     * The focusable node closest to [rect], or null if nothing is near it.
     *
     * NEAREST CENTRE, not exact equality -- and that difference is the whole bug. A Leanback row
     * scrolls its tiles a few pixels whenever focus moves, so by the next press no node had the
     * exact bounds we recorded. The lookup returned null, the code fell through to seeding at
     * firstFocusable() (top-left of the screen), and from there "up" and "left" have nothing beyond
     * them while "right" jumps from the top-left corner to whatever is nearest -- several tiles from
     * where the user actually was. Both reported symptoms, one cause. The log shows it as presses
     * alternating between "Resumed from the remembered position" and "Seeded focus onto a real
     * child" while the user pressed one direction repeatedly.
     *
     * The tolerance is generous on purpose: a tile that shifted is still the same tile.
     */
    private fun focusableAt(root: AccessibilityNodeInfo, rect: android.graphics.Rect): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, all)
        val bounds = android.graphics.Rect()
        var best: AccessibilityNodeInfo? = null
        var bestDistance = Int.MAX_VALUE
        for (node in all) {
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue
            val dx = bounds.centerX() - rect.centerX()
            val dy = bounds.centerY() - rect.centerY()
            val distance = Math.abs(dx) + Math.abs(dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = node
            }
        }
        return best?.takeIf { bestDistance <= CURSOR_MATCH_SLOP }
    }

    /**
     * Asks a Leanback-style collection to bring [node] into position itself.
     *
     * Rows and grids built on RecyclerView expose CollectionInfo on the container and
     * CollectionItemInfo on each item. Moving focus by hand fights the layout manager, which holds
     * its own idea of the current index and re-asserts it; ACTION_SCROLL_TO_POSITION updates that
     * index instead, so there is nothing left to revert. It also reaches items that are not
     * currently rendered, which geometry cannot see at all.
     *
     * @return false when the node is not part of a collection, which is most of the time.
     */
    private fun scrollToPosition(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val item = node.collectionItemInfo ?: return false
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < MAX_ANCESTOR_WALK) {
            if (parent.collectionInfo != null) {
                val args = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, item.rowIndex)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, item.columnIndex)
                }
                if (parent.performAction(ACTION_SCROLL_TO_POSITION, args)) {
                    Logger.i("Scrolled collection to row=${item.rowIndex} col=${item.columnIndex}")
                    remember(node)
                    return true
                }
                return false
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    /** Depth-first hunt for something that can hold input focus. */
    private fun firstFocusable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (node.isFocusable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstFocusable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Scrolls the nearest scrollable ancestor of [node] in [direction].
     *
     * Forward/backward is all the pre-API-33 scroll actions offer, so down and right both page
     * forward and up and left both page backward. That is the correct mapping for the vertical
     * row-of-rows layout every TV launcher uses.
     */
    private fun scrollNearest(node: AccessibilityNodeInfo, direction: Int): Boolean {
        val action = if (direction == View.FOCUS_DOWN || direction == View.FOCUS_RIGHT) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_WALK) {
            if (current.isScrollable && current.performAction(action)) return true
            current = current.parent
            depth++
        }
        return false
    }

    /** Activates whatever currently has focus. */
    private fun click(): Boolean {
        val root = rootInActiveWindow ?: return false
        // The remembered node comes FIRST in apps that hoard focus. OK did nothing at all in the
        // device log -- not even a failure line -- because findFocus returned the window root, which
        // is not clickable and has no clickable ancestor, so the walk below fell straight off the
        // end. Where the D-pad has been moving a cursor of our own, that cursor is what OK means.
        val focused = lastMovedTo?.let { focusableAt(root, it) }
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: run {
                Logger.i("OK pressed but nothing is focused and no remembered position")
                return false
            }
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

        /**
         * Notified with the package name of each newly foregrounded app.
         *
         * Set by PhairPlayService. Null when nothing cares, in which case the event is dropped
         * without any further inspection.
         */
        @Volatile
        var onForegroundApp: ((String) -> Unit)? = null

        /** Sends [keyCode] system-wide, returning false if the service is not enabled. */
        fun sendKey(keyCode: Int): Boolean = instance?.press(keyCode) ?: false

        /** Ancestor hops allowed when hunting for a clickable parent. */
        private const val MAX_ANCESTOR_WALK = 5

        /** Depth cap on the focusable hunt, so a pathological window can't stall a key press. */
        private const val MAX_TREE_DEPTH = 25

        /**
         * Anything at least this wide is a full-screen container rather than a focusable item.
         *
         * Deliberately crude: the alternative is fetching the window bounds on every focus event,
         * which is the traversal cost this change exists to avoid.
         */
        /** Packages seen taking focus back to their own root — see [dpad]. */
        private val hostilePackages = mutableSetOf<String>()

        /**
         * How far a node may have moved and still count as the remembered one, in pixels.
         *
         * Roughly a tile's width at 1080p. Tiles shift as a row scrolls; they do not teleport.
         */
        private const val CURSOR_MATCH_SLOP = 400

        /** ACTION_SCROLL_TO_POSITION, named here so this compiles against older SDKs. */
        private const val ACTION_SCROLL_TO_POSITION = 0x01900000

        /**
         * Cost multiplier for a candidate that does not overlap the origin on the cross axis.
         *
         * Not infinite: a row that is genuinely offset should still be reachable when there is no
         * aligned alternative at all. Large enough that an aligned candidate always wins.
         */
        private const val NO_OVERLAP_PENALTY = 100

        /** How much drift across the press axis costs, relative to travel along it. */
        private const val CROSS_AXIS_PENALTY = 3

        // Named here rather than referenced directly so the file still compiles against an SDK
        // below 34, where these constants do not exist.
        private const val ACTION_DPAD_UP = 16
        private const val ACTION_DPAD_DOWN = 17
        private const val ACTION_DPAD_LEFT = 18
        private const val ACTION_DPAD_RIGHT = 19
    }
}
