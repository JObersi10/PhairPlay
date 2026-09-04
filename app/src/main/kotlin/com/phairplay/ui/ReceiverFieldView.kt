package com.phairplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * The receiver field — PhairPlay's one visual motif.
 *
 * The idea is that the receiver is a quiet field sitting inside the television, and that the field
 * reacts when something finds it. It is the same shape in every state; only its energy changes:
 *
 *   IDLE       a soft core, breathing slowly. Almost imperceptible.
 *   DISCOVERY  a single ring travels outward, once.
 *   CONNECTED  rings expand and settle; the core brightens and holds.
 *   STREAMING  the core stays lifted and the breath quickens slightly.
 *
 * It is deliberately NOT a visualiser. Nothing here is driven by audio, nothing bounces, and at
 * idle the whole thing moves by a few percent over six seconds. If you notice it while reading the
 * device name, it is wrong.
 *
 * ## Performance
 *
 * Modelled on [DynamicBackground]'s approach rather than reusing it — that view is driven by
 * decoded PCM (`setEnergy`/`setBands`) and there is no audio on this screen. What is worth copying
 * is how it stays cheap:
 *
 *  - every Paint and Shader is built once, in [onSizeChanged], never in [onDraw];
 *  - one Handler tick drives all animation, and it stops on detach and whenever the state is idle
 *    and settled, so a Home screen left on display eventually stops drawing entirely;
 *  - no allocation per frame — the ring array is fixed and reused;
 *  - [setLowPower] halves the frame rate for weaker sticks.
 */
class ReceiverFieldView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class Mode { IDLE, DISCOVERY, CONNECTED, STREAMING, OFF }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Ring progress 0..1, or -1 for a ring that is not currently travelling. */
    private val rings = FloatArray(RING_COUNT) { -1f }

    private var accent = DEFAULT_ACCENT
    private var mode = Mode.IDLE
    private var lowPower = false

    /**
     * When the user has turned animation off system-wide, the field renders its state but does not
     * move: no breath, no rings, no drifting nodes. Still informative, just still.
     */
    private val motionEnabled = Motion.animationsEnabled(context)

    /** 0..1, eased toward the mode's target so state changes glide rather than snap. */
    private var energy = 0f
    private var targetEnergy = IDLE_ENERGY

    private var phase = 0f
    private var cx = 0f
    private var cy = 0f
    private var maxRadius = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            advance()
            invalidate()
            handler.postDelayed(this, if (lowPower) FRAME_MS_LOW else FRAME_MS)
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Sets the field's state. Safe to call with the value it already holds — a repeat is ignored,
     * so a StateFlow that re-emits the same state will not restart the ripple.
     */
    fun setMode(next: Mode) {
        if (next == mode) return
        val previous = mode
        mode = next
        targetEnergy = when (next) {
            Mode.OFF -> 0f
            Mode.IDLE -> IDLE_ENERGY
            Mode.DISCOVERY -> DISCOVERY_ENERGY
            Mode.CONNECTED, Mode.STREAMING -> CONNECTED_ENERGY
        }
        // Arriving at connected from anywhere below it is the moment worth marking, so the ripple
        // is emitted on the transition rather than continuously while connected.
        if (next == Mode.DISCOVERY || (next == Mode.CONNECTED && previous != Mode.STREAMING)) {
            emitRing()
        }
        ensureRunning()
    }

    /** Tints the field toward a protocol's accent. Kept subtle; the field is never fully coloured. */
    fun setAccent(color: Int) {
        if (color == accent) return
        accent = color
        if (width > 0) buildShaders()
        invalidate()
    }

    fun setLowPower(on: Boolean) { lowPower = on }

    /** Call from the fragment's onPause/onResume so a backgrounded screen costs nothing. */
    fun pause() { running = false; handler.removeCallbacks(tick) }
    fun resume() { ensureRunning() }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureRunning() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        handler.removeCallbacks(tick)
    }

    /**
     * Reports no desired size of its own.
     *
     * The field is decorative and sits behind the panel's text as a `match_parent` child of a
     * `wrap_content` FrameLayout. Without this, the default View measurement answers an AT_MOST
     * spec with the *entire* space offered -- so the field claimed the whole screen and the panel
     * grew to match it, pushing the protocol cards and controls off the bottom. Answering 0 lets
     * the text decide the panel's height; FrameLayout then re-measures match_parent children
     * against that resolved height, which is exactly the behaviour wanted.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun resolve(spec: Int): Int =
            if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(resolve(widthMeasureSpec), resolve(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w * 0.5f
        cy = h * 0.5f
        maxRadius = min(w, h) * 0.9f
        buildShaders()
    }

    private fun buildShaders() {
        if (maxRadius <= 0f) return
        // Built once per size change, never per frame: a RadialGradient allocation inside onDraw at
        // 60fps is the classic way to make a TV UI stutter.
        corePaint.shader = RadialGradient(
            cx, cy, maxRadius * 0.55f,
            intArrayOf(
                withAlpha(accent, 0.42f),
                withAlpha(accent, 0.14f),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun ensureRunning() {
        if (!motionEnabled) { energy = targetEnergy; invalidate(); return }
        if (running || !isAttachedToWindow || mode == Mode.OFF) return
        running = true
        handler.post(tick)
    }

    // ─── Animation ───────────────────────────────────────────────────────────

    private fun advance() {
        phase += if (mode == Mode.STREAMING) BREATH_STEP * 1.6f else BREATH_STEP
        if (phase > TWO_PI) phase -= TWO_PI

        energy += (targetEnergy - energy) * ENERGY_EASE

        var anyRing = false
        for (i in rings.indices) {
            if (rings[i] < 0f) continue
            rings[i] += RING_STEP
            if (rings[i] > 1f) rings[i] = -1f else anyRing = true
        }

        // Stop drawing entirely once nothing is moving. A Home screen sitting on IDLE settles
        // within a second or two and then costs nothing at all until the next state change --
        // except that IDLE itself breathes, so only OFF can truly stop.
        val settled = !anyRing && kotlin.math.abs(targetEnergy - energy) < 0.002f
        if (settled && mode == Mode.OFF) {
            running = false
            handler.removeCallbacks(tick)
        }
    }

    private fun emitRing() {
        val slot = rings.indexOfFirst { it < 0f }
        if (slot >= 0) rings[slot] = 0f
    }

    override fun onDraw(canvas: Canvas) {
        if (maxRadius <= 0f || energy <= 0.001f) return

        // Breath is a few percent of scale, not a pulse. The sine is the only trigonometry per
        // frame and it feeds both the core and the nodes.
        val breath = if (motionEnabled) 1f + sin(phase) * BREATH_DEPTH * (0.4f + energy) else 1f
        val coreAlpha = (energy * 255f).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.scale(breath, breath, cx, cy)
        corePaint.alpha = coreAlpha
        canvas.drawCircle(cx, cy, maxRadius * 0.55f, corePaint)
        canvas.restore()

        // Rings: thin, fading as they travel, never reaching the edge of the panel.
        for (progress in rings) {
            if (progress < 0f) continue
            val r = maxRadius * (0.25f + progress * 0.65f)
            val fade = (1f - progress)
            ringPaint.color = withAlpha(accent, 0.30f * fade * (0.5f + energy))
            ringPaint.strokeWidth = RING_WIDTH_DP * resources.displayMetrics.density * fade
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // A few faint nodes, suggesting devices somewhere out there rather than depicting them.
        // Fixed positions on a slow orbit; there is no particle system here on purpose.
        val nodeAlpha = 0.22f * energy
        if (nodeAlpha > 0.01f) {
            for (i in 0 until NODE_COUNT) {
                val a = phase * NODE_DRIFT + i * (TWO_PI / NODE_COUNT)
                val r = maxRadius * (0.42f + 0.06f * sin(phase + i))
                nodePaint.color = withAlpha(accent, nodeAlpha)
                canvas.drawCircle(
                    cx + r * kotlin.math.cos(a),
                    cy + r * sin(a) * VERTICAL_SQUASH,
                    NODE_RADIUS_DP * resources.displayMetrics.density,
                    nodePaint,
                )
            }
        }
    }

    private fun withAlpha(color: Int, factor: Float): Int = Color.argb(
        (255 * factor).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color),
    )

    private companion object {
        const val RING_COUNT = 3
        const val NODE_COUNT = 5

        const val FRAME_MS = 16L
        const val FRAME_MS_LOW = 33L

        const val TWO_PI = (Math.PI * 2).toFloat()
        /** ~6s per breath at 60fps. Slow enough to read as "alive", not as "animating". */
        const val BREATH_STEP = TWO_PI / 360f
        const val BREATH_DEPTH = 0.035f

        /** ~1.4s for a ring to travel out and fade. */
        const val RING_STEP = 1f / 85f
        const val RING_WIDTH_DP = 1.5f

        const val NODE_RADIUS_DP = 2f
        const val NODE_DRIFT = 0.35f
        /** Panels are wide and short, so the node orbit is flattened to match. */
        const val VERTICAL_SQUASH = 0.55f

        const val ENERGY_EASE = 0.06f
        const val IDLE_ENERGY = 0.34f
        const val DISCOVERY_ENERGY = 0.6f
        const val CONNECTED_ENERGY = 0.85f

        const val DEFAULT_ACCENT = 0xFF4C9AFF.toInt()
    }
}
