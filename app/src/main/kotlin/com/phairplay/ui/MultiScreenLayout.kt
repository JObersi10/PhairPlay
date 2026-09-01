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
 * WHY: the receiver can serve several senders at once (see `docs/MULTI_SCREEN.md`), but a
 * `SurfaceView` cannot be shared — two decoders pointed at one Surface simply paint over each
 * other. Each session therefore needs its own view, and something has to decide where they go.
 *
 * **Every tile is created up front and keeps its Surface for the whole life of the layout.** That
 * is the important property, and it is why tiles are hidden rather than added and removed: a
 * `SurfaceView` has no Surface until it is visible and laid out, so creating one at the moment a
 * sender connects reproduces the cold-first-connect race — the sender's opening IDR arrives before
 * the Surface exists, and the tile stays black until the next keyframe, which macOS is in no hurry
 * to send. Building them ahead of time means a Surface is always waiting.
 *
 * HOW: [setTileCount] switches between full-bleed, side-by-side and a 2x2 grid. With one active
 * tile the layout is exactly what a single session had before this class existed — the tile fills
 * the container — so nothing about the ordinary one-sender case changes.
 */
class MultiScreenLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tiles = ArrayList<StreamingScreen>(AirPlayReceiver.MAX_SLOTS)

    /** How many tiles are currently laid out. Always at least one. */
    var tileCount: Int = 1
        private set

    init {
        repeat(AirPlayReceiver.MAX_SLOTS) { slot ->
            val tile = StreamingScreen(context)
            // Only the primary starts visible. The rest are INVISIBLE rather than GONE: a GONE view
            // is never laid out, so its SurfaceView would never create a Surface and the tile would
            // be black for the first seconds of the session it is eventually given.
            tile.visibility = if (slot == 0) View.VISIBLE else View.INVISIBLE
            tiles += tile
            addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** The primary tile, which every single-stream path (URL video, DLNA, audio) draws into. */
    val primary: StreamingScreen get() = tiles[AirPlayReceiver.PRIMARY_SLOT]

    /** The Surface for [slot], or null if it has not been created yet. */
    fun surfaceFor(slot: Int): Surface? =
        tiles.getOrNull(slot)?.getSurface()

    fun tileAt(slot: Int): StreamingScreen? = tiles.getOrNull(slot)

    /**
     * Re-arranges for [count] simultaneous senders.
     *
     * 1 fills the frame; 2 splits it left/right; 3 and 4 use a 2x2 grid, with the third tile taking
     * the bottom-left and the bottom-right left dark until a fourth arrives. Splitting horizontally
     * rather than vertically for two is deliberate: mirrored content is 16:9, so side-by-side tiles
     * stay wider than they are tall and letterbox far less than stacked ones would.
     */
    fun setTileCount(count: Int) {
        val next = count.coerceIn(1, AirPlayReceiver.MAX_SLOTS)
        if (next == tileCount && width > 0) return
        tileCount = next
        Logger.i("MultiScreenLayout: $next tile(s)")
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        val cols = if (tileCount <= 1) 1 else 2
        val rows = if (tileCount <= 2) 1 else 2
        val tw = w / cols
        val th = h / rows

        tiles.forEachIndexed { slot, tile ->
            if (slot >= tileCount) {
                // Parked off to one side at full size rather than resized to nothing. A zero-sized
                // SurfaceView destroys its Surface, which is exactly what this class exists to
                // avoid; keeping it laid out means it is ready the instant a sender takes the slot.
                tile.layout(w, 0, w + tw.coerceAtLeast(1), th.coerceAtLeast(1))
                return@forEachIndexed
            }
            val col = slot % cols
            val row = slot / cols
            tile.layout(col * tw, row * th, col * tw + tw, row * th + th)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        if (w <= 0 || h <= 0) return
        val cols = if (tileCount <= 1) 1 else 2
        val rows = if (tileCount <= 2) 1 else 2
        val tw = MeasureSpec.makeMeasureSpec(w / cols, MeasureSpec.EXACTLY)
        val th = MeasureSpec.makeMeasureSpec(h / rows, MeasureSpec.EXACTLY)
        tiles.forEach { it.measure(tw, th) }
    }

    /**
     * Shows the tiles that have a session and hides the rest.
     *
     * INVISIBLE, never GONE — see the note in `init`. A hidden tile keeps its Surface so the next
     * sender to land on it starts with somewhere to decode into.
     */
    fun showTiles(activeSlots: Set<Int>) {
        tiles.forEachIndexed { slot, tile ->
            tile.visibility = if (slot in activeSlots || slot == AirPlayReceiver.PRIMARY_SLOT) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }
        setTileCount(if (activeSlots.isEmpty()) 1 else (activeSlots.maxOrNull() ?: 0) + 1)
    }
}
