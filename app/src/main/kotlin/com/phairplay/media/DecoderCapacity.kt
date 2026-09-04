package com.phairplay.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.phairplay.util.Logger

/**
 * How many H.264 streams this device can decode at once.
 *
 * This is the binding constraint on multi-screen casting: every simultaneous mirror needs its own
 * `MediaCodec`, and a TV stick has a small, fixed number of hardware decoder instances. Asking the
 * question is cheap; guessing is not, because the failure mode of over-committing is a second
 * sender that connects, negotiates, and then shows nothing.
 *
 * Measured on the Fire TV this project targets (MediaTek): `OMX.MTK.VIDEO.DECODER.AVC` declares
 * `concurrent-instances max="5"` and a `performance-point-1920x1080` of 120, i.e. enough decode
 * budget for two 1080p60 mirrors with room to spare.
 *
 * `maxSupportedInstances` is what the vendor claims, not what the device will actually hand out
 * under memory pressure, so treat the result as an upper bound rather than a promise.
 */
object DecoderCapacity {

    /** Fallback when the query fails outright. One decoder is the only number always safe. */
    private const val UNKNOWN = 1

    /** The stream size and rate a mirror is budgeted at. One tile is assumed to cost this much. */
    private const val REFERENCE_WIDTH = 1920
    private const val REFERENCE_HEIGHT = 1080
    private const val REFERENCE_FPS = 60.0

    @Volatile
    private var cached: Int? = null

    /**
     * Vendor-declared maximum concurrent AVC decoder instances, across every non-secure hardware
     * decoder on the device. Queried once and cached — the codec list is immutable for the life of
     * the process, and walking it is not free.
     */
    fun maxConcurrentAvcDecoders(): Int {
        cached?.let { return it }
        val measured = runCatching { query() }
            .onFailure { Logger.i("Decoder capacity query failed, assuming $UNKNOWN: ${it.message}") }
            .getOrDefault(UNKNOWN)
        cached = measured
        return measured
    }

    private fun query(): Int {
        var hardwareBest = 0
        var hardwareName = "none"
        var softwareBest = 0

        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            // Secure decoders are reserved for DRM playback and are separately (and much more
            // tightly) limited — counting one would overstate what mirroring can have.
            if (info.name.endsWith(".secure")) continue
            if (MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) continue
            val caps: MediaCodecInfo.CodecCapabilities =
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                    .getOrNull() ?: continue
            val instances = caps.maxSupportedInstances
            if (isHardware(info)) {
                if (instances > hardwareBest) {
                    hardwareBest = instances
                    hardwareName = info.name
                }
            } else if (instances > softwareBest) {
                softwareBest = instances
            }
        }

        // The software decoder's limit is not a capability, it is an absence of one: `c2.android`
        // advertises 32 instances because nothing in it is a fixed hardware resource. Taking the
        // max across every decoder therefore reported 32 on a stick that has five real ones, which
        // would have sized the tile grid off a number the CPU cannot honour — software-decoding two
        // 1080p60 mirrors is not something this device can do at all.
        val answer = if (hardwareBest > 0) hardwareBest else softwareBest
        Logger.i("Decoder capacity: hardware=$hardwareBest ($hardwareName), software=$softwareBest " +
                 "— using $answer")
        return answer.coerceAtLeast(UNKNOWN)
    }

    /**
     * Whether [info] is a hardware decoder.
     *
     * `isHardwareAccelerated` only exists from API 29; below that the naming convention is the only
     * signal available, and it is a reliable one — Google's own software decoders are the
     * `OMX.google.` and `c2.android.` prefixes, and everything else is the vendor's.
     */
    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }

    /**
     * How many simultaneous mirrors the hardware could support, leaving one decoder in reserve.
     *
     * The reserve is not superstition: DLNA and AirPlay URL playback both build their own decoder
     * through ExoPlayer, and a mirror session that consumed the last instance would make the next
     * video play fail with no obvious cause.
     */
    fun maxConcurrentMirrors(): Int {
        val byInstances = (maxConcurrentAvcDecoders() - 1).coerceAtLeast(1)
        val byThroughput = mirrorsWithinFrameBudget()
        val answer = minOf(byInstances, byThroughput)
        Logger.i("Mirror capacity: $byInstances by decoder count, $byThroughput by frame budget — using $answer")
        return answer
    }

    /**
     * How many 1080p60 mirrors fit in the decoder's advertised throughput.
     *
     * **This, not the instance count, is what actually binds.** The Fire TV reports five concurrent
     * AVC instances and a `performance-point-1920x1080` of 120 — two 1080p60 streams and the budget
     * is spent. Sizing by instances alone admitted a third sender, which then negotiated cleanly,
     * decoded nothing, and sat on a frozen frame: strictly worse than the immediate refusal it
     * replaced, because the sender believes it is mirroring.
     *
     * Measured with the codec's own numbers rather than assumed, because a different device will
     * have a different answer and this is exactly the sort of constant that would go stale silently.
     */
    private fun mirrorsWithinFrameBudget(): Int {
        val fps = runCatching { advertisedFrameRate(REFERENCE_WIDTH, REFERENCE_HEIGHT) }.getOrNull()
            ?: return UNKNOWN
        if (fps <= 0.0) return UNKNOWN
        return (fps / REFERENCE_FPS).toInt().coerceAtLeast(UNKNOWN)
    }

    /**
     * The decoder's total throughput, in pixels per second.
     *
     * A COUNT OF STREAMS IS THE WRONG UNIT and this is the right one. "How many mirrors fit" has no
     * fixed answer, because it depends entirely on what they are streaming: a 1440p Mac is 1.78x the
     * pixels of a 1080p phone, so two of those do not cost the same as two of these. Budgeting in
     * pixels lets each sender be charged for what it actually negotiated, which is the difference
     * between admitting a sender that works and admitting one that freezes.
     *
     * Derived from the hardware decoder's own advertised frame rate at 1080p — on this Fire TV,
     * 120fps, i.e. 1920 x 1080 x 120 pixels per second. Nothing here is a constant anyone typed.
     */
    fun pixelBudgetPerSecond(): Long {
        budget?.let { return it }
        val fps = runCatching { advertisedFrameRate(REFERENCE_WIDTH, REFERENCE_HEIGHT) }.getOrNull()
        val measured = if (fps == null || fps <= 0.0) {
            // No usable answer: budget exactly one reference stream, which is what the receiver
            // did before any of this existed.
            REFERENCE_WIDTH.toLong() * REFERENCE_HEIGHT * REFERENCE_FPS.toLong()
        } else {
            (REFERENCE_WIDTH.toLong() * REFERENCE_HEIGHT * fps).toLong()
        }
        budget = measured
        Logger.i("Decoder throughput: ${measured / 1_000_000}M pixels/sec " +
                 "(${REFERENCE_WIDTH}x$REFERENCE_HEIGHT @ ${fps ?: REFERENCE_FPS}fps)")
        return measured
    }

    @Volatile private var budget: Long? = null

    /** What one mirror of this size costs per second, assumed at the reference frame rate. */
    fun costOf(width: Int, height: Int): Long =
        width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1) * REFERENCE_FPS.toLong()

    /** The hardware decoder's advertised frame rate for one stream of the given size. */
    private fun advertisedFrameRate(width: Int, height: Int): Double? {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || info.name.endsWith(".secure")) continue
            if (MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) continue
            if (!isHardware(info)) continue
            val video = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            }.getOrNull() ?: continue
            val rate = runCatching {
                video.getSupportedFrameRatesFor(width, height).upper
            }.getOrNull() ?: continue
            return rate
        }
        return null
    }
}
