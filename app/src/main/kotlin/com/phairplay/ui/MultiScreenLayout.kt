package com.phairplay.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.util.Logger

/**
 * MultiScreenLayout — holds one [StreamingScreen] per mirroring session and arranges them as tiles.
 *
 * WHY: a `SurfaceView` cannot be shared. Two decoders pointed at one Surface simply paint over each
 * other, so each simultaneous sender needs its own view and something has to place them.
 *
 * ## A SurfaceView only has a Surface while it is really on screen
 *
 * The first version of this class kept every tile built up front and parked the spares as
 * `INVISIBLE`, on the theory that a Surface created early avoids the cold-connect race. **That was
 * wrong, and it crashed the app.** `surfaceDestroyed` fires as soon as a SurfaceView stops being
 * VISIBLE, while the `Surface` object we hold stays non-null — so a spare tile handed back a dead
 * Surface, `MediaCodec.configure` got one with no native window behind it, and the process went
 * down with `Could not find corresponding native window for surface`. It is not a catchable
 * exception.
 *
 * The same is true of a tile laid out with no area, and of one positioned outside its parent: a
 * SurfaceView is a separate window punched through the view hierarchy, so "hidden" states that work
 * for an ordinary View do not give you a live Surface here.
 *
 * So tiles are created and attached ONLY while a session is using them, and the primary tile — the
 * one every single-sender path uses — is created once and never removed. That keeps the ordinary
 * case on exactly the code that worked before this class existed, and confines the cost of a
 * late-created Surface to the second and subsequent senders, where a beat of black while the
 * decoder waits is a fair price and the alternative is a crash.
 */
class MultiScreenLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Index 0 is created eagerly and permanent; the rest exist only while a session holds them. */
    private val tiles = arrayOfNulls<StreamingScreen>(AirPlayReceiver.MAX_SLOTS)

    /**
     * The slots that currently have a session, in order — and the tile ORDER on screen.
     *
     * Positions come from a tile's place in THIS list, not from its slot number. Laying out by slot
     * leaves a hole when a lower slot disconnects: the iPhone on tile 0 ending left the grid still
     * two wide, with its frozen last frame sitting in the empty half while the Mac stayed squeezed
     * into the other. What should happen is the survivor takes the whole screen.
     */
    private var order: List<Int> = listOf(AirPlayReceiver.PRIMARY_SLOT)

    init {
        // OPAQUE. A grid of three tiles leaves a fourth cell with nothing in it, and without a
        // background of its own this ViewGroup is transparent — so the Home screen behind the
        // streaming overlay showed through the empty quadrant. Tiles cover their own area; this is
        // for the gaps between them.
        setBackgroundColor(android.graphics.Color.BLACK)
        val first = StreamingScreen(context)
        tiles[AirPlayReceiver.PRIMARY_SLOT] = first
        addView(first, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The primary tile, which every single-stream path (URL video, DLNA, audio) draws into. */
    val primary: StreamingScreen get() = tiles[AirPlayReceiver.PRIMARY_SLOT]!!

    /**
     * The Surface for [slot], creating the tile if a session has just claimed it.
     *
     * Returns null until the SurfaceView has actually been laid out and its Surface created, which
     * is the honest answer and what the mirror server already knows how to wait for. Returning a
     * placeholder would be the crash again.
     */
    fun surfaceFor(slot: Int): Surface? {
        if (slot !in tiles.indices) return null
        ensureTile(slot)
        return tiles[slot]?.getSurface()
    }

    /** The tile for [slot] if it exists. Null when no session holds it. */
    fun tileAt(slot: Int): StreamingScreen? = tiles.getOrNull(slot)

    private fun ensureTile(slot: Int): StreamingScreen? {
        if (slot !in tiles.indices) return null
        tiles[slot]?.let {
            // A tile that was parked has no Surface; showing it again is what creates one.
            if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
            return it
        }
        val tile = StreamingScreen(context)
        tiles[slot] = tile
        addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Logger.i("MultiScreenLayout: created tile $slot")
        relayoutTiles()
        return tile
    }

    private fun removeTile(slot: Int) {
        if (slot == AirPlayReceiver.PRIMARY_SLOT) return   // permanent; see the class note
        val tile = tiles[slot] ?: return
        tiles[slot] = null
        removeView(tile)
        Logger.i("MultiScreenLayout: removed tile $slot")
    }

    /**
     * Brings the layout in line with the sessions that actually exist.
     *
     * Creating and destroying real views rather than toggling visibility is the whole point — see
     * the class note. The primary tile is never removed, so a session ending leaves the layout
     * exactly as it was before any of this ran.
     */
    fun showTiles(activeSlots: Set<Int>) {
        for (slot in tiles.indices) {
            if (slot in activeSlots) ensureTile(slot) else removeTile(slot)
        }

        // THE PRIMARY IS PERMANENT BUT NOT ALWAYS SHOWN. It cannot be removed — every single-stream
        // path draws into it — but leaving it visible with no session left the last frame of a
        // sender that had disconnected frozen on screen next to the one still going. Parking it
        // clears that, and also releases its Surface, which is correct: nothing is decoding into it.
        val primaryHasSession = AirPlayReceiver.PRIMARY_SLOT in activeSlots
        primary.visibility =
            if (!primaryHasSession && activeSlots.isNotEmpty()) View.INVISIBLE else View.VISIBLE

        val next = activeSlots.sorted().ifEmpty { listOf(AirPlayReceiver.PRIMARY_SLOT) }
        if (next != order) {
            order = next
            Logger.i("MultiScreenLayout: ${next.size} tile(s) — slots ${next.joinToString()}")
        }
        relayoutTiles()
    }

    private fun relayoutTiles() {
        requestLayout()
        // Each tile letterboxes its video inside whatever box it has been given. Resizing the box
        // without re-running that leaves the previous fit stretched across the new one, which is
        // what a mirror looked like when a second sender halved its width mid-stream.
        tiles.forEach { it?.invalidateAspectFit() }
    }

    private fun columns() = if (order.size <= 1) 1 else 2
    private fun rows() = if (order.size <= 2) 1 else 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        if (w <= 0 || h <= 0) return
        val tw = MeasureSpec.makeMeasureSpec(w / columns(), MeasureSpec.EXACTLY)
        val th = MeasureSpec.makeMeasureSpec(h / rows(), MeasureSpec.EXACTLY)
        tiles.forEach { it?.measure(tw, th) }
    }

    /**
     * One tile fills the frame; two split it left and right; three or four use a 2x2 grid.
     *
     * Side by side rather than stacked for two, because mirrored content is 16:9 — half the width
     * still leaves a wider-than-tall box, where half the height would letterbox savagely.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        val cols = columns()
        val tw = w / cols
        val th = h / rows()

        tiles.forEachIndexed { slot, tile ->
            tile ?: return@forEachIndexed
            // Position by place in `order`, never by slot — see the note there. A tile with no
            // session is parked over the whole frame; it is INVISIBLE, so this only keeps it laid
            // out and measured rather than putting anything on screen.
            val index = order.indexOf(slot)
            if (index < 0) {
                tile.layout(0, 0, w, h)
                return@forEachIndexed
            }
            val col = index % cols
            val row = index / cols
            tile.layout(col * tw, row * th, col * tw + tw, row * th + th)
        }
    }
}
