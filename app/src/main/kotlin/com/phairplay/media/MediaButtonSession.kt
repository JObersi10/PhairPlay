package com.phairplay.media

import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.phairplay.util.Logger

/**
 * MediaButtonSession — receives the remote's transport keys.
 *
 * WHY THIS EXISTS: PhairPlay handled media keys in `MainActivity.onKeyDown` and they mostly did
 * nothing. The device log settles why. Across a whole listening session the Activity logged exactly
 * one media key:
 *
 *     Key KEYCODE_MEDIA_FAST_FORWARD mode=AUDIO
 *
 * and no play/pause at all — not "dropped", not "ignored", simply never delivered. Fire OS routes
 * PLAY/PAUSE to whichever app owns an active [MediaSession] rather than to the focused Activity's
 * key handler, so an app without a session never sees the button. Skip keys happen to fall through
 * to the Activity, which is why exactly one of the three appeared to work.
 *
 * Owning a session is also what makes the keys work when PhairPlay is NOT in front — in PiP, or
 * with the screen off — which is the case the Activity path could never have covered.
 *
 * Uses the framework [MediaSession] (API 21+) rather than MediaSessionCompat: it needs no new
 * dependency and nothing here requires the compat layer's back-port behaviour.
 */
class MediaButtonSession(
    context: Context,
    /** Invoked with a [com.phairplay.airplay.DacpClient] command constant. */
    private val onCommand: (String) -> Unit,
) {

    private val session = MediaSession(context, TAG).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = send(com.phairplay.airplay.DacpClient.CMD_PLAY_RESUME)
            override fun onPause() = send(com.phairplay.airplay.DacpClient.CMD_PAUSE)
            override fun onSkipToNext() = send(com.phairplay.airplay.DacpClient.CMD_NEXT)
            override fun onSkipToPrevious() = send(com.phairplay.airplay.DacpClient.CMD_PREV)
            override fun onStop() = send(com.phairplay.airplay.DacpClient.CMD_PAUSE)
            override fun onFastForward() = send(com.phairplay.airplay.DacpClient.CMD_NEXT)
            override fun onRewind() = send(com.phairplay.airplay.DacpClient.CMD_PREV)
        })
    }

    private fun send(command: String) {
        Logger.i("Media button → $command")
        onCommand(command)
    }

    /**
     * Publishes a playback state and takes ownership of the media buttons.
     *
     * The state matters as much as being active: the system routes buttons to the session it
     * believes is playing, and the available ACTIONS decide which buttons are offered at all — a
     * session that never declares PLAY_PAUSE will not be sent one.
     */
    fun setPlaying(playing: Boolean) {
        runCatching {
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_STOP,
                    )
                    // Position is deliberately unknown: the sender owns the timeline, and claiming
                    // a position we would have to keep in sync buys nothing for button routing.
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build(),
            )
            if (!session.isActive) {
                session.isActive = true
                Logger.i("Media button session active — transport keys now reach PhairPlay")
            }
        }.onFailure { Logger.w("Media session state failed: ${it.message}") }
    }

    /** Gives the buttons back. Called when no session is streaming. */
    fun release() {
        runCatching {
            session.isActive = false
            session.release()
        }
    }

    private companion object {
        const val TAG = "PhairPlay"
    }
}
