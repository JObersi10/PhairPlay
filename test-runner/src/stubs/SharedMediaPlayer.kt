package com.phairplay.media

import android.content.Context

/**
 * JVM stub for [com.phairplay.media.SharedMediaPlayer].
 *
 * The real implementation is built on ExoPlayer (androidx.media3), which ships as AARs and cannot
 * be resolved without the Android Gradle Plugin — and this module deliberately runs without it.
 * DlnaServer holds a SharedMediaPlayer, so the type has to exist for DLNA's protocol tests to
 * compile; nothing in those tests exercises playback, so every member here is inert.
 *
 * Keep this in sync with the real class's public surface, or the protocol tests stop compiling.
 */
@Suppress("UNUSED_PARAMETER")
class SharedMediaPlayer(context: Context?) {

    @Volatile var isPlaying: Boolean = false; private set
    @Volatile var currentPositionMs: Long = 0L; private set
    @Volatile var durationMs: Long = 0L; private set

    fun load(url: String, onReady: () -> Unit) {}
    fun play() {}
    fun pause() {}
    fun stop() {}
    fun seekTo(ms: Long) {}
    fun release() {}

    // Volume state is real here, not inert: the RenderingControl tests assert that SetVolume is
    // applied and read back rather than acknowledged and dropped, which was the bug.
    @Volatile var volumePercent: Int = 100; private set
    @Volatile var muted: Boolean = false; private set

    fun setVolumePercent(percent: Int) { volumePercent = percent.coerceIn(0, 100) }
    fun setMuted(mute: Boolean) { muted = mute }
}
