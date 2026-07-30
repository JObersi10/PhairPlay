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

    // 4 blob colors — cross-fade on song change
    private val colors = IntArray(4) { Color.parseColor(DEFAULTS[it % DEFAULTS.size]) }
    private val targets = IntArray(4) { Color.parseColor(DEFAULTS[it % DEFAULTS.size]) }
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
            ).map { s ->
                val r = Color.red(s.rgb); val g = Color.green(s.rgb); val b = Color.blue(s.rgb)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val sc = if (lum > 140f) 140f / lum else 1f
                Color.rgb((r * sc).toInt().coerceIn(0,255), (g * sc).toInt().coerceIn(0,255), (b * sc).toInt().coerceIn(0,255))
            }
            if (swatches.isEmpty()) return@generate
            for (i in 0..3) targets[i] = swatches[i % swatches.size]
            colorFade = 0f; colorAnim.cancel(); colorAnim.start()
        }
    }

    fun resetColors() {
        for (i in 0..3) targets[i] = Color.parseColor(DEFAULTS[i % DEFAULTS.size])
        colorAnim.cancel(); colorAnim.start()
    }

    override fun onDraw(canvas: Canvas) {
        val f = colorFade
        if (f >= 1f) for (i in 0..3) colors[i] = targets[i]

        val w = width.toFloat(); val h = height.toFloat()
        val a1 = t1.animatedValue as Float
        val a2 = t2.animatedValue as Float
        val a3 = t3.animatedValue as Float

        val beatScale = 1f + energy * 0.18f
        val beatAlpha = 0.52f + energy * 0.12f
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

        blob(canvas, cx0, cy0, r, blend(colors[0], targets[0], f), beatAlpha)
        blob(canvas, cx1, cy1, r, blend(colors[1], targets[1], f), beatAlpha)
        blob(canvas, cx2, cy2, r, blend(colors[2], targets[2], f), beatAlpha)
        blob(canvas, cx3, cy3, r, blend(colors[3], targets[3], f), beatAlpha)

        canvas.restoreToCount(sc)

        // Center darken — suppress white hotspot where blobs converge
        val darkR = maxOf(w, h) * 0.35f
        val darkGrad = RadialGradient(w * 0.5f, h * 0.5f, darkR,
            intArrayOf(0x55000000, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        clearPaint.shader = darkGrad
        canvas.drawCircle(w * 0.5f, h * 0.5f, darkR, clearPaint)
        clearPaint.shader = null
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

    companion object {
        private val DEFAULTS = arrayOf("#1a1a2e", "#16213e", "#0f3460", "#220033")
    }
}
