package com.phairplay.airplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

/**
 * AirPlay video URL mode — the TV fetches and plays the media itself.
 *
 * This is what YouTube, Safari and the Photos app use when you AirPlay a *video* rather than mirror
 * the screen: the sender never streams pixels, it just hands over a URL (usually an HLS playlist)
 * with `POST /play` and then drives transport with `/rate`, `/scrub` and `/stop`. Picture quality is
 * therefore the source's own, not a re-encode of the phone's screen.
 *
 * DRM-protected services (Netflix, Disney+) will not appear here at all — they refuse to hand out a
 * playable URL to a non-FairPlay receiver, and that is not something the receiver can work around.
 *
 * ── Threading ────────────────────────────────────────────────────────────────────────────────────
 * Every public method here is called from an RTSP socket thread, and ExoPlayer is single-threaded:
 * it throws IllegalStateException ("Player is accessed on the wrong thread") the moment it is
 * touched from anywhere but the Looper it was built on. So every call is posted to the main looper,
 * and [info] — which the sender polls over `GET /playback-info` from that same network thread —
 * reads a snapshot kept up to date *by* the main thread instead of querying the player directly.
 */
class AirPlayVideoPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    /** Main-thread only. */
    private var player: ExoPlayer? = null

    /** Written on the main thread, read from RTSP threads. */
    @Volatile private var snapshot: PlaybackInfo? = null

    /**
     * The surface belongs to a SurfaceView that only becomes visible once the Activity reacts to
     * this session starting, so it is reliably null for the first few hundred milliseconds. Held
     * here so the polling tick can attach it the moment it appears — without this the media plays
     * with sound but no picture.
     *
     * Tracked as the surface *instance* rather than a boolean: backgrounding the app or entering
     * PiP destroys the SurfaceView's surface and builds a new one, and a boolean would leave the
     * player pushing frames at the dead one for the rest of the session.
     */
    private var attachedSurface: Surface? = null

    private val tick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val current = surfaceProvider()
            if (current != null && current != attachedSurface) {
                exo.setVideoSurface(current)
                attachedSurface = current
            }
            val duration = exo.duration
            snapshot = PlaybackInfo(
                // ExoPlayer reports C.TIME_UNSET (a large negative) until the manifest is parsed,
                // and a live stream never has a duration at all. Either way the sender wants 0.
                durationSec = if (duration > 0) duration / 1000.0 else 0.0,
                positionSec = exo.currentPosition.coerceAtLeast(0L) / 1000.0,
                rate = if (exo.isPlaying) 1.0 else 0.0,
                readyToPlay = exo.playbackState == Player.STATE_READY ||
                    exo.playbackState == Player.STATE_BUFFERING
            )
            main.postDelayed(this, POLL_MS)
        }
    }

    fun play(url: String, startPositionFraction: Double) = onMain {
        releaseOnMain()
        Logger.i("AirPlay video: play url=$url start=$startPositionFraction")
        val exo = ExoPlayer.Builder(context).build()
        player = exo
        attachedSurface = null
        snapshot = PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        surfaceProvider()?.let { exo.setVideoSurface(it); attachedSurface = it }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        // Start-Position is a fraction of the whole, so it can only be resolved
                        // once the duration is known — which is here, not at play() time.
                        if (startPositionFraction > 0.0 && exo.duration > 0) {
                            exo.seekTo((startPositionFraction * exo.duration).toLong())
                        }
                        Logger.i("AirPlay video: ready dur=${exo.duration}ms")
                    }
                    Player.STATE_ENDED -> {
                        Logger.i("AirPlay video: ended")
                        onEnded()
                    }
                    // IDLE and BUFFERING need no action: the polling tick already publishes them
                    // to the sender through readyToPlay.
                    Player.STATE_IDLE, Player.STATE_BUFFERING -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Surfaced rather than swallowed: an unplayable URL leaves the TV on a black
                // screen while the sender still believes it is casting, so the log is the only
                // way to tell that apart from a decode stall.
                Logger.e("AirPlay video error (${error.errorCodeName}): ${error.message}")
                onEnded()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        exo.prepare()
        exo.playWhenReady = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun setRate(rate: Float) = onMain {
        val exo = player ?: return@onMain
        if (rate <= 0f) exo.pause() else exo.play()
    }

    fun scrub(positionSec: Double) = onMain {
        player?.seekTo((positionSec * 1000).toLong().coerceAtLeast(0L))
    }

    /** Re-attaches the output surface after the Activity rebuilds it (backgrounding, PiP). */
    fun attachSurface() = onMain {
        val exo = player ?: return@onMain
        surfaceProvider()?.let { exo.setVideoSurface(it); attachedSurface = it }
    }

    /** Safe to call from any thread — returns the last snapshot the main thread published. */
    fun info(): PlaybackInfo? = snapshot

    fun release() = onMain { releaseOnMain() }

    private fun releaseOnMain() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        attachedSurface = null
        snapshot = null
    }

    /**
     * Runs [block] on the main thread, inline when already there. Posting unconditionally would
     * reorder a release() behind a play() issued from the main thread itself.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        /** Matches the cadence senders poll `/playback-info` at; also drives the surface retry. */
        const val POLL_MS = 250L
    }
}
