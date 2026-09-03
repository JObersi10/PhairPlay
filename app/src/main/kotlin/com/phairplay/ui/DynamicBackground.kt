package com.phairplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.Choreographer
import androidx.palette.graphics.Palette
import com.phairplay.settings.BackdropTheme
import com.phairplay.util.Logger
import kotlin.math.pow

class DynamicBackground @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Half-resolution composite buffer ─────────────────────────────────────
    // Everything this view draws is a wide, soft radial gradient — there is no high-frequency
    // detail for a half-res grid to lose — so the whole field is composited at half width and
    // height and blitted up with one bilinear pass. That quarters every full-screen fill (the
    // opaque base, four blobs, three orbs of two gradients each, the vignette), which is the ONLY
    // thing that was over budget: gfxinfo measured ~19ms of GPU on ten-odd full-screen SCREEN
    // passes at 1080p, Slow-UI-thread and Missed-Vsync both zero. A quarter of that clears 16.6ms,
    // which is why FRAME_STRIDE can now be 1. Allocated once per size (~2 MB), reused every frame;
    // recycled on detach.
    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var scratchW = 0
    private var scratchH = 0
    private val blitDst = android.graphics.Rect()
    // FILTER_BITMAP = bilinear on the upscale, so the half-res grid does not show as blockiness.
    private val upscalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Ensures [scratch] is a half-resolution ARGB buffer for a [w]x[h] view. Rounds each axis up so
     * an odd dimension never drops its last column/row on the scale-back. Returns false (and the
     * caller falls back to full-res) only if allocation fails — half res of 1080p is ~2 MB, but the
     * Fire TV's low-memory killer is real, so an OOM here must degrade rather than crash.
     */
    private fun ensureScratch(w: Int, h: Int): Boolean {
        val bw = (w + 1) / 2
        val bh = (h + 1) / 2
        if (bw <= 0 || bh <= 0) return false
        if (scratch == null || scratchW != bw || scratchH != bh) {
            scratch?.recycle()
            val bmp = try {
                Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                scratch = null; scratchCanvas = null; scratchW = 0; scratchH = 0
                return false
            }
            scratch = bmp
            scratchCanvas = Canvas(bmp)
            scratchW = bw; scratchH = bh
        }
        return true
    }

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
    private val t1 = ValueAnimator.ofFloat(0f, 1f).apply { duration = ORBIT_MS[0]; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator() }
    private val t2 = ValueAnimator.ofFloat(0f, 1f).apply { duration = ORBIT_MS[1]; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator() }
    private val t3 = ValueAnimator.ofFloat(0f, 1f).apply { duration = ORBIT_MS[2]; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator() }

    private val colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        addUpdateListener { colorFade = it.animatedValue as Float }
    }

    private val choreographer: Choreographer = Choreographer.getInstance()
    /** Frame time of the previous callback, for the elapsed-time smoothing below. 0 = first frame. */
    private var lastFrameNs = 0L
    /** Vsync counter for the fixed draw stride. */
    private var frameCount = 0
    private var frameLoopRunning = false

    /**
     * The redraw loop, driven by the display's own vsync rather than a fixed delay.
     *
     * It used to be `handler.postDelayed(this, 33L)`. Two things were wrong with that, and the
     * visible one is not the frame rate:
     *
     * **A 33 ms period beats against a 16.67 ms refresh.** The orbs are drawn wherever the orbit
     * animators happen to be when the Runnable fires, and that instant drifts through the vsync
     * interval — so a frame is held for two refreshes, then three, then two, forever. The motion is
     * mathematically perfectly smooth and looks like it is stuttering, which is exactly the
     * complaint. Nothing about a fixed `postDelayed` can be in phase with the display; only a
     * Choreographer callback is. (Apple's equivalent, and the reason Music's visuals look glued to
     * the screen, is CADisplayLink.)
     *
     * **The orbs move continuously, even when the levels do not.** The old comment justified 30fps
     * on the grounds that band levels only arrive every 33 ms, so a 60fps redraw would be showing
     * data that had not changed. That is true of the *swell* and false of the *position*: t1/t2/t3
     * run 20/27/34-second orbits that are a different value at every single vsync. Halving the
     * sample rate of a continuous motion halves its smoothness no matter how slowly its brightness
     * changes.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameLoopRunning) return

            // Clamped: a GC pause or a backgrounded window can hand back an arbitrarily large gap,
            // and letting that through would snap every orb straight to its target in one step.
            val dtMs = if (lastFrameNs == 0L) REF_FRAME_MS
                       else ((frameTimeNanos - lastFrameNs) / 1_000_000f).coerceIn(1f, 100f)
            lastFrameNs = frameTimeNanos

            advanceLevels(dtMs)

            // A FIXED STRIDE, because a steady cadence is what reads as smooth — not a higher
            // average rate. Skipping the DRAW while still advancing the levels above keeps the
            // timing right: those are elapsed-time based now, so a skipped frame costs smoothness
            // and never accuracy. (A PiP window strides further still — the backdrop is a few
            // hundred pixels wide there and competes for CPU with the video decoder.)
            //
            // gfxinfo once measured ~19ms of GPU per frame here — ten-odd full-screen SCREEN passes
            // at 1080p, with Slow UI thread and Missed Vsync both zero, i.e. a pure fill-rate wall
            // that no amount of scheduling could get under 16.6ms. The stride was pinned to 2 then:
            // a free-running loop alternated 1-2-1-2 refreshes per frame (~50fps, period always
            // changing), and an exactly even 30 read as steadier than that flutter.
            //
            // The half-resolution composite buffer (see onDraw) quartered that fill and put the
            // frame back inside 16.6ms, so FRAME_STRIDE is now 1 — a true, steady 60. The stride
            // machinery is kept because it is still the one-line lever back to an even 30 if a
            // future change re-inflates the per-frame cost, and because the PiP path below leans on
            // it: there the backdrop is a few hundred pixels wide and competes with the video
            // decoder, so it strides further regardless of the fill headroom.
            frameCount++
            val stride = if (lowPower) LOW_POWER_STRIDE else FRAME_STRIDE
            if (frameCount % stride == 0) invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        lastFrameNs = 0L
        frameCount = 0
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        frameLoopRunning = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /**
     * Advances every smoothed level by [dtMs] of real time.
     *
     * The rates are written as "fraction of the remaining gap closed in one [REF_FRAME_MS] frame",
     * which is how they were originally tuned, and are converted here to the equivalent for however
     * long this frame actually took. Applying a fixed per-frame fraction — which is what this did
     * before — makes every time constant a function of the frame rate, so the orbs got mushier
     * whenever the redraw slowed down and snappier whenever it sped up. Compounding it properly
     * means a gap takes the same wall-clock time to close at 60fps, at 30fps, and while stuttering.
     */
    private fun advanceLevels(dtMs: Float) {
        energy += (energyTarget - energy) * decay(ENERGY_RATE, dtMs)

        // SPRINGS, NOT EXPONENTIAL EASING — this is what the orbs were missing.
        //
        // The band levels arrive as a STAIRCASE: one value every ~33ms from the analyser, held
        // constant in between. Exponential easing has no memory, so each new step restarts a fresh
        // decelerating ramp from a standstill; the result is thirty little ease-outs a second, and
        // the eye reads that pattern of repeated starts and stops as stepping however fine the
        // steps are. It is smooth in the sense of continuous and not smooth in the sense of
        // *fluid*, which is exactly the complaint.
        //
        // A spring carries VELOCITY across the target changing. Arriving at a step with momentum,
        // it keeps moving through it and bends toward the next one, so a staircase input comes out
        // as one continuous curve rather than a chain of separate moves. This is also what Apple
        // Music TV does — its orbs put a Compose spring on each band on top of the same kind of
        // 33ms DSP output — and it is the real difference between the two, not the frame rate.
        //
        // Integrated in fixed sub-steps because explicit integration goes unstable once dt exceeds
        // ~2/omega; a dropped frame would otherwise make the orbs detonate rather than lag.
        var remaining = (dtMs / 1000f).coerceAtMost(0.1f)
        while (remaining > 0f) {
            val step = minOf(remaining, SPRING_MAX_STEP_S)
            for (i in 0 until 3) {
                val accel = -ORB_STIFFNESS * (orbEnergy[i] - orbTarget[i]) - ORB_DAMPING * orbVel[i]
                orbVel[i] += accel * step
                orbEnergy[i] = (orbEnergy[i] + orbVel[i] * step).coerceIn(0f, ORB_LEVEL_CEILING)
            }
            remaining -= step
        }
        bandBass = orbEnergy[0]
        bandVocal = orbEnergy[1]
        bandTreble = orbEnergy[2]
    }

    /** Per-orb spring velocity. The state that exponential easing did not have. */
    private val orbVel = FloatArray(3)

    /**
     * The fraction of the remaining gap to close this frame, for a rate expressed per
     * [REF_FRAME_MS]. Two half-length frames compose to exactly one full-length one.
     */
    private fun decay(ratePerRefFrame: Float, dtMs: Float): Float {
        if (ratePerRefFrame >= 1f) return 1f
        return 1f - (1f - ratePerRefFrame).toDouble().pow((dtMs / REF_FRAME_MS).toDouble()).toFloat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        t1.start(); t2.start(); t3.start()
        if (backdropTheme != BackdropTheme.BLACK) startFrameLoop()
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        t1.cancel(); t2.cancel(); t3.cancel()
        stopFrameLoop()
        // Release the ~2 MB half-res buffer; recreated on the next attach. The Fire TV's
        // low-memory killer answers idle bitmaps left on a backgrounded view.
        scratch?.recycle(); scratch = null; scratchCanvas = null; scratchW = 0; scratchH = 0
    }

    fun setEnergy(e: Float) {
        energyTarget = e
        // Fallback only. While real band levels are arriving [setBands] owns the orbs; this keeps
        // them alive for any source that reports loudness but not spectrum, by separating the orbs
        // on RESPONSE SPEED instead of on frequency.
        if (haveBands) return
        for (i in orbEnergy.indices) {
            orbEnergy[i] += (e - orbEnergy[i]) * ORB_FOLLOW[i]
        }
    }

    /**
     * Real per-band levels: index 0 bass, 1 vocal, 2 treble.
     *
     * One orb per band, which is the point — the bass orb swells on the kick, the middle orb tracks
     * the CENTRE-PANNED voice specifically (see the mid/side note in AudioStreamServer, which is why
     * it follows the singer rather than everything sharing the singer's frequency range), and the
     * treble orb flickers on cymbals. They are still smoothed, but each with a constant
     * suited to its band rather than as a way of faking the split: bass is slow because bass IS
     * slow, treble is fast because a hi-hat is over in 30ms.
     */
    fun setBands(bands: FloatArray) {
        if (bands.size < 3) return
        haveBands = true
        // TARGETS only. The smoothing itself happens once per FRAME in [tick].
        //
        // Doing it here made the result depend on how often the audio thread happened to call: the
        // same constants gave near-instant tracking at 100 calls/sec and visible stepping at 10, so
        // the orbs' smoothness was a side effect of the audio block size. Frame-locked smoothing
        // always takes the same wall-clock time to close a gap, which is the only way a fixed
        // constant can mean anything.
        for (i in 0 until 3) orbTarget[i] = bands[i]
    }

    private val orbTarget = FloatArray(3)

    private var haveBands = false
    private var bandBass = 0f
    private var bandVocal = 0f
    private var bandTreble = 0f

    private val orbEnergy = FloatArray(3)

    /** Halves the redraw rate — set while the window is a PiP thumbnail. */
    fun setLowPower(on: Boolean) { lowPower = on }
    private var lowPower = false

    fun updateColors(bitmap: Bitmap) {
        // 32, not Palette's default 16. The finer quantisation is what surfaces SMALL accent
        // regions -- a teal logo, a red jacket on an otherwise blue sleeve -- which at 16 get merged
        // into the nearest large area and never reach the pool the hue filter picks from. Costs one
        // more k-means pass on a 256px bitmap, once per track.
        Palette.from(bitmap).maximumColorCount(32).generate { palette ->
            if (palette == null) return@generate
            // The six NAMED swatches plus every swatch Palette actually found.
            //
            // The named ones alone are the reason a blue-and-red cover looked blue: "vibrant",
            // "muted", "dark muted" and friends are roles, and on a cover with one dominant colour
            // several of those roles are filled by shades of that SAME colour, so the pool handed to
            // the hue filter had no red in it to pick. palette.swatches is the full quantised set,
            // where a strong secondary colour does appear even when it holds no named role.
            val raw = (
                listOfNotNull(
                    palette.vibrantSwatch, palette.darkVibrantSwatch, palette.mutedSwatch,
                    palette.lightVibrantSwatch, palette.darkMutedSwatch, palette.lightMutedSwatch,
                    palette.dominantSwatch,
                ) + palette.swatches
                ).distinctBy { it.rgb }.sortedByDescending { it.population }
            if (raw.isEmpty()) return@generate

            // GREY AND WHITE ARTWORK IS ITS OWN CASE, not a weak version of a colour one.
            //
            // SAT_FLOOR forces every swatch to 55% saturation. That is right for a colour cover and
            // actively wrong for a monochrome one: a black-and-white sleeve has no hue, so whatever
            // trace the quantiser happens to report gets amplified into a confident invented colour,
            // and spreadByHue then rotates it into two more. Three arbitrary pastels off a greyscale
            // cover, none of which are in the artwork.
            //
            // Decided from the ORIGINAL saturations, before any boost — after the boost everything
            // looks saturated by construction and the test can no longer tell the two cases apart.
            val hsvProbe = FloatArray(3)
            val maxSat = raw.maxOf { s -> Color.colorToHSV(s.rgb, hsvProbe); hsvProbe[1] }
            val monochrome = maxSat < ACHROMATIC_SAT

            val swatches = raw.map { s ->
                val hsv = FloatArray(3)
                Color.colorToHSV(s.rgb, hsv)
                if (monochrome) {
                    // Stay grey, and glow anyway. Value is lifted into a band that actually reads on
                    // black, but the artwork's own spread across that band is preserved by mapping
                    // rather than clamping, so a white highlight and a mid grey stay different tones
                    // instead of collapsing onto one.
                    hsv[1] = 0f
                    hsv[2] = MONO_VALUE_FLOOR + hsv[2] * (MONO_VALUE_CEILING - MONO_VALUE_FLOOR)
                } else {
                    // Push toward vivid. The value ceiling matters most: a pale, high-value swatch
                    // reads as light grey and washes out the text on top, so cap value and floor
                    // saturation to force deep colour rather than haze. A plain saturation filter was
                    // tried upstream and stripped vivid pinks and teals.
                    hsv[1] = (hsv[1] * SAT_BOOST).coerceIn(SAT_FLOOR, 1f)
                    // FLOOR as well as ceiling. Only the ceiling was applied, so a dark swatch stayed
                    // dark: the device log recorded orb colours at v=0.09 and v=0.13, which on black is
                    // no glow at all -- the orbs were being drawn correctly and were simply invisible.
                    hsv[2] = hsv[2].coerceIn(VALUE_FLOOR, VALUE_CEILING)
                }
                Color.HSVToColor(hsv)
            }
            if (swatches.isEmpty()) return@generate
            val spread = if (monochrome) spreadByValue(swatches) else spreadByHue(swatches)
            // The log caught updateColors running four times in two seconds, twice producing a
            // near-black palette (v=0.09). Those are the between-track placeholder and part-decoded
            // images, not artwork, and letting them through means the backdrop lurches to black and
            // back on every track change.
            val hsvGuard = FloatArray(3)
            val brightest = spread.maxOf { c -> Color.colorToHSV(c, hsvGuard); hsvGuard[2] }
            if (brightest < MIN_USABLE_VALUE) {
                Logger.i("Palette ignored — brightest swatch v=%.2f, too dark to glow".format(brightest))
                return@generate
            }
            for (i in 0 until PALETTE_SIZE) targets[i] = spread[i % spread.size]
            // The three hues the ORBS will use. A capture showed three purple orbs over a vivid
            // pink/orange/green cover, and there was no way to tell whether Palette had failed to
            // find the other colours or whether something downstream was muddying them. Printing the
            // hue angles settles which end to fix: three numbers far apart here means the palette is
            // fine and the drawing is at fault.
            val hs = FloatArray(3)
            Logger.i(
                "Palette orb hues${if (monochrome) " (monochrome art)" else ""}: " +
                    (0 until 3).joinToString(", ") { i ->
                        Color.colorToHSV(targets[i], hs)
                        "%.0f° s=%.2f v=%.2f".format(hs[0], hs[1], hs[2])
                    }
            )
            colorFade = 0f; colorAnim.cancel(); colorAnim.start()
        }
    }

    fun resetColors() {
        for (i in 0 until PALETTE_SIZE) targets[i] = Color.parseColor(DEFAULTS[i % DEFAULTS.size])
        colorAnim.cancel(); colorAnim.start()
    }

    /**
     * Composites the backdrop into a half-resolution buffer and scales it up.
     *
     * Measured on the Fire TV with `dumpsys gfxinfo`: 18 ms median frame, **19–20 ms of it on the
     * GPU**, 71% of frames janky — while `Slow UI thread` and `Missed Vsync` were both zero. So the
     * Kotlin costs nothing and the shortfall is pure fill rate: the field is an opaque base plus
     * four full-screen blobs plus three orbs of two gradients each plus a vignette, every one of
     * them SCREEN-blended across all 1920x1080. Ten-odd full-screen passes will not fit in 16.6 ms
     * on this GPU no matter how the loop is scheduled, which is why vsync alignment alone left it
     * at roughly 50fps.
     *
     * Quartering the pixels quarters all of that, at a cost of one bilinear upscale. It is close to
     * free *visually* because every source here is a wide, soft radial gradient — there is no
     * high-frequency detail for the half-resolution grid to lose. This is not a trick that would
     * survive on text or on the artwork; it works because of what this particular view draws.
     *
     * The canvas is pre-scaled rather than the geometry, so every coordinate below stays in
     * full-resolution space and the composition is identical — radii, centres and the vignette all
     * keep their tuned values instead of needing a second set for the scaled buffer.
     */
    override fun onDraw(canvas: Canvas) {
        // BLACK draws one rectangle; a half-res buffer would be pure overhead for it.
        if (backdropTheme == BackdropTheme.BLACK) { canvas.drawColor(Color.BLACK); return }
        val w = width; val h = height
        if (w <= 0 || h <= 0) return

        val buf = if (ensureScratch(w, h)) scratchCanvas else null
        val bmp = scratch
        if (buf == null || bmp == null) {
            // Allocation failed — draw full-res. Correct, just back to the old fill cost.
            drawBackdrop(canvas)
            return
        }

        // Pre-SCALE the buffer canvas rather than the geometry, so every coordinate in
        // drawBackdrop stays in full-resolution space and the composition is byte-for-byte the
        // tuned one — radii, anchors, orbits and the vignette all keep their measured values
        // instead of needing a second set for the scaled grid.
        val save = buf.save()
        buf.scale(scratchW.toFloat() / w, scratchH.toFloat() / h)
        drawBackdrop(buf)
        buf.restoreToCount(save)

        // One bilinear upscale. The base is opaque, so this is a plain textured copy — a single
        // full-screen pass in place of the ten-odd blended ones it replaced.
        blitDst.set(0, 0, w, h)
        canvas.drawBitmap(bmp, null, blitDst, upscalePaint)
    }

    private fun drawBackdrop(canvas: Canvas) {
        // Nothing to compose: no palette, no beat, no edge treatment. Just the card on black.
        if (backdropTheme == BackdropTheme.BLACK) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val f = colorFade
        if (f >= 1f) for (i in 0 until PALETTE_SIZE) colors[i] = targets[i]

        val w = width.toFloat(); val h = height.toFloat()
        val a1 = t1.animatedValue as Float
        val a2 = t2.animatedValue as Float
        val a3 = t3.animatedValue as Float

        // Levels stay 0..1; the intensity setting is applied to the RENDER below, never to the
        // level itself. See the note in drawOrb -- a pre-clip multiply pins the top of the range
        // flat and makes the highest settings indistinguishable from one another.
        val amp = beatMultiplier
        val e = energy.coerceIn(0f, 1f)
        // BAND ENVELOPES ONLY -- `energy` is deliberately not mixed in here any more.
        //
        // It carries the same beat, but through a SECOND smoothing stage (0.22/frame in tick, on top
        // of the per-band attack/release the bands already went through). Summing a once-smoothed
        // and a twice-smoothed copy of one signal smears the attack and lands the field a frame or
        // two behind the orbs -- the two modes were drawing the same music at different times.
        // bandBass already falls back to `energy` for sources that report loudness but no spectrum,
        // so nothing is lost by dropping it. `e` stays for the fallback path below.
        val bass = bandBass.coerceIn(0f, 1f)
        val vocal = bandVocal.coerceIn(0f, 1f)
        val treble = bandTreble.coerceIn(0f, 1f)
        // ALPHA IS ALL BUT CONSTANT, for the same reason the projector halo's is: these blobs cover
        // the whole screen, so any alpha that rides the beat pumps the entire picture's brightness
        // on every kick. That is what read as "too sensitive" -- the response was not too large in
        // magnitude, it was applied to the one property that affects every pixel at once. The beat
        // now shows as size, which is local and reads correctly from across a room.
        val beatAlpha =
            (FIELD_BASE_ALPHA + treble * FIELD_BEAT_ALPHA * amp).coerceAtMost(FIELD_ALPHA_CAP)

        // Black base required for SCREEN blend. Projector mode uses TRUE black rather than the
        // near-black used on a TV: #050505 is a deliberate lift that stops OLED/LCD panels crushing
        // shadows, but on a projector any non-zero value is light thrown onto a wall, which turns
        // the "invisible" edge into a visible grey rectangle.
        canvas.drawColor(if (projectorMode) Color.BLACK else BASE_COLOR)

        // PROJECTOR MODE draws a single glowing orb instead of the four drifting blobs.
        //
        // The blob field is built to fill a rectangle: four sources biased to the edges, bleeding
        // off them. No amount of fading at the boundary makes that read as edgeless, because the
        // eye still sees a lit rectangle with dark corners -- which is what the first attempt got
        // wrong. A single radial source with a smooth falloff to true black has no edge to hide:
        // the light simply runs out, the way a real glow does, and on a projector the surround is
        // literally unlit wall.
        if (projectorMode) {
            drawOrb(canvas, w, h, e)
            return
        }

        // NO saveLayer. SCREEN ONTO THE OPAQUE BASE INSTEAD.
        //
        // The layer was here so overlapping sources would screen against each other rather than
        // occlude, and they still do -- but screen(black, src) IS src, so compositing against the
        // opaque base already on the canvas gives pixel-identical output. What the layer added was
        // a full-screen offscreen buffer allocated, drawn into and blitted back EVERY FRAME: on the
        // Fire TV's GPU, dumpsys gfxinfo reported a 12 MB scratch RenderTarget, 907 frames of "slow
        // issue draw commands" and 98% of frames janky at a 61ms median. That is the lag, and it
        // was buying nothing.

        // 4 blob centers — lerp-based, biased to edges.
        //
        // PROJECTOR MODE pulls them toward the middle. Normally the blobs deliberately hug the
        // edges and bleed off-screen, which looks right on a TV with a bezel but puts a hard,
        // straight cut exactly where a projector has no edge at all. [pull] compresses every centre
        // toward 0.5 so the whole composition sits inside the frame and the vignette below can
        // dissolve it into black without ever clipping a blob against a boundary.
        fun px(v: Float) = (0.5f + (v - 0.5f) * centreBias) * w
        fun py(v: Float) = (0.5f + (v - 0.5f) * centreBias) * h
        // Each blob stays in its own QUADRANT. The inner endpoints used to run to 0.40-0.60 on x
        // and 0.40-0.55 on y, so whenever the three animators happened to line up all four centres
        // converged on the middle of the screen -- four colours piled into one bright blob with dark
        // corners around it, which is both the ugliest and the least colourful state the field has.
        // The travel is the same in character, just bounded well short of the centre line.
        val cx0 = px(lerp(0.04f, 0.30f, a1)); val cy0 = py(lerp(0.08f, 0.34f, a2))
        val cx1 = px(lerp(0.96f, 0.70f, a2)); val cy1 = py(lerp(0.04f, 0.30f, a3))
        val cx2 = px(lerp(0.10f, 0.34f, a3)); val cy2 = py(lerp(0.94f, 0.68f, a1))
        val cx3 = px(lerp(0.90f, 0.66f, a1)); val cy3 = py(lerp(0.90f, 0.64f, a3))

        // Each blob rides between two palette entries, driven by a drift float, so four blobs
        // express six colours and keep "vibing" without any extra animators.
        // Reused rather than reallocated: this runs on every frame, and two fresh arrays per draw
        // is exactly the garbage that shows up as stutter on a low-end Fire TV.
        blobMix[0] = a3; blobMix[1] = 1f - a2; blobMix[2] = a1; blobMix[3] = 1f - a3
        val cs = blobColors
        for (i in 0 until 4) {
            val c1 = blend(colors[(i * 2) % PALETTE_SIZE], targets[(i * 2) % PALETTE_SIZE], f)
            val c2 = blend(colors[(i * 2 + 1) % PALETTE_SIZE], targets[(i * 2 + 1) % PALETTE_SIZE], f)
            // DIMMED for the blob field specifically. VALUE_FLOOR exists so a dark swatch still
            // glows as a small orb on black -- but here four screen-sized sources are SCREEN-blended
            // over each other, and SCREEN is additive: four overlapping colours each held above 55%
            // value sum toward white, which is why the whole backdrop went to hazy lilac-grey and
            // the artwork stopped being the brightest thing on screen. The orbs keep the floor;
            // the field is scaled back down before it is stacked.
            cs[i] = darken(blend(c1, c2, blobMix[i]), BLOB_VALUE_SCALE)
        }
        // ONE RADIUS PER BLOB, EACH ON ITS OWN BAND -- the same idea the orbs are built on.
        //
        // All four used to share a single radius driven by bass alone, so the entire field grew and
        // shrank as one object: a 20% swell on a shape that already covers the screen is nearly
        // invisible, which is why Dynamic looked static until the intensity was pushed to Strong.
        // Giving each blob its own band means the field CHANGES SHAPE with the music -- the corner
        // on the vocal swells while the one on the kick recedes -- and shape reads from across a
        // room in a way that overall size never did. The fourth takes the average, so the
        // composition still has one member that moves with everything.
        val mean = (bass + vocal + treble) / 3f
        val base = maxOf(w, h) * FIELD_BASE_RADIUS
        blob(canvas, 0, cx0, cy0, base * blobScale(bass, amp), cs[0], beatAlpha)
        blob(canvas, 1, cx1, cy1, base * blobScale(vocal, amp), cs[1], beatAlpha)
        blob(canvas, 2, cx2, cy2, base * blobScale(treble, amp), cs[2], beatAlpha)
        blob(canvas, 3, cx3, cy3, base * blobScale(mean, amp), cs[3], beatAlpha)

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
     * How fast the orbs travel their ellipses: 0 = Slow, 1 = Normal, 2 = Fast.
     *
     * Applied by rescaling the three orbit animators' durations rather than by multiplying their
     * output, because their PERIODS are deliberately co-prime (20/27/34s) — that is what stops the
     * composition repeating. Scaling all three by the same factor keeps them co-prime; scaling the
     * values instead would have wrapped them against each other and produced a visible cycle.
     *
     * The current position is preserved across the change so the orbs do not jump when the setting
     * is touched mid-track.
     */
    fun setOrbSpeed(level: Int) {
        val factor = when (level) {
            0 -> 0.6f
            2 -> 1.7f
            else -> 1f
        }
        if (factor == orbSpeedFactor) return
        orbSpeedFactor = factor
        listOf(t1 to ORBIT_MS[0], t2 to ORBIT_MS[1], t3 to ORBIT_MS[2]).forEach { (anim, base) ->
            val wasRunning = anim.isStarted
            val at = anim.animatedFraction
            anim.cancel()
            anim.duration = (base / factor).toLong().coerceAtLeast(1_000L)
            if (wasRunning) {
                anim.start()
                anim.currentPlayTime = (anim.duration * at).toLong()
            }
        }
    }

    @Volatile private var orbSpeedFactor = 1f


    /**
     * Turns the edgeless projector look on or off.
     *
     * Cheap enough to call whenever the setting changes: it only flips a flag and drops the cached
     * gradient, and the next frame rebuilds whatever it needs.
     */
    fun setTheme(theme: BackdropTheme) {
        if (backdropTheme == theme) return
        backdropTheme = theme
        centreBias = if (theme == BackdropTheme.PROJECTOR) PROJECTOR_CENTRE_BIAS else 1f
        vignette = null
        // BLACK stops the redraw loop rather than merely drawing nothing. A 60fps invalidate that
        // paints one rectangle is still 60 frames a second of compositing on a device that has
        // little to spare, for a picture that cannot change. The loop restarts on the way out.
        if (theme == BackdropTheme.BLACK) stopFrameLoop() else startFrameLoop()
        invalidate()
    }

    private var backdropTheme = BackdropTheme.DYNAMIC
    private val projectorMode get() = backdropTheme == BackdropTheme.PROJECTOR

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
        // SYNTHESISE the shortfall by rotating hue, rather than repeating what was picked.
        //
        // Cycling the distinct picks was the previous attempt and it is why the log showed
        // "51 deg, 353 deg, 51 deg" -- orbs 0 and 2 the same colour -- and, on a cover with one
        // dominant tone, "0 deg, 0 deg, 0 deg": three identical orbs, which is indistinguishable
        // from the bug that behaviour was meant to fix. Raising HUE_MIN_ANGLE to 40 made it MORE
        // likely, because fewer candidates qualify as distinct.
        //
        // A cover that genuinely contains one colour cannot yield three, so the choice is between
        // three identical orbs and three related ones. Rotating the strongest hue by even steps
        // keeps the artwork's character while guaranteeing the trio reads as three lights.
        val distinct = picked.toList()
        if (distinct.isEmpty()) return DEFAULTS.map { Color.parseColor(it) }
        //
        // ROTATING HUE IS THE LESSER EVIL, NOT A GOOD ONE, and it was the previous behaviour here.
        // It buys separation by painting a colour the artwork does not contain: on an all-orange
        // cover, even steps around the wheel produce blue and green orbs, which is confidently
        // wrong rather than merely dull. Stepping VALUE and SATURATION instead keeps the hue the
        // art actually has, so an orange album gives orange shades. The trio still reads as three
        // lights -- brightness separates them as well as hue does at orb scale -- and nothing on
        // screen is a colour the cover lacks. Monochrome art reaches the same conclusion from the
        // other direction in [spreadByValue].
        var step = 1
        while (picked.size < PALETTE_SIZE) {
            val base = distinct[(picked.size - distinct.size).coerceAtLeast(0) % distinct.size]
            picked += varyShade(base, step)
            step++
        }
        return picked
    }

    /**
     * A lighter or darker relative of [base] on the SAME hue.
     *
     * Alternates brighter/darker as [step] climbs so successive fills separate from each other as
     * well as from the original, and drops saturation a little on the way up because a real light
     * source desaturates as it gets hotter.
     */
    private fun varyShade(base: Int, step: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        val up = step % 2 == 1
        val magnitude = SHADE_STEP * ((step + 1) / 2)
        if (up) {
            hsv[2] = (hsv[2] + magnitude).coerceAtMost(SHADE_VALUE_CEILING)
            hsv[1] = (hsv[1] - magnitude * 0.5f).coerceAtLeast(SHADE_SAT_FLOOR)
        } else {
            hsv[2] = (hsv[2] - magnitude).coerceAtLeast(SHADE_VALUE_FLOOR)
            hsv[1] = (hsv[1] + magnitude * 0.3f).coerceAtMost(1f)
        }
        return Color.HSVToColor(hsv)
    }

    /**
     * The monochrome counterpart to [spreadByHue].
     *
     * On greyscale artwork hue carries no information at all — every entry sits at the same
     * meaningless angle, so [spreadByHue] would reject all but the first as a clash and then
     * synthesise the rest by ROTATING that angle, which is how a black-and-white sleeve ends up
     * throwing coloured light. Here the orbs are separated on VALUE instead, which is the one axis a
     * greyscale image genuinely varies along: a white, a light grey and a mid grey still read as
     * three distinct lights on black.
     */
    private fun spreadByValue(source: List<Int>): List<Int> {
        val hsv = FloatArray(3)
        val picked = mutableListOf<Int>()
        for (c in source) {
            if (picked.size >= PALETTE_SIZE) break
            Color.colorToHSV(c, hsv)
            val v = hsv[2]
            val clash = picked.any { p ->
                Color.colorToHSV(p, hsv)
                Math.abs(hsv[2] - v) < VALUE_MIN_GAP
            }
            if (!clash) picked += c
        }
        if (picked.isEmpty()) return DEFAULTS.map { Color.parseColor(it) }
        // Shortfall stepped DOWN from what was found, floored so it never darkens into invisibility.
        val distinct = picked.toList()
        var step = 1
        while (picked.size < PALETTE_SIZE) {
            Color.colorToHSV(distinct[picked.size % distinct.size], hsv)
            hsv[1] = 0f
            hsv[2] = (hsv[2] - VALUE_MIN_GAP * step).coerceAtLeast(MONO_VALUE_FLOOR)
            picked += Color.HSVToColor(hsv)
            step++
        }
        return picked
    }

    /**
     * A single soft orb on true black, breathing with the beat.
     *
     * Deliberately built so nothing ever meets an edge: the radius is capped against the SHORTEST
     * side, so even at the loudest beat the glow's outer falloff lands inside the frame on every
     * side, including on a 16:9 screen where the vertical margin is the tight one. That cap is what
     * keeps the pulse from clipping.
     *
     * The falloff is a four-stop ramp rather than a linear one. A straight fade to transparent has
     * a visible outer ring where the gradient terminates; easing it out means the last of the light
     * approaches black asymptotically and the boundary cannot be located by eye.
     */
    /**
     * Projector mode — a direct port of Apple Music TV's implementation, so the two match.
     *
     * Three orbs on true black, one per band, each on its own slow circle. Every geometric and
     * alpha figure below is AMTV's: anchors 0.52/0.62/0.72 across and 0.44/0.56/0.46 down, a
     * 0.08w x 0.045h circle, base radii 0.26/0.21/0.16 of the short side, and a size ride of
     * 0.75/0.85/1.05 capped at 2.2. Halo alpha runs 0.24 + level*0.42 (ceiling 0.9), the core is
     * 0.34 of the radius at 0.12 + level*0.62 (ceiling 0.92), whitened 60% toward white.
     *
     * Two things are deliberately NOT copied verbatim, both because Compose can afford what a
     * Canvas on this device cannot:
     *
     * - AMTV builds a Brush per orb per frame. Here the gradients stay cached and keyed on the
     *   quantised tint, with alpha applied through the Paint instead of baked into the stops —
     *   which is arithmetically the same result, since a 2-stop `[colour@a, colour@0]` ramp scaled
     *   by paint alpha `a` is identical to baking `a` into the first stop.
     * - The four edge fades are drawn over their own 10% strips rather than as four full-screen
     *   rects. Outside its band each gradient has clamped to fully transparent and is contributing
     *   nothing, so the visible result is identical while the fill cost drops from four whole
     *   screens to about four tenths of one — and this GPU is already the binding constraint.
     *
     * The edge treatment is what lets the radius clamp go. The old code bounded each orb to the
     * distance from its own centre to the nearest side, which is why the anchors had to be huddled
     * in the middle and why a big swell could stop growing mid-beat. Dissolving the frame to black
     * on all four sides means an orb running off the edge simply fades out, which is both what a
     * projector needs and what frees the composition to spread out.
     */
    private fun drawOrb(canvas: Canvas, w: Float, h: Float, energy: Float) {
        canvas.drawColor(Color.BLACK)
        if (w <= 0f || h <= 0f) return

        val short = minOf(w, h)
        val amp = beatMultiplier
        val cf = colorFade
        val drifts = floatArrayOf(
            t1.animatedValue as Float, t2.animatedValue as Float, t3.animatedValue as Float,
        )

        for (k in 0 until ORB_COUNT) {
            // ONE animator drives both axes, unlike the old version which used a different one per
            // axis. Same value into cos and sin is a circle; two independent ones is a Lissajous
            // figure, which wanders more but never returns anywhere predictable.
            val drift = drifts[k]
            val ang = (drift * TWO_PI + ORB_PHASE[k]).toDouble()
            val cx = ORB_X[k] * w + Math.cos(ang).toFloat() * ORB_DRIFT_X * w
            val cy = ORB_Y[k] * h + Math.sin(ang).toFloat() * ORB_DRIFT_Y * h

            // Not clamped to 1: the spring is under-damped, and its overshoot is the flare on a
            // hit. Clamping here would flatten exactly the peak that makes a beat read.
            val lvl = orbEnergy[k].coerceAtLeast(0f)
            val radius = short * ORB_BASE_RADIUS[k] *
                (1f + (lvl * ORB_SIZE_RIDE[k] * amp).coerceAtMost(ORB_SWELL_CAP))
            if (radius <= 0f) continue

            // The first three palette slots, blended toward the incoming palette so a track change
            // moves the orbs' colour across the crossfade instead of snapping at the end of it.
            val tint = blend(colors[k % PALETTE_SIZE], targets[k % PALETTE_SIZE], cf)
            val partner = blend(colors[(k + 1) % PALETTE_SIZE], targets[(k + 1) % PALETTE_SIZE], cf)
            val mixed = blend(tint, partner, ORB_HUE_BASE + ORB_HUE_TRAVEL * drift)
            val key = mixed and 0xF8F8F8
            if (orbGrads[k] == null || orbKeys[k] != key) {
                orbKeys[k] = key
                orbGrads[k] = RadialGradient(
                    0f, 0f, 1f, orbHaloColors(key), ORB_STOPS, Shader.TileMode.CLAMP,
                )
                orbCoreGrads[k] = RadialGradient(
                    0f, 0f, 1f, orbCoreColors(key), ORB_CORE_STOPS, Shader.TileMode.CLAMP,
                )
            }
            val grad = orbGrads[k] ?: continue

            blobMatrix.setScale(radius, radius)
            blobMatrix.postTranslate(cx, cy)
            grad.setLocalMatrix(blobMatrix)
            orbPaint.shader = grad
            orbPaint.alpha = alpha255(ORB_BASE_ALPHA + lvl * ORB_BEAT_ALPHA * amp, ORB_ALPHA_CAP)
            canvas.drawCircle(cx, cy, radius, orbPaint)

            // The bright heart. Small enough that its beat brightness stays local — the halo covers
            // most of the frame, so putting the flare there would pulse the whole picture.
            val core = orbCoreGrads[k]
            if (core != null && !lowPower) {
                val cr = radius * ORB_CORE_FRAC
                blobMatrix.setScale(cr, cr)
                blobMatrix.postTranslate(cx, cy)
                core.setLocalMatrix(blobMatrix)
                orbPaint.shader = core
                orbPaint.alpha =
                    alpha255(ORB_CORE_ALPHA + lvl * ORB_CORE_BEAT_ALPHA * amp, ORB_CORE_ALPHA_CAP)
                canvas.drawCircle(cx, cy, cr, orbPaint)
            }
            orbPaint.shader = null
        }

        drawProjectorEdges(canvas, w, h)
    }

    private fun alpha255(value: Float, cap: Float): Int =
        (value.coerceAtMost(cap) * 255f).toInt().coerceIn(0, 255)

    /**
     * Dissolves all four edges to black, then darkens the right where the text sits.
     *
     * A projector has no bezel: any lit rectangle reads as a grey panel hanging on the wall, and no
     * amount of fading the ORBS fixes that, because the problem is the boundary rather than what is
     * inside it. Fading the frame itself means the light simply runs out, the way a real glow does.
     *
     * Each strip is drawn over its own band. Beyond the gradient's end the shader has clamped to
     * fully transparent, so painting the rest of the screen with it would be four extra full-screen
     * passes that change no pixel.
     */
    private fun drawProjectorEdges(canvas: Canvas, w: Float, h: Float) {
        if (edgeW != w || edgeH != h) {
            edgeW = w; edgeH = h
            val ev = h * ORB_EDGE_FADE
            val eh = w * ORB_EDGE_FADE
            edgeTop = LinearGradient(0f, 0f, 0f, ev, Color.BLACK, TRANSPARENT, Shader.TileMode.CLAMP)
            edgeBottom = LinearGradient(0f, h - ev, 0f, h, TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            edgeLeft = LinearGradient(0f, 0f, eh, 0f, Color.BLACK, TRANSPARENT, Shader.TileMode.CLAMP)
            edgeRight = LinearGradient(w - eh, 0f, w, 0f, TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            rightDim = LinearGradient(
                w * RIGHT_DIM_START, 0f, w, 0f, TRANSPARENT, RIGHT_DIM_COLOR, Shader.TileMode.CLAMP,
            )
        }
        val ev = h * ORB_EDGE_FADE
        val eh = w * ORB_EDGE_FADE
        edgePaint.shader = edgeTop;    canvas.drawRect(0f, 0f, w, ev, edgePaint)
        edgePaint.shader = edgeBottom; canvas.drawRect(0f, h - ev, w, h, edgePaint)
        edgePaint.shader = edgeLeft;   canvas.drawRect(0f, 0f, eh, h, edgePaint)
        edgePaint.shader = edgeRight;  canvas.drawRect(w - eh, 0f, w, h, edgePaint)
        edgePaint.shader = rightDim;   canvas.drawRect(w * RIGHT_DIM_START, 0f, w, h, edgePaint)
        edgePaint.shader = null
    }

    /** Plain SRC_OVER — these paint black ON TOP, which is the opposite of the orbs' SCREEN. */
    private val edgePaint = Paint()
    private var edgeTop: LinearGradient? = null
    private var edgeBottom: LinearGradient? = null
    private var edgeLeft: LinearGradient? = null
    private var edgeRight: LinearGradient? = null
    private var rightDim: LinearGradient? = null
    private var edgeW = -1f
    private var edgeH = -1f

    /** SCREEN so overlapping orbs add their light rather than hiding one another. */
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val orbGrads = arrayOfNulls<RadialGradient>(3)
    private val orbCoreGrads = arrayOfNulls<RadialGradient>(3)
    private val orbKeys = IntArray(3) { -1 }

    // Colour ramps, built only when an orb's quantised tint changes. Kept as helpers rather than
    // constants because each stop carries the orb's own colour in its low 24 bits.
    /**
     * Two stops: the orb's colour at full alpha, falling to fully transparent at the rim.
     *
     * AMTV's radial brush is exactly this pair, with the halo alpha baked into the first stop. Here
     * the ramp stays cached and the alpha is applied through the Paint, which is the same result —
     * scaling `[c@255, c@0]` by alpha a gives `[c@a, c@0]` — while letting one gradient serve every
     * brightness the beat asks for instead of a fresh allocation per frame.
     */
    private fun orbHaloColors(key: Int) = intArrayOf(
        key or 0xFF000000.toInt(),
        key and 0xFFFFFF,
    )

    /** The core, whitened toward white the way a real glow's hottest point desaturates. */
    private fun orbCoreColors(key: Int) = intArrayOf(
        lighten(key, CORE_WHITEN) or 0xFF000000.toInt(),
        lighten(key, CORE_WHITEN) and 0xFFFFFF,
    )

    /** Scales a colour's brightness, leaving hue and saturation alone. */
    private fun darken(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )

    private fun lighten(c: Int, f: Float): Int {
        val i = 1f - f
        return Color.rgb(
            (Color.red(c) * i + 255f * f).toInt().coerceIn(0, 255),
            (Color.green(c) * i + 255f * f).toInt().coerceIn(0, 255),
            (Color.blue(c) * i + 255f * f).toInt().coerceIn(0, 255),
        )
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

    /** A blob's radius factor for its band level, with the intensity setting on the render. */
    private fun blobScale(level: Float, amp: Float) = 1f + level * FIELD_BEAT_SCALE * amp

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
        private const val VALUE_CEILING = 0.92f
        /** Below this an orb is black on black. See the note in updateColors. */
        private const val VALUE_FLOOR = 0.55f
        /** A palette whose brightest colour is under this came from a placeholder, not artwork. */
        private const val MIN_USABLE_VALUE = 0.35f

        /**
         * Brightness the full-screen blob field is scaled to before SCREEN-stacking.
         *
         * Four overlapping sources add, so the on-screen result is far brighter than any single
         * blob colour. 0.46 keeps the palette's hue and saturation fully readable while leaving the
         * album art as the brightest object in the frame, which is what makes the card read as a
         * card rather than as text floating on a pastel wash.
         */
        // Back up from 0.34. Dimming this was a misread: the field was only ever "washed out" at
        // 0.92, and the ask was for PROJECTOR mode to be dimmer -- so 0.34 made the one mode that
        // was fine too dark while leaving the orbs untouched. The orb alphas below carry that
        // change now, and this sits at the value that killed the wash without draining the colour.
        private const val BLOB_VALUE_SCALE = 0.66f

        /** Beat -> blob size. The only place the full-screen field expresses the beat. */
        /** Blob radius as a fraction of the long side, before its band swells it. */
        private const val FIELD_BASE_RADIUS = 0.52f

        // Was 0.20, applied to one shared radius. A fifth is nothing on a shape that already fills
        // the screen; per-blob it changes the composition rather than merely inflating it, so it
        // can be much larger without the whole picture pumping.
        private const val FIELD_BEAT_SCALE = 0.42f

        /**
         * Field brightness. Near-constant on purpose -- see the note at the call site.
         *
         * Lowered along with BLOB_VALUE_SCALE: the title fades IN over this field, so every point of
         * backdrop brightness is contrast the text loses. White-on-pastel at TV viewing distance is
         * the failure mode, and it is worse mid-crossfade than at rest.
         */
        private const val FIELD_BASE_ALPHA = 0.70f
        private const val FIELD_BEAT_ALPHA = 0.10f

        /** Ceiling on the field's alpha, so a high intensity setting cannot wash the screen out. */
        private const val FIELD_ALPHA_CAP = 0.86f
        /** Hue step used to invent the colours a one-tone cover cannot supply. */
        /** Value/saturation step between synthesised shades of an accent the artwork really has. */
        private const val SHADE_STEP = 0.22f
        private const val SHADE_VALUE_CEILING = 0.95f
        private const val SHADE_VALUE_FLOOR = 0.30f
        private const val SHADE_SAT_FLOOR = 0.30f

        /** Minimum hue separation between chosen palette colours, in degrees. */
        private const val HUE_MIN_ANGLE = 40f

        /**
         * Below this ORIGINAL saturation the artwork is treated as greyscale and kept that way.
         *
         * Set generously rather than at literal zero: JPEG chroma subsampling and Palette's own
         * quantiser both leave a few percent of colour on an image that is black and white to the
         * eye, and SAT_BOOST multiplies exactly that residue into a real hue.
         */
        private const val ACHROMATIC_SAT = 0.18f

        /** Monochrome value band. The floor must clear MIN_USABLE_VALUE or the art is rejected. */
        private const val MONO_VALUE_FLOOR = 0.45f
        private const val MONO_VALUE_CEILING = 1f

        /** How far apart two greys must be to count as different lights. */
        private const val VALUE_MIN_GAP = 0.16f

        /** Darkness directly under the text block; the gradient fades to nothing from there. */
        // Raised from 0x8C. This pool is what the title and artist actually sit on, and the card
        // renders them white; the backdrop only has to be dark HERE, not everywhere, so darkening
        // this region harder costs nothing elsewhere and is what keeps the text readable while the
        // blob field stays colourful.
        private const val TEXT_DARKEN_ARGB = 0xB8000000.toInt()

        /** Mid stop of the text pool, so the falloff eases rather than ramping straight to nothing. */
        private const val TEXT_DARKEN_MID_ARGB = 0x8C000000.toInt()

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

        /** Orb size against the SHORT side, so the glow clears every edge on a wide screen. */
        private const val ORB_COUNT = 3
        /**
         * Orb size against the SHORT side.
         *
         * Raised from 0.19: with the front-loaded falloff below, 0.19 of 1080px put most of the light
         * inside ~110px, which on a 1920-wide panel read as three faint smudges rather than as glows
         * (visible in the 1080p capture). The edge budget below is what caps this, not taste.
         */
        /**
         * Base radius per band, as a fraction of the short side. Bass is the big slow one, treble
         * the small quick one -- giving all three the same size is most of why a trio of orbs reads
         * as one thing blinking rather than as three instruments.
         */
        // SIZED SO THE EDGE CLAMP NEVER BITES. At 0.26/0.21/0.16 the fully-swollen bass orb
        // reached 0.42 of the short side against ~0.41 of room, so it hit the geometric clamp on
        // every peak and simply stopped growing -- a big orb that never changed size, which is
        // exactly the "bass and vocals are maxed, only treble moves" report. The clamp is a safety
        // net for odd window shapes; when it is doing the work on a 16:9 TV, the radii are wrong.
        // Trimmed again when the orbits were widened to 0.08 vertically. The clamp rule is
        // anchor - orbit - radius >= margin, so every unit of extra travel has to come out of the
        // radius: at 0.20 the bass orb's fully-swollen 0.324 of the short side would have exceeded
        // the 0.29 of room left at the top of its new orbit and started clamping on peaks again --
        // the same "big orb that never changes size" this line was written to fix.
        private val ORB_BASE_RADIUS = floatArrayOf(0.26f, 0.21f, 0.16f)

        /**
         * Orbit anchors, as fractions of width and height.
         *
         * Deliberately closer together than before, especially vertically. Two competing goals had
         * to be reconciled: the orbs must MIX, which needs their halos to overlap, and none of them
         * may approach an edge. Both are satisfied by keeping the anchors well inside the frame and
         * spending the available room on orbit amplitude instead of on spread -- so the trio opens
         * out and closes up over a couple of minutes rather than sitting at fixed distances.
         *
         * The numbers are chosen against 16:9 so that anchor + full orbit + fully swollen radius
         * still clears the [ORB_EDGE_MARGIN] on every side without the clamp ever engaging. Vertical
         * is the binding axis on a wide screen, which is why ORB_Y is the tighter of the two.
         */
        // MOVED RIGHT, AND OFF ONE LINE.
        //
        // Two problems with the old 0.42/0.54/0.66 on 0.46/0.54/0.48. First, the album art occupies
        // roughly the left 43% of the screen, and the first orb's anchor of 0.42 put its CENTRE
        // behind the tile — the brightest part of the orb was the part you could not see. Second,
        // three anchors within 0.08 of each other vertically is effectively a straight horizontal
        // line, so the orbs could only ever pass side-to-side through one another and spent much of
        // the cycle piled up in the middle.
        //
        // Now every centre sits clear of the artwork's right edge, and the three are spread over
        // 0.20 of the height so they orbit past each other from genuinely different directions.
        // Halos still bleed left across the tile, which is wanted — light spilling behind the cover
        // is the effect; a bright core hidden behind it is not.
        private val ORB_X = floatArrayOf(0.52f, 0.62f, 0.72f)
        private val ORB_Y = floatArrayOf(0.44f, 0.56f, 0.46f)

        /**
         * Orbit amplitude. Vertical was 0.04 — barely a wobble, and the reason the trio read as
         * sliding left and right rather than moving around each other.
         *
         * The ceiling on this is [ORB_BASE_RADIUS]: anchor + orbit + a fully swollen radius has to
         * stay inside [ORB_EDGE_MARGIN], and on 16:9 the height is what binds. Giving the orbs more
         * room to travel therefore meant making them slightly smaller, which is the trade below.
         */
        private const val ORB_DRIFT_X = 0.08f
        private const val ORB_DRIFT_Y = 0.045f

        /**
         * How far an orb's colour travels toward its neighbour's over one animator cycle. Enough to
         * see the hue move on a long track, small enough that the trio never converges on one shade.
         */
        private const val ORB_HUE_TRAVEL = 0.10f

        /** Starting angle per orb, so they do not set off from the same point on their ellipses. */
        private val ORB_PHASE = floatArrayOf(0f, 2.1f, 4.2f)
        private const val TWO_PI = 6.2831855f

        /**
         * Per-orb smoothing, PER FRAME at 60fps. Attack then release.
         *
         * Ordered by band, and the ordering is physical rather than decorative: bass is slow to rise
         * and slow to fall because a bass note is, treble snaps because a hi-hat is over in 30ms.
         * Release is always slower than attack so the glow trails the hit instead of flickering off
         * with it. At 60fps a rate of 0.10 closes ~86% of a gap in a quarter second.
         */
        /**
         * The frame length the smoothing rates below were tuned against — the old fixed 33 ms
         * loop. Keeping the constants in those units means the tuning survives the move to vsync;
         * [decay] converts them to whatever this frame actually cost.
         */
        /**
         * Orbit periods at Normal speed. Co-prime on purpose: 20/27/34 seconds do not line up
         * again for long enough that the composition never visibly repeats. [setOrbSpeed] scales
         * all three by one factor, which preserves that property.
         */
        private val ORBIT_MS = longArrayOf(20_000L, 27_000L, 34_000L)

        private const val REF_FRAME_MS = 33.33f
        /**
         * Vsyncs per drawn frame. 1 = draw every vsync = a true 60fps.
         *
         * This was 2 (an exactly even 30fps) because the device could not hold 16.6ms: the note in
         * the frame callback measured ~19ms of GPU on ten-odd full-screen SCREEN passes at 1080p,
         * and a steady 30 beat a free-running ~50 whose period kept changing. The half-resolution
         * composite buffer (see onDraw) quartered that fill, which is what put a frame back inside
         * the budget and let this drop to 1. If a future change re-inflates the per-frame fill past
         * 16.6ms, this is the one-line lever back to a steady 30.
         */
        private const val FRAME_STRIDE = 1
        /** Same idea in a PiP window, where the backdrop is small and nobody is studying it. */
        private const val LOW_POWER_STRIDE = 4
        /** Overall loudness follow, used by the full-screen pulse. */
        private const val ENERGY_RATE = 0.36f

        /**
         * Orb spring — MATCHED TO AMTV, which is the whole point of the feel.
         *
         * AMTV drives each band with a Compose `spring(dampingRatio = 0.6, stiffness =
         * StiffnessMediumLow)`. StiffnessMediumLow is 400, mass 1, so omega = sqrt(400) = 20 rad/s
         * and damping = 2 * zeta * omega = 2 * 0.6 * 20 = 24. Those two numbers ARE the smoothness
         * and the thump: at omega 20 the spring carries real momentum through the ~33ms staircase of
         * band levels, so a chain of steps comes out as one gliding curve rather than a stack of
         * little ease-outs, and at zeta 0.6 it overshoots ~9% on a hit — a slow, visible bloom that
         * peaks around pi / (omega*sqrt(1-zeta^2)) ~= 196ms and settles gently. That lingering bloom
         * is what reads as "thumpy".
         *
         * This was briefly RAISED to 1200 (omega 34) to fight apparent lateness — but that snappier
         * spring settles too fast to bloom and loses the glide, i.e. it stops feeling like AMTV,
         * which is exactly the complaint. Lateness is not the spring's job to fix: it belongs to the
         * output-latency compensation on the emit side (emitDelayed in AudioStreamServer). Shape and
         * delay the signal there; follow it here with AMTV's own constants.
         *
         * zeta stays 0.6 (under-damped) deliberately — the overshoot is the flare, and
         * ORB_LEVEL_CEILING below leaves room for it instead of clamping it flat.
         */
        private const val ORB_STIFFNESS = 900f
        private const val ORB_DAMPING = 36f

        /** Integration sub-step. Well inside the 2/omega = 100ms explicit-Euler stability bound. */
        private const val SPRING_MAX_STEP_S = 0.008f

        /**
         * How far the spring may overshoot 1.0. AMTV's spring is under-damped (zeta 0.6) and its
         * overshoot is the flare on a hit, so clamping at 1 would remove the very peak that makes a
         * beat read. A ceiling still exists so a runaway cannot inflate an orb off the screen.
         */
        private const val ORB_LEVEL_CEILING = 1.6f

        /** Fallback smoothing for sources that report loudness but no bands. */
        private val ORB_FOLLOW = floatArrayOf(0.10f, 0.27f, 0.60f)

        /**
         * Clearance kept between any orb and the nearest screen edge, as a fraction of the short
         * side. Raised sharply from 0.06: at 1080p that was a ~65px gap, which is a hair on a 1920px
         * screen and duly read as the orb touching the edge. 0.13 is ~140px of guaranteed black.
         */
        private const val ORB_EDGE_MARGIN = 0.05f
        /**
         * How much the beat grows an orb.
         *
         * Sized against what the bands ACTUALLY deliver rather than against their theoretical range.
         * The device log has them peaking around 0.65-0.70 on real music and only brushing 1.0, so a
         * swell of 0.30 bought a ~20% size change on a loud beat -- present in the numbers, barely
         * readable on screen. At 0.60 the same beat is a ~40% change, and the base radius comes down
         * to keep the fully-swollen orb inside the edge budget.
         */
        /**
         * How far each orb swells at full level, relative to ITS OWN size. Treble punches hardest
         * in proportional terms and is small enough that it still cannot reach the frame edge;
         * bass is already large, so the same figure there would collide with the edge clamp and
         * flatten the very peak it was meant to add.
         */
        /** How hard each orb swells with its band. Treble punches biggest relative to its size. */
        private val ORB_SIZE_RIDE = floatArrayOf(0.75f, 0.85f, 1.05f)

        /** Ceiling on the swell term, so a high intensity cannot inflate an orb without limit. */
        private const val ORB_SWELL_CAP = 2.2f

        /** Halo alpha — constant on purpose; see the note in drawOrb about lifting the black. */
        // Lowered from 0.92. On a projector every bit of this is light thrown at a wall, and the
        // halo covers most of the frame, so the orbs were the brightest thing in a dark room.
        private const val ORB_BASE_ALPHA = 0.24f

        /** The bright heart of each orb: small, so its beat brightness stays local. */
        private const val ORB_CORE_FRAC = 0.34f
        /** How much the halo brightens at full level. Deliberately small -- see the note in drawOrb. */
        private const val ORB_BEAT_ALPHA = 0.42f

        /** Ceiling on halo alpha, so a high intensity setting cannot wash the frame out. */
        private const val ORB_ALPHA_CAP = 0.9f

        private const val ORB_CORE_ALPHA = 0.12f
        private const val ORB_CORE_BEAT_ALPHA = 0.62f
        private const val ORB_CORE_ALPHA_CAP = 0.92f

        /** Fraction of each side dissolved to black. A projector has no bezel; see drawProjectorEdges. */
        private const val ORB_EDGE_FADE = 0.10f
        /** Where the right-hand darkening starts, and how dark it gets under the text. */
        private const val RIGHT_DIM_START = 0.30f
        private const val RIGHT_DIM_COLOR = 0x9E000000.toInt()
        private const val TRANSPARENT = 0x00000000

        /** Base hue mix toward the neighbouring palette slot, before the drift term. */
        private const val ORB_HUE_BASE = 0.05f
        private const val CORE_WHITEN = 0.6f
        private val ORB_CORE_STOPS = floatArrayOf(0f, 1f)

        /**
         * Falloff. Five stops, front-loaded: most of the light is gone by 55% of the radius and only
         * a 2%-alpha whisper survives to 80%. The earlier three-quarter-strength ramp spread a thin
         * wash of colour over nearly the whole frame, so there was no genuinely black region left
         * between the orbs -- they read as one lit haze rather than as separate glows on black. The
         * long, near-zero tail is still what stops a visible ring forming where the gradient ends.
         */
        private val ORB_STOPS = floatArrayOf(0f, 1f)
        private val TEXT_GRAD_COLORS =
            intArrayOf(TEXT_DARKEN_ARGB, TEXT_DARKEN_MID_ARGB, 0x00000000)
        // Three stops, not two. A linear ramp from full darkening to nothing puts the steepest part
        // of the falloff right where the text ends, so the pool reads as a dark disc behind the
        // words. Holding it near-full to 45% and easing out from there hides the boundary.
        private val TEXT_GRAD_STOPS = floatArrayOf(0f, 0.45f, 1f)

        private val DEFAULTS = arrayOf("#1a1a2e", "#16213e", "#0f3460", "#220033", "#2a1a3e", "#0d2b4e")
    }
}
