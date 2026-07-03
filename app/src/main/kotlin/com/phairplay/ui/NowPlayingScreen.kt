package com.phairplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.palette.graphics.Palette
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.lyrics.LyricLine
import com.phairplay.lyrics.LyricsPanel
import com.phairplay.util.Logger

class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onPlayPauseClick: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var positionBaseMs = 0L
    private var positionBaseEpoch = 0L
    private var durationMs = 0L
    private var currentTitle: String? = null

    private val positionTick = object : Runnable {
        override fun run() {
            if (positionBaseEpoch > 0L) {
                val now = positionBaseMs + (SystemClock.elapsedRealtime() - positionBaseEpoch)
                val clamped = if (durationMs > 0L) now.coerceAtMost(durationMs) else now
                progressMsState = clamped
                timeElapsed.text = formatTime(clamped / 1000.0)
                if (durationMs > 0L) {
                    progressBar.progress = ((clamped.toFloat() / durationMs) * 1000).toInt()
                    timeRemaining.text = "-${formatTime((durationMs - clamped) / 1000.0)}"
                } else {
                    progressBar.progress = 0
                    timeRemaining.text = ""
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(positionTick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacks(positionTick) }

    private val bgGradient = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TL_BR
        colors = intArrayOf(Color.parseColor("#1a1a2e"), Color.parseColor("#16213e"))
    }

    // Art + metadata column (left side when lyrics present)
    private val artwork: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val senderView: TextView
    private val progressBar: ProgressBar
    private val timeElapsed: TextView
    private val timeRemaining: TextView
    private val artColumn: LinearLayout

    // Compose view for lyrics (right side)
    private val lyricsComposeView: ComposeView

    // Compose state
    private var lyricsState by mutableStateOf<List<LyricLine>>(emptyList())
    private var progressMsState by mutableLongStateOf(0L)
    private var durationMsState by mutableLongStateOf(0L)

    init {
        background = bgGradient
        isFocusable = true
        isClickable = true
        setOnClickListener { onPlayPauseClick?.invoke() }

        // ── Art + metadata column ──────────────────────────────────────────
        artColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), 0, dp(48), 0)
        }

        artwork = ImageView(context).apply {
            val size = dp(280)
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.bottomMargin = dp(32)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Rounded corners
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }

        titleView = textView(28f, Color.WHITE, bold = true).apply {
            setPadding(0, 0, 0, dp(6))
            maxLines = 2
            gravity = Gravity.CENTER
        }
        artistView = textView(18f, Color.parseColor("#BFFFFFFF")).apply {
            setPadding(0, 0, 0, dp(28))
            gravity = Gravity.CENTER
        }

        // Progress bar + times
        val progressWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).also { it.bottomMargin = dp(8) }
            max = 1000
            progress = 0
            progressDrawable = android.graphics.drawable.ClipDrawable(
                android.graphics.drawable.ColorDrawable(Color.WHITE),
                android.view.Gravity.START,
                android.graphics.drawable.ClipDrawable.HORIZONTAL
            )
            background = android.graphics.drawable.ColorDrawable(Color.parseColor("#33FFFFFF"))
        }
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        timeElapsed = textView(14f, Color.parseColor("#AAFFFFFF")).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START
        }
        timeRemaining = textView(14f, Color.parseColor("#AAFFFFFF")).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        }
        timeRow.addView(timeElapsed)
        timeRow.addView(timeRemaining)
        progressWrapper.addView(progressBar)
        progressWrapper.addView(timeRow)

        senderView = textView(12f, Color.parseColor("#55FFFFFF")).apply {
            setPadding(0, dp(20), 0, 0)
            gravity = Gravity.CENTER
        }

        artColumn.addView(artwork)
        artColumn.addView(titleView)
        artColumn.addView(artistView)
        artColumn.addView(progressWrapper)
        artColumn.addView(senderView)

        // ── Lyrics ComposeView ─────────────────────────────────────────────
        lyricsComposeView = ComposeView(context).apply {
            visibility = View.GONE
            setContent {
                MaterialTheme {
                    LyricsPanel(
                        lyrics = lyricsState,
                        progressMs = progressMsState,
                        durationMs = durationMsState,
                        onSeek = { ms -> onSeek?.invoke(ms) }
                    )
                }
            }
        }

        // Root horizontal layout: art left, lyrics right
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(artColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(lyricsComposeView, LinearLayout.LayoutParams(0, 1080, 1f))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Must be called after the screen is added to the window so Compose lifecycle owners are set. */
    fun attachLifecycleOwners(lifecycleOwner: LifecycleOwner, savedStateOwner: SavedStateRegistryOwner) {
        lyricsComposeView.setViewTreeLifecycleOwner(lifecycleOwner)
        lyricsComposeView.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        (lifecycleOwner as? ViewModelStoreOwner)?.let { lyricsComposeView.setViewTreeViewModelStoreOwner(it) }
    }

    fun update(info: NowPlayingInfo) {
        val bitmap = info.artwork?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }

        if (bitmap != null) {
            artwork.setImageBitmap(bitmap)
            artwork.colorFilter = null
            applyPaletteBackground(bitmap)
        } else {
            artwork.setImageResource(R.drawable.ic_airplay)
            artwork.setColorFilter(Color.parseColor("#66FFFFFF"))
            bgGradient.colors = intArrayOf(Color.parseColor("#1a1a2e"), Color.parseColor("#16213e"))
        }

        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.apply {
            text = info.artist ?: ""
            visibility = if (info.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        senderView.text = context.getString(R.string.now_playing_from, info.senderName)

        // Reset anchor when song changes
        if (info.title != currentTitle) {
            currentTitle = info.title
            positionBaseEpoch = 0L
            positionBaseMs = 0L
        }

        if (info.durationSec > 0) {
            // Received explicit position from AirPlay SET_PARAMETER
            durationMs = (info.durationSec * 1000).toLong()
            durationMsState = durationMs
            val newPosMs = (info.positionSec * 1000).toLong()
            val expectedMs = if (positionBaseEpoch > 0L)
                positionBaseMs + (SystemClock.elapsedRealtime() - positionBaseEpoch)
            else -1L
            if (expectedMs < 0L || Math.abs(newPosMs - expectedMs) > 2000L) {
                positionBaseMs = newPosMs
                positionBaseEpoch = SystemClock.elapsedRealtime()
            }
        } else if (!info.title.isNullOrBlank() && positionBaseEpoch == 0L) {
            // Apple Music AirPlay 2 never sends position — start timer from 0 when song metadata arrives
            positionBaseMs = 0L
            positionBaseEpoch = SystemClock.elapsedRealtime()
        }

        val timerRunning = positionBaseEpoch > 0L
        progressBar.visibility = if (timerRunning) View.VISIBLE else View.GONE
        timeElapsed.visibility = if (timerRunning) View.VISIBLE else View.GONE
        timeRemaining.visibility = if (timerRunning) View.VISIBLE else View.GONE
    }

    fun setLyrics(lines: List<LyricLine>) {
        lyricsState = lines
        lyricsComposeView.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    fun clear() {
        artwork.setImageDrawable(null)
        lyricsState = emptyList()
        lyricsComposeView.visibility = View.GONE
        positionBaseEpoch = 0L
        positionBaseMs = 0L
        durationMs = 0L
        durationMsState = 0L
        currentTitle = null
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        timeElapsed.visibility = View.GONE
        timeRemaining.visibility = View.GONE
    }

    private fun applyPaletteBackground(bitmap: Bitmap) {
        Palette.from(bitmap).maximumColorCount(8).generate { palette ->
            if (palette == null) return@generate
            val c1 = palette.getDarkVibrantColor(palette.getDarkMutedColor(Color.parseColor("#1a1a2e")))
            val c2 = palette.getMutedColor(palette.getDominantColor(Color.parseColor("#0d0d1a")))
            bgGradient.colors = intArrayOf(darken(c1, 0.55f), darken(c2, 0.35f))
        }
    }

    private fun darken(color: Int, f: Float) = Color.rgb(
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255)
    )

    private fun formatTime(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun textView(sizeSp: Float, textColor: Int, bold: Boolean = false) =
        TextView(context).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            gravity = Gravity.CENTER
            maxLines = 2
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) {
            val lp = lyricsComposeView.layoutParams as LinearLayout.LayoutParams
            lp.height = h
            lyricsComposeView.layoutParams = lp
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
