package com.phairplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.phairplay.util.Logger

class SharedMediaPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile var isPlaying: Boolean = false; private set
    @Volatile var currentPositionMs: Long = 0L; private set
    @Volatile var durationMs: Long = 0L; private set

    private val positionTick = object : Runnable {
        override fun run() {
            player?.let { currentPositionMs = it.currentPosition; durationMs = it.duration.coerceAtLeast(0L) }
            main.postDelayed(this, 500)
        }
    }

    fun load(url: String, onReady: () -> Unit) {
        main.post {
            val p = player ?: ExoPlayer.Builder(context).build().also { ep ->
                ep.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                })
                player = ep
                main.post(positionTick)
            }
            p.stop()
            // A fresh ExoPlayer starts at unity gain, which would silently undo any SetVolume the
            // control point issued before the first track loaded.
            p.volume = if (muted) 0f else volumePercent / 100f
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    val name = when (state) {
                        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> "$state"
                    }
                    Logger.i("SharedMediaPlayer: state=$name")
                    if (state == Player.STATE_READY) { p.removeListener(this); onReady() }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Logger.e("SharedMediaPlayer: playback error ${error.errorCode} ${error.message}")
                }
            })
            Logger.i("SharedMediaPlayer: loading $url")
        }
    }

    fun play()  { main.post { player?.play() } }
    fun pause() { main.post { player?.pause() } }
    fun stop()  { main.post { player?.stop(); player?.clearMediaItems() } }
    fun seekTo(ms: Long) { main.post { player?.seekTo(ms) } }

    /** 0..100, as UPnP RenderingControl speaks it. Applied as ExoPlayer's 0f..1f gain. */
    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        volumePercent = clamped
        main.post { player?.volume = if (muted) 0f else clamped / 100f }
    }

    fun setMuted(mute: Boolean) {
        muted = mute
        main.post { player?.volume = if (mute) 0f else volumePercent / 100f }
    }

    @Volatile var volumePercent: Int = 100; private set
    @Volatile var muted: Boolean = false; private set

    fun release() {
        main.removeCallbacks(positionTick)
        main.post { player?.release(); player = null }
    }
}
