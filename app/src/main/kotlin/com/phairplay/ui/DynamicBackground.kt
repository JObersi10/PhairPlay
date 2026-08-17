package com.phairplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.palette.graphics.Palette

class DynamicBackground @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Per-frame scratch, allocated once ────────────────────────────────────
    // onDraw runs ~60x/sec; anything new()'d in there is pure garbage-collector pressure.
    private val blobMix = FloatArray(4)
    private val blobColors = IntArray(4)
    private var textGrad: RadialGradient? = null
    private var textGradX = Float.NaN
    private var textGradY = Float.NaN
    private var textGradR = Float.NaN

    // Six palette colours; four blobs each lerp between two of them so the backdrop keeps
    // shifting even on a paused track. Cross-faded on song change.
    private val colors = IntArray(PALETTE_SIZE) { Color.parseColor(DEFAULTS[it % DEFAULTS.size]) }
    private val targets = IntArray(PALETTE_SIZE) { Color.parseColor(DEFAULTS[it % DEFAULTS.size]) }
    private var colorFade = 1f

    private var energy = 0f
    private var energyTarget = 0f

    // 3 prime-period animators 0→1 REVERSE
    private val t1 = ValueAnimator.ofFloat(0f, 1f).apply { duration = 20_000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator() }
    private val t2 = ValueAnimator.ofFloat(0f, 1f).apply { duration = 27_000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator() }
    private val t3 = ValueAnimator.ofFloat(0f, 1f).apply { duration = 34_000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator() }

    private val colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        addUpdateListener { colorFade = it.animatedValue as Float }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            energy += (energyTarget - energy) * 0.22f
            invalidate()
            // Half rate in a PiP window. The backdrop is then a few hundred pixels wide and nobody
            // is studying it, but the full-rate redraw competes for CPU with the video decoder and
            // the audio writer -- which is exactly when the hiccups were reported.
            handler.postDelayed(this, if (lowPower) 33L else 16L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); t1.start(); t2.start(); t3.start(); handler.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); t1.cancel(); t2.cancel(); t3.cancel(); handler.removeCallbacks(tick) }

    fun setEnergy(e: Float) { energyTarget = e }

    /** Halves the redraw rate — set while the window is a PiP thumbnail. */
    fun setLowPower(on: Boolean) { lowPower = on }
    private var lowPower = false

    fun updateColors(bitmap: Bitmap) {
        Palette.from(bitmap).maximumColorCount(7).generate { palette ->
            if (palette == null) return@generate
            val swatches = listOfNotNull(
                palette.vibrantSwatch, palette.darkVibrantSwatch, palette.mutedSwatch,
                palette.lightVibrantSwatch, palette.darkMutedSwatch, palette.lightMutedSwatch,
                palette.dominantSwatch
            ).sortedByDescending { it.population }.map { s ->
                // Push toward vivid. The value ceiling matters most: a pale, high-value swatch
                // reads as light grey and washes out the text on top, so cap value and floor
                // saturation to force deep colour rather than haze. A plain saturation filter was
                // tried upstream and stripped vivid pinks and teals.
                val hsv = FloatArray(3)
                Color.colorToHSV(s.rgb, hsv)
                hsv[1] = (hsv[1] * SAT_BOOST).coerceIn(SAT_FLOOR, 1f)
                hsv[2] = hsv[2].coerceAtMost(VALUE_CEILING)
                Color.HSVToColor(hsv)
            }
            if (swatches.isEmpty()) return@generate
            val spread = spreadByHue(swatches)
            for (i in 0 until PALETTE_SIZE) targets[i] = spread[i % spread.size]
            colorFade = 0f; colorAnim.cancel(); colorAnim.start()
        }
    }

    fun resetColors() {
        for (i in 0 until PALETTE_SIZE) targets[i] = Color.parseColor(DEFAULTS[i % DEFAULTS.size])
        colorAnim.cancel(); colorAnim.start()
    }

    override fun onDraw(canvas: Canvas) {
        val f = colorFade
        if (f >= 1f) for (i in 0 until PALETTE_SIZE) colors[i] = targets[i]

        val w = width.toFloat(); val h = height.toFloat()
        val a1 = t1.animatedValue as Float
        val a2 = t2.animatedValue as Float
        val a3 = t3.animatedValue as Float

        val e = (energy * beatMultiplier).coerceIn(0f, 1f)
        val beatScale = 1f + e * 0.25f
        val beatAlpha = 0.66f + e * 0.22f
        val r = maxOf(w, h) * 0.62f * beatScale

        // Black base required for SCREEN blend. Projector mode uses TRUE black rather than the
        // near-black used on a TV: #050505 is a deliberate lift that stops OLED/LCD panels crushing
        // shadows, but on a projector any non-zero value is light thrown onto a wall, which turns
        // the "invisible" edge into a visible grey rectangle.
        canvas.drawColor(if (projectorMode) Color.BLACK else BASE_COLOR)

        // Save layer for SCREEN blending
        val sc = canvas.saveLayer(0f, 0f, w, h, null)

        // 4 blob centers — lerp-based, biased to edges.
        //
        // PROJECTOR MODE pulls them toward the middle. Normally the blobs deliberately hug the
        // edges and bleed off-screen, which looks right on a TV with a bezel but puts a hard,
        // straight cut exactly where a projector has no edge at all. [pull] compresses every centre
        // toward 0.5 so the whole composition sits inside the frame and the vignette below can
        // dissolve it into black without ever clipping a blob against a boundary.
        fun px(v: Float) = (0.5f + (v - 0.5f) * centreBias) * w
        fun py(v: Float) = (0.5f + (v - 0.5f) * centreBias) * h
        val cx0 = px(lerp(0.05f, 0.40f, a1)); val cy0 = py(lerp(0.10f, 0.45f, a2))
        val cx1 = px(lerp(0.95f, 0.60f, a2)); val cy1 = py(lerp(0.05f, 0.50f, a3))
        val cx2 = px(lerp(0.15f, 0.50f, a3)); val cy2 = py(lerp(0.90f, 0.55f, a1))
        val cx3 = px(lerp(0.80f, 0.45f, a1)); val cy3 = py(lerp(0.80f, 0.40f, a3))

        // Each blob rides between two palette entries, driven by a drift float, so four blobs
        // express six colours and keep "vibing" without any extra animators.
        // Reused rather than reallocated: this runs on every frame, and two fresh arrays per draw
        // is exactly the garbage that shows up as stutter on a low-end Fire TV.
        blobMix[0] = a3; blobMix[1] = 1f - a2; blobMix[2] = a1; blobMix[3] = 1f - a3
        val cs = blobColors
        for (i in 0 until 4) {
            val c1 = blend(colors[(i * 2) % PALETTE_SIZE], targets[(i * 2) % PALETTE_SIZE], f)
            val c2 = blend(colors[(i * 2 + 1) % PALETTE_SIZE], targets[(i * 2 + 1) % PALETTE_SIZE], f)
            cs[i] = blend(c1, c2, blobMix[i])
        }
        blob(canvas, 0, cx0, cy0, r, cs[0], beatAlpha)
        blob(canvas, 1, cx1, cy1, r, cs[1], beatAlpha)
        blob(canvas, 2, cx2, cy2, r, cs[2], beatAlpha)
        blob(canvas, 3, cx3, cy3, r, cs[3], beatAlpha)

        canvas.restoreToCount(sc)

        // PROJECTOR MODE: dissolve the image into black on every side.
        //
        // A projector paints no light for black, so a frame that fades to true black before it
        // reaches its own boundary appears to have no boundary — the visual floats. This is drawn
        // AFTER the blobs and outside the SCREEN layer, so it darkens the composite rather than
        // participating in the blend.
        //
        // VIGNETTE_CLEAR_STOP is what keeps the beat from clipping: the gradient stays fully
        // transparent out to that fraction of the radius, and the centre-biased blobs above (even
        // at their 1.25x beat peak) stay inside it, so the pulse is never cut into.
        if (projectorMode) {
            val rad = maxOf(w, h) * VIGNETTE_RADIUS
            if (vignette == null || vignetteR != rad || vignetteW != w || vignetteH != h) {
                vignetteR = rad; vignetteW = w; vignetteH = h
                vignette = RadialGradient(w / 2f, h / 2f, rad,
                    VIGNETTE_COLORS, VIGNETTE_STOPS, Shader.TileMode.CLAMP)
            }
            clearPaint.shader = vignette
            canvas.drawRect(0f, 0f, w, h, clearPaint)
            clearPaint.shader = null
        }

        // Darken only where the text actually sits, not a whole screen edge — enough contrast for
        // the title/artist/album to stay legible without muting the rest of the backdrop.
        val fx = textFocusX; val fy = textFocusY; val fr2 = textFocusRadius
        if (fr2 > 0f) {
            // Rebuilt only when its geometry actually changes; RadialGradient allocates natively
            // and this used to happen on every single frame.
            val radius = fr2 * maxOf(w, h)
            if (textGrad == null || textGradX != fx * w || textGradY != fy * h || textGradR != radius) {
                textGradX = fx * w; textGradY = fy * h; textGradR = radius
                textGrad = RadialGradient(textGradX, textGradY, radius,
                    TEXT_GRAD_COLORS, TEXT_GRAD_STOPS, Shader.TileMode.CLAMP)
            }
            clearPaint.shader = textGrad
            canvas.drawCircle(textGradX, textGradY, radius, clearPaint)
            clearPaint.shader = null
        }
    }

    /**
     * Where the text block sits, in fractions of the view, so the backdrop can darken just that
     * area. Set by NowPlayingScreen once it has laid out.
     */
    fun setTextFocus(xFraction: Float, yFraction: Float, radiusFraction: Float) {
        textFocusX = xFraction; textFocusY = yFraction; textFocusRadius = radiusFraction
    }

    /** Beat Pulse strength from Settings: Normal 1x, Strong 2x, Insane 3.5x. */
    fun setBeatMultiplier(m: Float) { beatMultiplier = m }

    /**
     * Turns the edgeless projector look on or off.
     *
     * Cheap enough to call whenever the setting changes: it only flips a flag and drops the cached
     * gradient, and the next frame rebuilds whatever it needs.
     */
    fun setProjectorMode(on: Boolean) {
        if (projectorMode == on) return
        projectorMode = on
        centreBias = if (on) PROJECTOR_CENTRE_BIAS else 1f
        vignette = null
        invalidate()
    }

    private var projectorMode = false

    /** 1f leaves blob centres where they are; below 1f compresses them toward the middle. */
    private var centreBias = 1f

    private var vignette: RadialGradient? = null
    private var vignetteR = 0f
    private var vignetteW = 0f
    private var vignetteH = 0f

    /**
     * Picks colours at least [HUE_MIN_ANGLE] apart so the blobs don't all land on one shade.
     * Short-falls are topped up with the closest remaining non-duplicates.
     */
    private fun spreadByHue(source: List<Int>): List<Int> {
        val picked = mutableListOf<Int>()
        val hsv = FloatArray(3)
        for (c in source) {
            if (picked.size >= PALETTE_SIZE) break
            Color.colorToHSV(c, hsv)
            val hue = hsv[0]
            val clash = picked.any { p ->
                Color.colorToHSV(p, hsv)
                val d = Math.abs(hsv[0] - hue)
                minOf(d, 360f - d) < HUE_MIN_ANGLE
            }
            if (!clash) picked += c
        }
        for (c in source) {
            if (picked.size >= PALETTE_SIZE) break
            if (c !in picked) picked += c
        }
        while (picked.size < PALETTE_SIZE && picked.isNotEmpty()) picked += picked[picked.size % picked.size.coerceAtLeast(1)]
        return picked.ifEmpty { DEFAULTS.map { Color.parseColor(it) } }
    }

    /**
     * Draws one blob, reusing its gradient across frames.
     *
     * This used to build a RadialGradient per blob per frame — four native allocations every frame,
     * ~240 a second, in the one method whose own header says allocating in onDraw is pure GC
     * pressure. The pauses that caused are not confined to the UI thread: they showed up as audio
     * hiccups, because a collection stops the audio writer too.
     *
     * The fix is to separate what actually changes. Position and radius change every frame but are
     * pure geometry, so the gradient is built once at unit scale about the origin and moved with a
     * local matrix, which costs nothing. Colour changes slowly, so the gradient is rebuilt only when
     * the quantised colour actually differs — a few times a track instead of sixty times a second.
     * Alpha rides on the Paint, where it was always free.
     */
    private fun blob(canvas: Canvas, i: Int, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        if (r <= 0f) return
        // 5 bits per channel: far finer than the eye can follow on a slow crossfade, and it turns a
        // continuously-changing int into one that holds still for many frames.
        val key = color and 0xF8F8F8.toInt()
        if (blobGrads[i] == null || blobGradKeys[i] != key) {
            blobGradKeys[i] = key
            blobGrads[i] = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(key or 0xFF000000.toInt(), key and 0xFFFFFF),
                BLOB_STOPS, Shader.TileMode.CLAMP,
            )
        }
        val grad = blobGrads[i] ?: return
        blobMatrix.setScale(r, r)
        blobMatrix.postTranslate(cx, cy)
        grad.setLocalMatrix(blobMatrix)
        blobPaint.shader = grad
        blobPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, blobPaint)
        blobPaint.shader = null
        blobPaint.alpha = 255
    }

    private val blobGrads = arrayOfNulls<RadialGradient>(4)
    private val blobGradKeys = IntArray(4) { -1 }
    private val blobMatrix = android.graphics.Matrix()

    private fun blend(c1: Int, c2: Int, f: Float): Int {
        val i = 1f - f
        return Color.rgb(
            (Color.red(c1) * i + Color.red(c2) * f).toInt().coerceIn(0,255),
            (Color.green(c1) * i + Color.green(c2) * f).toInt().coerceIn(0,255),
            (Color.blue(c1) * i + Color.blue(c2) * f).toInt().coerceIn(0,255)
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    @Volatile private var beatMultiplier = 1f
    private var textFocusX = 0f
    private var textFocusY = 0f
    private var textFocusRadius = 0f

    companion object {

        private const val PALETTE_SIZE = 6

        /** Vividness push. Do not raise VALUE_CEILING past ~0.85 — that is where text starts to lose. */
        private const val SAT_BOOST = 1.45f
        private const val SAT_FLOOR = 0.55f
        private const val VALUE_CEILING = 0.80f

        /** Minimum hue separation between chosen palette colours, in degrees. */
        private const val HUE_MIN_ANGLE = 28f

        /** Darkness directly under the text block; the gradient fades to nothing from there. */
        private const val TEXT_DARKEN_ARGB = 0x8C000000.toInt()

        /** Colours and stops for the text scrim. Constant, so they are shared, not rebuilt per frame. */
        /** The near-black TV base. Lifted off zero so panels do not crush shadows. */
        private val BASE_COLOR = Color.parseColor("#050505")

        /**
         * How far projector mode pulls the blob centres toward the middle.
         *
         * 0.55 keeps the composition well inside the vignette's clear zone even when a loud beat
         * scales a blob by 1.25x, which is the whole reason the pulse never clips.
         */
        private const val PROJECTOR_CENTRE_BIAS = 0.55f

        /** Vignette reach, as a fraction of the longest edge. */
        private const val VIGNETTE_RADIUS = 0.78f

        /**
         * Fully transparent out to 0.45 so the centre is untouched, then a long ramp to opaque
         * black. The ramp is deliberately gradual — a short one reads as a dark ring rather than
         * as the image simply running out of light.
         */
        private val VIGNETTE_COLORS = intArrayOf(0x00000000, 0x00000000, 0x80000000.toInt(), 0xFF000000.toInt())
        private val VIGNETTE_STOPS = floatArrayOf(0f, 0.45f, 0.75f, 1f)

        private val BLOB_STOPS = floatArrayOf(0f, 1f)
        private val TEXT_GRAD_COLORS = intArrayOf(TEXT_DARKEN_ARGB, 0x00000000)
        private val TEXT_GRAD_STOPS = floatArrayOf(0f, 1f)

        private val DEFAULTS = arrayOf("#1a1a2e", "#16213e", "#0f3460", "#220033", "#2a1a3e", "#0d2b4e")
    }
}
