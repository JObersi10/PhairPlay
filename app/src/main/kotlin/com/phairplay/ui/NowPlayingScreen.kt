package com.phairplay.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.airplay.SenderDeviceType
import timber.log.Timber

class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onPlayPauseClick: (() -> Unit)? = null
    var onPrevClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null

    // ── State ────────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var positionBaseMs = 0L    // position snapshot when epoch was anchored
    private var positionBaseEpoch = 0L // elapsedRealtime at last anchor
    private var durationMs = 0L
    private var currentTitle: String? = null
    private var isPaused = false
    private var seekMultiplier = 1f

    // ── Idle screensaver state ───────────────────────────────────────────────
    /** Debug HUD (Settings → "Debug overlay"), mirroring the one on [StreamingScreen]. */
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }

    private val debugTick = object : Runnable {
        override fun run() {
            if (com.phairplay.airplay.StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = com.phairplay.airplay.StreamStats.summary()
            } else if (debugView.visibility != GONE) {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, DEBUG_REFRESH_MS)
        }
    }

    // Declared above the init block so onVisibilityChanged can safely run before the views exist.
    private var screensaverEnabled = true
    private var screensaverDelayMs = DEFAULT_SCREENSAVER_MINUTES * 60_000L
    private var screensaverActive = false
    /** Position in [SHIFT_STEPS] — the OLED-style burn-in nudge cycle. */
    private var shiftIndex = 0
    private val enterScreensaver = Runnable { startScreensaver() }
    private val driftRunnable = object : Runnable {
        override fun run() {
            drift(SHIFT_DURATION_MS)
            handler.postDelayed(this, SHIFT_INTERVAL_MS)
        }
    }

    // Artwork currently on screen, kept so a new track can cross-fade out of it.
    private var artworkKey: Int? = null
    private var currentArtDrawable: Drawable? = null

    private val stopSeekRunnable = Runnable { seekMultiplier = 1f }
    fun setSeekMultiplier(m: Float) {
        handler.removeCallbacks(stopSeekRunnable)
        seekMultiplier = m
        if (m != 1f) handler.postDelayed(stopSeekRunnable, 3000)
    }

    /** Called when user presses play/pause — toggles local pause state immediately. */
    fun togglePause() {
        if (isPaused) {
            // resuming: re-anchor epoch
            positionBaseEpoch = SystemClock.elapsedRealtime()
            isPaused = false
        } else {
            // pausing: freeze positionBaseMs at current displayed position
            if (positionBaseEpoch > 0L) {
                positionBaseMs += ((SystemClock.elapsedRealtime() - positionBaseEpoch) * seekMultiplier).toLong()
            }
            positionBaseEpoch = 0L
            isPaused = true
        }
    }

    // ── Timer tick ───────────────────────────────────────────────────────────
    private val positionTick = object : Runnable {
        override fun run() {
            if (positionBaseEpoch > 0L) {
                val elapsed = (SystemClock.elapsedRealtime() - positionBaseEpoch) * seekMultiplier
                val now = positionBaseMs + elapsed.toLong()
                val clamped = if (durationMs > 0L) now.coerceAtMost(durationMs) else now
                timeElapsed.text = formatTime(clamped / 1000.0)
                if (durationMs > 0L) {
                    progressBar.setValue(((clamped.toFloat() / durationMs) * 10000).toInt())
                    timeRemaining.text = "-${formatTime((durationMs - clamped) / 1000.0)}"
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(positionTick) }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(stopSeekRunnable)
        cancelScreensaver()
    }

    // ── Dynamic blob background ───────────────────────────────────────────────
    private val dynamicBg: DynamicBackground
    fun setEnergy(e: Float) = dynamicBg.setEnergy(e)

    // ── Views ────────────────────────────────────────────────────────────────
    private val artworkView: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val albumView: TextView
    private val metaSecondaryView: TextView   // composer · year
    private val progressBar: ProgressView
    private val timeElapsed: TextView
    private val timeRemaining: TextView
    private val pillIcon: ImageView
    private val pillLabel: TextView

    /** The whole art + text block. Held as a field so the screensaver can drift and dim it. */
    private val contentGroup: LinearLayout
    private val pillWrapper: FrameLayout

    /** "Back of the record sleeve" — the extended credits panel toggled with the Menu key. */
    private val infoPanel: LinearLayout
    private val infoRows = LinkedHashMap<String, TextView>()

    init {
        // Pure black behind everything: the screensaver fades the coloured background out to this.
        setBackgroundColor(Color.BLACK)
        dynamicBg = DynamicBackground(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(dynamicBg)

        // ── Content ──────────────────────────────────────────────────────────
        contentGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(72), dp(60), dp(72), dp(60))
        }

        // Album art
        val artWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(280)).also { it.rightMargin = dp(64) }
            elevation = dp(24).toFloat()
        }
        artworkView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
                }
            }
            clipToOutline = true
        }
        artWrapper.addView(artworkView)
        contentGroup.addView(artWrapper)

        // ── Right column ──────────────────────────────────────────────────────
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Each field gets its own full-width row. The title and album used to share a row with
        // the title on weight=1 and the album on WRAP_CONTENT, which let a long album name claim
        // the space and force the title into a marquee — real-world albums run 60+ characters
        // ("Tomorrowland Brasil 2024: Armin van Buuren at Mainstage (DJ Mix)"), so the song name
        // lost the fight almost every time.
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            letterSpacing = -0.02f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(2) }
        }
        artistView = TextView(context).apply {
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(6) }
        }
        // Album now lives on the dim third line alongside year and genre, where it can be long
        // without costing the title any room.
        albumView = TextView(context).apply {
            setTextColor(Color.argb(140, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        metaSecondaryView = TextView(context).apply {
            setTextColor(Color.argb(100, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(24) }
        }

        // Progress bar
        progressBar = ProgressView(context)
        progressBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).also { it.bottomMargin = dp(8) }

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        timeElapsed = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START
        }
        timeRemaining = TextView(context).apply {
            setTextColor(Color.argb(120, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        }
        timeRow.addView(timeElapsed); timeRow.addView(timeRemaining)

        right.addView(titleView)
        right.addView(artistView)
        right.addView(albumView)
        right.addView(metaSecondaryView)
        right.addView(progressBar)
        right.addView(timeRow)

        contentGroup.addView(right)
        addView(contentGroup)

        // ── AirPlay pill ──────────────────────────────────────────────────────
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(60, 255, 255, 255))
                cornerRadius = dp(20).toFloat()
            }
        }
        pillIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also { it.rightMargin = dp(6) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_airplay)
            setColorFilter(Color.WHITE)
        }
        pillLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        pill.addView(pillIcon); pill.addView(pillLabel)

        pillWrapper = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(32)
            }
        }
        pillWrapper.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(pillWrapper)

        infoPanel = buildInfoPanel()
        addView(infoPanel)

        // Same debug HUD as StreamingScreen. Audio-only sessions never show that screen, so with the
        // overlay setting on there was nowhere for the stats to appear during AirPlay audio.
        addView(debugView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.topMargin = DEBUG_MARGIN
            it.leftMargin = DEBUG_MARGIN
        })

        // Long titles scroll instead of clipping. Marquee only animates on a "selected" view, and
        // these are non-focusable, so selection is forced on once here rather than per update.
        listOf(titleView, artistView, albumView, metaSecondaryView).forEach { it.enableMarquee() }
    }

    /** Applies the endless side-scroll used for any text that can overflow its row. */
    private fun TextView.enableMarquee() {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1        // -1 = forever
        isHorizontalFadingEdgeEnabled = true
        isSelected = true              // what actually starts the animation
    }

    // ── Info panel ("back of the sleeve") ─────────────────────────────────────

    /**
     * Builds the credits card that slides up over the bottom of the screen on Menu. It carries the
     * fields the main layout has no room for — genre, composer, year, source — laid out like the
     * back of a record sleeve. Starts hidden and off-screen; [toggleInfoPanel] animates it in.
     */
    private fun buildInfoPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(28))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 12, 12, 14))
                cornerRadii = floatArrayOf(
                    dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            elevation = dp(32).toFloat()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM
            }
            visibility = View.GONE
        }

        panel.addView(TextView(context).apply {
            text = context.getString(R.string.now_playing_info_header)
            setTextColor(Color.argb(110, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.28f
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(View(context).apply {
            setBackgroundColor(Color.argb(38, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                .also { it.topMargin = dp(10); it.bottomMargin = dp(14) }
        })

        // Two columns so six fields fit without pushing the card up past the artwork.
        val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.leftMargin = dp(48) }
        }
        INFO_FIELDS.forEachIndexed { i, label ->
            (if (i < (INFO_FIELDS.size + 1) / 2) left else right).addView(buildInfoRow(label))
        }
        columns.addView(left); columns.addView(right)
        panel.addView(columns)

        panel.addView(TextView(context).apply {
            text = context.getString(R.string.now_playing_info_hint)
            setTextColor(Color.argb(80, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(16) }
        })
        return panel
    }

    /** One "LABEL   value" line; the value TextView is kept in [infoRows] for later updates. */
    private fun buildInfoRow(label: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(9) }
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.argb(95, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val value = TextView(context).apply {
            setTextColor(Color.argb(225, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }.also { it.enableMarquee() }
        row.addView(value)
        infoRows[label] = value
        return row
    }

    /**
     * Shows/hides the credits panel. Returns true if the key was consumed — the caller uses this to
     * decide whether Menu did anything, so it can fall through to default handling when the panel
     * isn't applicable.
     */
    fun toggleInfoPanel(): Boolean {
        wakeFromScreensaver()
        val opening = infoPanel.visibility != View.VISIBLE
        if (opening) {
            infoPanel.visibility = View.VISIBLE
            infoPanel.alpha = 0f
            // Height is only known after layout, so defer the slide until it is measured.
            infoPanel.post {
                infoPanel.translationY = infoPanel.height.toFloat()
                infoPanel.animate().translationY(0f).alpha(1f)
                    .setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            }
            pillWrapper.animate().alpha(0f).setDuration(180).start()
        } else {
            infoPanel.animate().translationY(infoPanel.height.toFloat()).alpha(0f)
                .setDuration(220)
                .withEndAction { infoPanel.visibility = View.GONE }
                .start()
            pillWrapper.animate().alpha(1f).setDuration(240).start()
        }
        return true
    }

    /** Closes the panel if it is open. Returns true if there was something to close. */
    fun dismissInfoPanel(): Boolean {
        if (infoPanel.visibility != View.VISIBLE) return false
        toggleInfoPanel()
        return true
    }

    // ── Custom progress bar ───────────────────────────────────────────────────
    inner class ProgressView(ctx: Context) : View(ctx) {
        private var value = 0
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
        private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, trackPaint)
            val fill = width * (value / 10000f)
            if (fill > 0) canvas.drawRoundRect(0f, 0f, fill, height.toFloat(), r, r, fillPaint)
        }
        fun setValue(v: Int) { value = v; invalidate() }
    }

    // ── update() ─────────────────────────────────────────────────────────────
    fun update(info: NowPlayingInfo) {
        // Sync pause from RTSP PAUSE only (info.paused=true). Never auto-resume from server
        // because Apple Music never sends RTSP PAUSE so info.paused is always false — if we
        // synced resume here it would override local togglePause() on every metadata update.
        if (info.paused && !isPaused) {
            if (positionBaseEpoch > 0L) {
                positionBaseMs += ((SystemClock.elapsedRealtime() - positionBaseEpoch) * seekMultiplier).toLong()
                positionBaseEpoch = 0L
            }
            isPaused = true
        }

        applyArtwork(info.artwork)

        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.apply {
            text = info.artist ?: ""; visibility = if (info.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        albumView.apply {
            text = info.album ?: ""; visibility = if (info.album.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val secondaryParts = listOfNotNull(info.year?.toString(), info.genre?.takeIf { it.isNotBlank() })
        metaSecondaryView.apply {
            text = secondaryParts.joinToString(" · ")
            visibility = if (secondaryParts.isEmpty()) View.GONE else View.VISIBLE
        }

        Timber.d("NowPlaying senderType=${info.senderDeviceType} name=${info.senderName}")
        if (info.senderName == "DLNA") {
            pillIcon.setImageResource(R.drawable.ic_cast)
            pillLabel.text = if (info.title != null) "Playing via DLNA" else "Audio via DLNA"
        } else {
            pillIcon.setImageResource(R.drawable.ic_airplay_audio)
            val genericName = info.senderName == "AirPlay" || info.senderName.isBlank()
            val deviceName = if (genericName) when (info.senderDeviceType) {
                SenderDeviceType.IPHONE  -> "Unknown iPhone"
                SenderDeviceType.IPAD    -> "Unknown iPad"
                SenderDeviceType.MAC     -> "Unknown Mac"
                SenderDeviceType.UNKNOWN -> "Unknown iPhone"
            } else info.senderName
            pillLabel.text = "Audio from $deviceName"
        }

        updateInfoPanel(info)

        if (info.title != currentTitle) {
            currentTitle = info.title
            if (!isPaused) positionBaseEpoch = SystemClock.elapsedRealtime()
            // Seed from the sender's reported position rather than 0. Returning from Home clears
            // currentTitle, so the same track looked like a new one and the elapsed time snapped
            // back to 0:00 until the next progress push — which can be 40s away.
            positionBaseMs = (info.positionSec * 1000).toLong().coerceAtLeast(0L)
            // A track change counts as activity: the screensaver shouldn't cover a song the user
            // just started, and it should re-arm from here rather than from the last button press.
            notifyActivity()
        }

        if (info.durationSec > 0) {
            durationMs = (info.durationSec * 1000).toLong()
            val newPosMs = (info.positionSec * 1000).toLong()
            val expectedMs = if (positionBaseEpoch > 0L)
                positionBaseMs + ((SystemClock.elapsedRealtime() - positionBaseEpoch) * seekMultiplier).toLong()
            else positionBaseMs
            if (Math.abs(newPosMs - expectedMs) > 2000L) {
                positionBaseMs = newPosMs
                if (!isPaused) positionBaseEpoch = SystemClock.elapsedRealtime()
                seekMultiplier = 1f
            }
        } else if (!info.title.isNullOrBlank() && positionBaseEpoch == 0L && !isPaused) {
            positionBaseMs = 0L; positionBaseEpoch = SystemClock.elapsedRealtime()
        }

        val timerRunning = positionBaseEpoch > 0L || isPaused
        progressBar.visibility   = if (timerRunning && durationMs > 0L) View.VISIBLE else View.GONE
        timeElapsed.visibility   = if (timerRunning) View.VISIBLE else View.GONE
        timeRemaining.visibility = if (timerRunning && durationMs > 0L) View.VISIBLE else View.GONE
    }

    /**
     * Swaps in new cover art with a cross-fade. The sender re-sends the same artwork on every
     * metadata push, so the byte hash gates the work: without it every position tick would re-decode
     * the JPEG and restart the fade, making the art flicker once a second.
     */
    private fun applyArtwork(bytes: ByteArray?) {
        val key = bytes?.contentHashCode()
        if (key == artworkKey && currentArtDrawable != null) return
        artworkKey = key

        // Senders push a 0-byte "image/none" placeholder between tracks (visible in the RTSP log as
        // `artwork (0B, image/none)`). Treat it as "no art yet" rather than decoding it, so it can't
        // clear real cover art that is about to arrive a few milliseconds later.
        val payload = bytes?.takeIf { it.isNotEmpty() }
        val bitmap = payload?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        Timber.d("applyArtwork bytes=${bytes?.size ?: -1} decoded=${bitmap != null} " +
                 "size=${bitmap?.width}x${bitmap?.height}")
        if (payload != null && bitmap == null) {
            Timber.w("Artwork decode failed for ${payload.size}B payload — keeping previous art")
            return
        }
        val next: Drawable = if (bitmap != null) {
            dynamicBg.updateColors(bitmap)
            BitmapDrawable(resources, bitmap)
        } else {
            dynamicBg.resetColors()
            // Tint the placeholder itself rather than the ImageView: an ImageView colour filter
            // would also wash out the outgoing artwork for the length of the fade.
            (context.getDrawable(R.drawable.ic_airplay) ?: ColorDrawable(Color.TRANSPARENT))
                .mutate().apply { setTint(Color.parseColor("#66FFFFFF")) }
        }

        val previous = currentArtDrawable
        artworkView.clearColorFilter()
        if (previous == null) {
            // No cross-fade for the first image of a session. startTransition(0) divides elapsed
            // time by a zero duration inside TransitionDrawable.draw(), and Math.min(NaN, 1f) stays
            // NaN, so alpha evaluates to 0 and the layer draws fully transparent.
            artworkView.setImageDrawable(next)
        } else {
            val fade = TransitionDrawable(arrayOf(previous, next))
            fade.isCrossFadeEnabled = true
            artworkView.setImageDrawable(fade)
            fade.startTransition(ARTWORK_FADE_MS)
        }
        currentArtDrawable = next
    }

    /**
     * Shows what the sender's volume slider actually achieved — level, step count and route — so it
     * is obvious whether the hardware took the change or only the app did.
     */
    fun setVolumeReport(display: String?) {
        setInfoValue(FIELD_VOLUME, display)
    }

    private fun updateInfoPanel(info: NowPlayingInfo) {
        setInfoValue(FIELD_TRACK, info.title)
        setInfoValue(FIELD_ARTIST, info.artist)
        setInfoValue(FIELD_ALBUM, info.album)
        setInfoValue(FIELD_GENRE, info.genre)
        setInfoValue(FIELD_COMPOSER, info.composer)
        setInfoValue(FIELD_YEAR, info.year?.toString())
    }

    private fun setInfoValue(label: String, value: String?) {
        infoRows[label]?.text = if (value.isNullOrBlank()) "—" else value
    }

    // ── Idle screensaver ──────────────────────────────────────────────────────

    /** Applies the user's screensaver preferences and re-arms the idle countdown. */
    fun setScreensaverConfig(enabled: Boolean, timeoutMinutes: Int) {
        screensaverEnabled = enabled
        screensaverDelayMs = timeoutMinutes.coerceAtLeast(1) * 60_000L
        notifyActivity()
    }

    /**
     * Call on any remote input or track change: wakes the screensaver if it is showing and restarts
     * the idle countdown. Deliberately NOT called from [update] on every metadata push — the sender
     * pushes position updates about once a second, which would keep the timer permanently reset.
     */
    fun notifyActivity() {
        wakeFromScreensaver()
        handler.removeCallbacks(enterScreensaver)
        if (screensaverEnabled && visibility == View.VISIBLE) {
            handler.postDelayed(enterScreensaver, screensaverDelayMs)
        }
    }

    private fun cancelScreensaver() {
        handler.removeCallbacks(enterScreensaver)
        wakeFromScreensaver()
    }

    /**
     * Fades the coloured background out to black and leaves the art and text breathing and slowly
     * drifting — dim enough to sit on a TV overnight, still readable from the couch.
     */
    private fun startScreensaver() {
        if (screensaverActive || !screensaverEnabled || visibility != View.VISIBLE) return
        // Close the credits panel first: dismissInfoPanel() wakes the screensaver, so it has to run
        // before the active flag is set or it would immediately undo everything below.
        dismissInfoPanel()
        screensaverActive = true
        Timber.d("Now Playing idle — entering screensaver")

        dynamicBg.animate().alpha(0f).setDuration(FADE_TO_BLACK_MS).start()
        pillWrapper.animate().alpha(0f).setDuration(FADE_TO_BLACK_MS).start()
        // Dim once and stay there. The old version pulsed alpha with an ObjectAnimator while drift()
        // ran a ViewPropertyAnimator on the same view — the two fought over contentGroup, so every
        // move was preceded by a visible brighten.
        contentGroup.animate().alpha(SCREENSAVER_MIN_ALPHA).setDuration(FADE_TO_BLACK_MS).start()
        shiftIndex = 0
        handler.postDelayed(driftRunnable, SHIFT_INTERVAL_MS)
    }

    private fun wakeFromScreensaver() {
        if (!screensaverActive) return
        screensaverActive = false
        handler.removeCallbacks(driftRunnable)
        dynamicBg.animate().alpha(1f).setDuration(WAKE_MS).start()
        pillWrapper.animate().alpha(1f).setDuration(WAKE_MS).start()
        contentGroup.animate()
            .alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(WAKE_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * Pixel-shift for burn-in protection, the way an OLED TV does it: a few pixels, on a fixed
     * cycle, slowly enough to be invisible. Deliberately touches only translation — no alpha, no
     * scale — so nothing competes with the dim and the card never flashes brighter as it moves.
     */
    private fun drift(duration: Long) {
        val (dx, dy) = SHIFT_STEPS[shiftIndex % SHIFT_STEPS.size]
        shiftIndex++
        contentGroup.animate()
            .translationX(dx * SHIFT_PX).translationY(dy * SHIFT_PX)
            .setDuration(duration).setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility == View.VISIBLE) {
            notifyActivity()
            handler.removeCallbacks(debugTick)
            handler.post(debugTick)
        } else {
            cancelScreensaver()
            handler.removeCallbacks(debugTick)
        }
    }

    /**
     * Resets the card between sessions.
     *
     * Deliberately keeps the decoded artwork and its cache key: this runs every time the overlay is
     * hidden, including when the user presses Home, and dropping the bitmap forced a fresh
     * main-thread decode of a ~200 KB JPEG plus a Palette pass on the way back in — which is what
     * made returning to the audio screen feel slow.
     */
    fun clear() {
        cancelScreensaver()
        infoPanel.visibility = View.GONE
        pillWrapper.alpha = 1f
        positionBaseEpoch = 0L; positionBaseMs = 0L; durationMs = 0L
        currentTitle = null; isPaused = false; seekMultiplier = 1f
        progressBar.setValue(0)
        progressBar.visibility = View.GONE
        timeElapsed.visibility = View.GONE
        timeRemaining.visibility = View.GONE
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        // Info-panel row labels. These double as the keys of `infoRows`, so they are plain
        // constants rather than string resources — a locale switch must not orphan the map.
        private const val FIELD_TRACK    = "TRACK"
        private const val FIELD_ARTIST   = "ARTIST"
        private const val FIELD_ALBUM    = "ALBUM"
        private const val FIELD_GENRE    = "GENRE"
        private const val FIELD_COMPOSER = "COMPOSER"
        private const val FIELD_YEAR     = "YEAR"
        private const val FIELD_VOLUME   = "VOLUME"
        private val INFO_FIELDS = listOf(
            FIELD_TRACK, FIELD_ARTIST, FIELD_ALBUM,
            FIELD_GENRE, FIELD_COMPOSER, FIELD_YEAR, FIELD_VOLUME
        )

        private const val ARTWORK_FADE_MS = 450

        /** Screensaver default, mirrored by AppSettings.screensaverTimeoutMin. */
        const val DEFAULT_SCREENSAVER_MINUTES = 15

        /** Debug HUD refresh cadence and inset, matching StreamingScreen. */
        private const val DEBUG_REFRESH_MS = 500L
        private const val DEBUG_MARGIN = 32

        private const val FADE_TO_BLACK_MS = 2500L
        private const val WAKE_MS = 600L
        private const val BREATHE_MS = 7000L
        private const val SCREENSAVER_MIN_ALPHA = 0.32f
        private const val SCREENSAVER_SCALE = 0.82f
        /** How often to nudge, how long the nudge takes, and how far — a handful of pixels. */
        private const val SHIFT_INTERVAL_MS = 60_000L
        private const val SHIFT_DURATION_MS = 2_000L
        private const val SHIFT_PX = 8f

        /** Fixed cycle of unit offsets, so the card creeps around a small box and returns. */
        private val SHIFT_STEPS = listOf(
            0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f,
            -1f to 1f, -1f to 0f, -1f to -1f, 0f to -1f, 1f to -1f,
        )
    }
}
