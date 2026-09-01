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

    private var tileCount: Int = 1

    init {
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

    private fun ensureTile(slot: Int): StreamingScreen? {
        if (slot !in tiles.indices) return null
        tiles[slot]?.let { return it }
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
        val highest = (activeSlots.maxOrNull() ?: 0) + 1
        if (highest != tileCount) {
            tileCount = highest.coerceIn(1, AirPlayReceiver.MAX_SLOTS)
            Logger.i("MultiScreenLayout: $tileCount tile(s)")
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

    private fun columns() = if (tileCount <= 1) 1 else 2
    private fun rows() = if (tileCount <= 2) 1 else 2

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
            val col = slot % cols
            val row = slot / cols
            tile.layout(col * tw, row * th, col * tw + tw, row * th + th)
        }
    }
}
