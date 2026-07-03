package com.phairplay.airplay

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.phairplay.util.Logger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,
    val readyToPlay: Boolean,
)

class AirPlayVideoPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    @Volatile private var player: ExoPlayer? = null
    @Volatile private var ready = false

    fun play(url: String, startPositionFraction: Double) {
        release()
        Logger.i("AirPlay video: play url=$url start=$startPositionFraction")
        val exo = ExoPlayer.Builder(context).build()
        player = exo
        ready = false
        surfaceProvider()?.let { exo.setVideoSurface(it) }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    if (startPositionFraction > 0.0)
                        exo.seekTo((startPositionFraction * exo.duration).toLong())
                    Logger.i("AirPlay video: ready dur=${exo.duration}ms")
                } else if (state == Player.STATE_ENDED) {
                    Logger.i("AirPlay video: ended")
                    onEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Logger.e("AirPlay video error: ${error.message}")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        exo.prepare()
        exo.playWhenReady = true
    }

    fun setRate(rate: Float) {
        val exo = player ?: return
        if (rate <= 0f) exo.pause() else exo.play()
    }

    fun scrub(positionSec: Double) {
        player?.seekTo((positionSec * 1000).toLong())
    }

    fun attachSurface() {
        player?.let { surfaceProvider()?.let { s -> it.setVideoSurface(s) } }
    }

    fun info(): PlaybackInfo? {
        val exo = player ?: return null
        if (!ready) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        return PlaybackInfo(
            durationSec = exo.duration.coerceAtLeast(0) / 1000.0,
            positionSec = exo.currentPosition / 1000.0,
            rate = if (exo.isPlaying) 1.0 else 0.0,
            readyToPlay = true
        )
    }

    fun release() {
        player?.release()
        player = null
        ready = false
    }
}
