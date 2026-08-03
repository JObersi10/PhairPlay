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
            handler.postDelayed(this, 16)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); t1.start(); t2.start(); t3.start(); handler.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); t1.cancel(); t2.cancel(); t3.cancel(); handler.removeCallbacks(tick) }

    fun setEnergy(e: Float) { energyTarget = e }

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

        // Black base required for SCREEN blend
        canvas.drawColor(Color.parseColor("#050505"))

        // Save layer for SCREEN blending
        val sc = canvas.saveLayer(0f, 0f, w, h, null)

        // 4 blob centers — lerp-based, biased to edges
        val cx0 = lerp(0.05f, 0.40f, a1) * w; val cy0 = lerp(0.10f, 0.45f, a2) * h
        val cx1 = lerp(0.95f, 0.60f, a2) * w; val cy1 = lerp(0.05f, 0.50f, a3) * h
        val cx2 = lerp(0.15f, 0.50f, a3) * w; val cy2 = lerp(0.90f, 0.55f, a1) * h
        val cx3 = lerp(0.80f, 0.45f, a1) * w; val cy3 = lerp(0.80f, 0.40f, a3) * h

        // Each blob rides between two palette entries, driven by a drift float, so four blobs
        // express six colours and keep "vibing" without any extra animators.
        val fr = floatArrayOf(a3, 1f - a2, a1, 1f - a3)
        val cs = IntArray(4) { i ->
            val c1 = blend(colors[(i * 2) % PALETTE_SIZE], targets[(i * 2) % PALETTE_SIZE], f)
            val c2 = blend(colors[(i * 2 + 1) % PALETTE_SIZE], targets[(i * 2 + 1) % PALETTE_SIZE], f)
            blend(c1, c2, fr[i])
        }
        blob(canvas, cx0, cy0, r, cs[0], beatAlpha)
        blob(canvas, cx1, cy1, r, cs[1], beatAlpha)
        blob(canvas, cx2, cy2, r, cs[2], beatAlpha)
        blob(canvas, cx3, cy3, r, cs[3], beatAlpha)

        canvas.restoreToCount(sc)

        // Center darken — suppress white hotspot where blobs converge
        val darkR = maxOf(w, h) * 0.35f
        val darkGrad = RadialGradient(w * 0.5f, h * 0.5f, darkR,
            intArrayOf(0x55000000, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        clearPaint.shader = darkGrad
        canvas.drawCircle(w * 0.5f, h * 0.5f, darkR, clearPaint)
        clearPaint.shader = null

        // Darken only where the text actually sits, not a whole screen edge — enough contrast for
        // the title/artist/album to stay legible without muting the rest of the backdrop.
        val fx = textFocusX; val fy = textFocusY; val fr2 = textFocusRadius
        if (fr2 > 0f) {
            val textGrad = RadialGradient(fx * w, fy * h, fr2 * maxOf(w, h),
                intArrayOf(TEXT_DARKEN_ARGB, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            clearPaint.shader = textGrad
            canvas.drawCircle(fx * w, fy * h, fr2 * maxOf(w, h), clearPaint)
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

    private fun blob(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val center = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        val grad = RadialGradient(cx, cy, r, intArrayOf(center, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        blobPaint.shader = grad
        canvas.drawCircle(cx, cy, r, blobPaint)
        blobPaint.shader = null
    }

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

        private val DEFAULTS = arrayOf("#1a1a2e", "#16213e", "#0f3460", "#220033", "#2a1a3e", "#0d2b4e")
    }
}
