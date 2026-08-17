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

    /** Our own focus ring, for apps that refuse to move theirs. Null until the service connects. */
    private var cursorOverlay: RemoteCursorOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cursorOverlay = RemoteCursorOverlay(this)
        Logger.i("Accessibility service connected — system-wide remote control available")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        cursorOverlay?.destroy()
        cursorOverlay = null
        Logger.i("Accessibility service disconnected — remote falls back to in-app keys")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        cursorOverlay?.destroy()
        cursorOverlay = null
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
            // NOT in an app we drive with the phantom cursor. Syncing to the app's focus is right
            // where the app and we agree on what focus means; in a hostile app it is self-defeating,
            // because "hostile" is defined as an app that moves focus back on its own. Adopting that
            // move drags our cursor to wherever the launcher decided, which is the same drift the
            // phantom cursor exists to remove — the press after it is then computed from a position
            // the user never navigated to.
            val syncingPkg = rootInActiveWindow?.packageName?.toString()
            if (syncingPkg != null && syncingPkg in hostilePackages) return
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
        // A remembered rect belongs to the screen it was taken from, and so does the ring drawn at
        // it — leaving it up would park a highlight over an app that has no such item.
        lastMovedTo = null
        cursorOverlay?.hide()
        // Hostility is a property of a SCREEN, not of an app for all time. One transient refusal --
        // during a layout pass, or on a splash screen with nothing focusable yet -- used to put a
        // package on the phantom-cursor path permanently, which is strictly worse for an app that
        // would have accepted ordinary focus a second later. Apple Music TV, where the remote was
        // reported working, is exactly the kind of app that loses out. Re-earned per window.
        hostilePackages -= pkg
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
            // PHANTOM CURSOR. We no longer ask this app to move any kind of focus.
            //
            // The previous version tried ACTION_ACCESSIBILITY_FOCUS here on the theory that it is
            // tracked by ViewRootImpl and so cannot be reverted by the app's focus manager. The
            // device log killed that theory: the action returns FALSE on every press — the launcher
            // refuses it outright, exactly as it refuses ACTION_FOCUS. Both focus systems are shut,
            // and no amount of re-ordering them opens one.
            //
            // So the cursor becomes entirely ours. nearestInDirection already resumes from the
            // remembered rect and advances it; we draw the highlight ourselves in an overlay window,
            // and Select activates by ACTION_CLICK, which is a different code path in the app (it is
            // what screen readers use) and is honoured where focus is not.
            val target = nearestInDirection(root, from, direction)
            if (target != null) {
                remember(target)
                showCursor(target)
                // Best-effort, purely so the app's own highlight follows along where it will take
                // it. The return value is deliberately ignored: our cursor has already moved and
                // the press is handled either way.
                target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                return true
            }
            // Nothing that way on screen means the row or grid has to page. Scroll the CONTAINER,
            // not the leaf: Leanback routes navigation through its grid views, and their
            // accessibility delegate implements the directional scroll actions even though it
            // ignores focus requests on children.
            if (scrollContainer(from, direction)) {
                Logger.i("Scrolled the container (direction $direction) — cursor waiting for the new layout")
                return true
            }
            Logger.i("Cursor is at the edge and nothing scrolls (direction $direction)")
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
            // LAND ON THE SEED, do not move on from it.
            //
            // This used to apply the requested direction immediately after seeding, so the first
            // press of a screen travelled two positions: onto the seed, and then one beyond it.
            // Every later press moves one. That is the "+1" — the cursor is permanently one step
            // further along than the number of presses accounts for, and it shows up as the whole
            // sequence being offset rather than as a single wrong jump.
            //
            // The double move was a workaround for focus not sticking, back when the app was
            // expected to hold it: seeding alone appeared to do nothing, so the direction was
            // applied to "get one real move out of the press". The cursor is ours now and the seed
            // is a visible position in its own right, so the workaround is just an off-by-one.
            Logger.i("Seeded the cursor onto a real child (direction $direction)")
            remember(seed)
            showCursor(seed)
            return seed
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

    /**
     * Pages the grid container that owns [node], in the direction asked for.
     *
     * Distinct from [scrollNearest] in two ways that matter on Leanback. First it prefers the
     * DIRECTIONAL scroll actions (API 29+) over forward/backward: a row-of-rows layout has a
     * horizontal grid nested in a vertical one, and "forward" is ambiguous between them — which is
     * how a left press ended up paging a row rightwards. Second it prefers the container exposing
     * CollectionInfo, because that is the node whose accessibility delegate Leanback actually
     * implements; the leaf tiles have none.
     *
     * Falls back to forward/backward on API 28, where the directional actions do not exist.
     */
    private fun scrollContainer(node: AccessibilityNodeInfo, direction: Int): Boolean {
        val directional = if (Build.VERSION.SDK_INT >= 29) when (direction) {
            View.FOCUS_UP    -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            View.FOCUS_DOWN  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            View.FOCUS_LEFT  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else             -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        } else null

        val fallback = if (direction == View.FOCUS_DOWN || direction == View.FOCUS_RIGHT) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        // Paging actions, API 29+. Never tried before this: a launcher row that ignores a one-step
        // scroll may still honour a page, because that is what a fast-forward gesture maps to.
        val page = if (Build.VERSION.SDK_INT >= 29) when (direction) {
            View.FOCUS_UP    -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
            View.FOCUS_DOWN  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
            View.FOCUS_LEFT  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
            else             -> AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
        } else null

        // Collections first, then any scrollable ancestor. Two passes rather than one so a
        // scrollable-but-not-a-collection wrapper cannot swallow the press before the real grid
        // underneath it gets a turn.
        for (collectionsOnly in listOf(true, false)) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < MAX_ANCESTOR_WALK) {
                val eligible = if (collectionsOnly) current.collectionInfo != null else current.isScrollable
                if (eligible) {
                    if (directional != null && current.performAction(directional)) return true
                    if (page != null && current.performAction(page)) return true
                    // Leanback's own action, and the one this method LOST when the hostile path was
                    // rewritten around the phantom cursor -- the previous version called it and this
                    // one did not, so the current build was trying strictly fewer things than the
                    // build before it. Restored here rather than in the caller so every scroll
                    // attempt goes through one chain.
                    if (current.performAction(ACTION_SCROLL_TO_POSITION)) return true
                    if (current.isScrollable && current.performAction(fallback)) return true
                }
                current = current.parent
                depth++
            }
        }

        // Last resort: ask the app to bring the far edge of the collection into view.
        //
        // ACTION_SHOW_ON_SCREEN is answered by the VIEW rather than by a scroll container, so it
        // survives a container that refuses every scroll action -- which is exactly the situation
        // the device log shows. Aimed at the node furthest along the direction of travel, because
        // asking to show a node already on screen is a no-op.
        val edge = furthestInDirection(node, direction)
        if (edge != null &&
            edge.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        ) {
            Logger.i("Asked the app to show the far edge on screen (direction $direction)")
            return true
        }
        return swipeToScroll(direction)
    }

    /**
     * Scrolls by synthesising a touch swipe, when every accessibility scroll action has been refused.
     *
     * WHY THIS IS WORTH TRYING despite Fire TV having no touchscreen: Leanback's grid views are
     * RecyclerViews, which implement touch scrolling themselves, and dispatchGesture injects
     * MotionEvents through InputManager rather than through a driver -- the view cannot tell that no
     * finger exists. This asks the list to scroll the way a phone user would, which is a completely
     * different code path from the accessibility scroll actions the launcher rejects.
     *
     * Note this is used ONLY to scroll, never to move focus. Trying to steer a cursor with blind
     * swipes on a device with no pointer would be guesswork; bringing more rows into the node tree
     * so the real cursor has somewhere to go is a narrow, checkable job.
     *
     * The swipe runs opposite to the direction of travel — dragging content up reveals what is
     * below it — and is kept short and fast so it scrolls by roughly one item rather than flinging.
     */
    private fun swipeToScroll(direction: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val screen = android.graphics.Rect().also { rootInActiveWindow?.getBoundsInScreen(it) }
        if (screen.isEmpty) return false
        val cx = screen.centerX().toFloat()
        val cy = screen.centerY().toFloat()
        val dx = screen.width() * SWIPE_FRACTION
        val dy = screen.height() * SWIPE_FRACTION

        val path = android.graphics.Path()
        path.moveTo(cx, cy)
        when (direction) {
            View.FOCUS_UP    -> path.lineTo(cx, cy + dy)
            View.FOCUS_DOWN  -> path.lineTo(cx, cy - dy)
            View.FOCUS_LEFT  -> path.lineTo(cx + dx, cy)
            else             -> path.lineTo(cx - dx, cy)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, SWIPE_MS))
            .build()
        val dispatched = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        Logger.i("Swipe-to-scroll (direction $direction) dispatched=$dispatched")
        // The cursor is deliberately NOT advanced here. The swipe is asynchronous and the node tree
        // does not update until the list settles, so the next press is what picks up the new rows --
        // pretending the move already happened would put the cursor somewhere nothing exists yet.
        return dispatched
    }

    /**
     * The focusable node furthest along [direction] from [from] — the one whose appearance would
     * mean the list has paged. Used only as the target for ACTION_SHOW_ON_SCREEN.
     */
    private fun furthestInDirection(from: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val origin = android.graphics.Rect().also { from.getBoundsInScreen(it) }
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectFocusable(root, all)
        val bounds = android.graphics.Rect()
        var best: AccessibilityNodeInfo? = null
        var bestDistance = 0
        for (node in all) {
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue
            val along = when (direction) {
                View.FOCUS_UP    -> origin.top - bounds.bottom
                View.FOCUS_DOWN  -> bounds.top - origin.bottom
                View.FOCUS_LEFT  -> origin.left - bounds.right
                else             -> bounds.left - origin.right
            }
            if (along > bestDistance) { bestDistance = along; best = node }
        }
        return best
    }

    /** Draws our own highlight at [node], since the app will not move one for us. */
    private fun showCursor(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        cursorOverlay?.moveTo(bounds)
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
        /** Fraction of the screen a scroll swipe travels — about one row, not a fling. */
        private const val SWIPE_FRACTION = 0.25f
        private const val SWIPE_MS = 120L

        private const val ACTION_DPAD_UP = 16
        private const val ACTION_DPAD_DOWN = 17
        private const val ACTION_DPAD_LEFT = 18
        private const val ACTION_DPAD_RIGHT = 19
    }
}
