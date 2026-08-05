package com.phairplay.airplay

import android.content.Context
import android.view.Surface

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,
    val readyToPlay: Boolean,
)

/**
 * JVM stub for [com.phairplay.airplay.AirPlayVideoPlayer].
 *
 * Same reason as SharedMediaPlayer: the real one is ExoPlayer-based, and androidx.media3 ships as
 * AARs that need AGP, which this module runs without. AirPlayReceiver owns one, so the type must
 * exist for the RTSP protocol tests to compile. No test drives URL video, so this is inert.
 *
 * PlaybackInfo is declared here rather than left in the real file because it is a plain data class
 * the protocol tests do read, and excluding the file would take it away with everything else.
 */
@Suppress("UNUSED_PARAMETER")
class AirPlayVideoPlayer(
    context: Context?,
    surfaceProvider: () -> Surface?,
    onEnded: () -> Unit = {},
) {
    fun play(url: String, startPositionFraction: Double) {}
    fun setRate(rate: Float) {}
    fun scrub(positionSec: Double) {}
    fun attachSurface() {}
    fun info(): PlaybackInfo? = null
    fun release() {}
}
