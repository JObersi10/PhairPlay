package com.phairplay.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.view.KeyEvent
import android.media.AudioManager
import com.phairplay.homekit.HomeKitActions
import com.phairplay.homekit.RemoteKey
import com.phairplay.util.Logger

/**
 * HomeKitBridge — turns HomeKit intents into things PhairPlay can actually do on a Fire TV.
 *
 * Kept out of [PhairPlayService] because the interesting content here is the set of NEGATIVE
 * results — the things Android will not let a normal app do — and they deserve to be stated in one
 * place rather than scattered through the service:
 *
 *  - **There is no power off.** No public API powers down a Fire TV. `lockNow()` via device admin
 *    is the closest available behaviour and blanks the display; without that grant, "off" can only
 *    end the session and leave the foreground. Both are implemented, and which one happened is
 *    logged, because a power button that silently does nothing is worse than one that says so.
 *
 *  - **There is no global key injection.** `INJECT_EVENTS` is signature-level, so HomeKit arrow
 *    keys cannot drive Fire TV's launcher. What they CAN do is drive the connected sender: the
 *    transport keys map onto the DACP commands PhairPlay already sends, so pausing from the Home
 *    app pauses the iPhone that is streaming. That is genuinely end-to-end and is the part of the
 *    remote worth having.
 *
 *  - **Volume is real.** It is the device's own STREAM_MUSIC, adjusted the same way the physical
 *    remote does.
 */
class HomeKitBridge(
    private val context: Context,
    private val onEndSession: () -> Unit,
    private val onBringToFront: () -> Unit,
    private val onWakeDisplay: () -> Unit,
    private val onSendRemoteCommand: (String) -> Unit,
    /** Delivers a D-pad/Back/Info press into PhairPlay's own Activity. */
    private val onNavKey: (Int) -> Unit,
) : HomeKitActions {

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun setActive(on: Boolean) {
        if (on) {
            Logger.i("HomeKit: turning on — waking display and showing PhairPlay")
            onWakeDisplay()
            onBringToFront()
            return
        }
        Logger.i("HomeKit: turning off — ending session")
        onEndSession()
        if (!sleepDisplay()) {
            Logger.i("HomeKit off: no device-admin grant, so the session ended but the TV stays awake")
        }
    }

    /**
     * Blanks the display via device admin.
     *
     * @return true if the device actually went to sleep. Device admin is opt-in and most users will
     *   not have granted it, so the false path is the normal one and must not look like an error.
     */
    private fun sleepDisplay(): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, PhairPlayDeviceAdmin::class.java)
        if (!dpm.isAdminActive(admin)) return false
        dpm.lockNow()
        Logger.i("HomeKit off: display locked via device admin")
        true
    }.getOrElse {
        Logger.w("HomeKit off: lockNow failed — ${it.message}")
        false
    }

    /**
     * Maps a HomeKit remote key onto a DACP command for the connected sender.
     *
     * Navigation keys (arrows, select, back) have no DACP equivalent and cannot be injected into
     * the system, so they are deliberately dropped with a log rather than silently swallowed —
     * otherwise a user pressing arrows in the Home app has no way to tell whether the key arrived.
     */
    /**
     * Handles a press on the iPhone's Control Center remote.
     *
     * The keys split into two groups that go to genuinely different places, and conflating them is
     * why most of the remote did nothing:
     *
     *  - **Transport** (play/pause, skip, scrub) belongs to whatever is PLAYING, which is the
     *    sender. These go out over DACP/MediaRemote to the phone or Mac.
     *
     *  - **Navigation** (arrows, select, back, exit, info) belongs to whatever is ON SCREEN, which
     *    is PhairPlay itself. There is no sender equivalent — an iPhone mirroring its display has
     *    no notion of "up" — so these were logged as unmappable and dropped, leaving two thirds of
     *    the remote inert.
     *
     * Navigation is delivered into our own Activity rather than injected system-wide: INJECT_EVENTS
     * is a signature permission no sideloaded app can hold, so the honest scope is our own window.
     * Pressing Up while the Fire TV launcher is in front does nothing, and that is a real limit
     * rather than a bug.
     */
    override fun remoteKey(key: Int) {
        navKeyFor(key)?.let { keyCode ->
            Logger.i("HomeKit remote key $key → local key ${KeyEvent.keyCodeToString(keyCode)}")
            onNavKey(keyCode)
            return
        }

        val command = when (key) {
            RemoteKey.PLAY_PAUSE -> "playpause"
            RemoteKey.NEXT_TRACK -> "nextitem"
            RemoteKey.PREVIOUS_TRACK -> "previtem"
            RemoteKey.FAST_FORWARD -> "beginff"
            RemoteKey.REWIND -> "beginrew"
            else -> null
        }
        if (command == null) {
            Logger.i("HomeKit remote key $key is not one we map — ignored")
            return
        }
        Logger.i("HomeKit remote key $key → DACP $command")
        onSendRemoteCommand(command)
    }

    /** The D-pad half of the remote, as Android key codes. Null for anything transport-related. */
    private fun navKeyFor(key: Int): Int? = when (key) {
        RemoteKey.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
        RemoteKey.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        RemoteKey.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        RemoteKey.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        RemoteKey.SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
        RemoteKey.BACK -> KeyEvent.KEYCODE_BACK
        // EXIT is the Home app's "leave this app" key; on a TV that is the same gesture as Back
        // rather than HOME, which would drop the user out of PhairPlay entirely.
        RemoteKey.EXIT -> KeyEvent.KEYCODE_BACK
        // Info flips the now-playing card to its credits side, matching the Menu/Info mapping the
        // physical Fire TV remote already has.
        RemoteKey.INFORMATION -> KeyEvent.KEYCODE_INFO
        else -> null
    }

    override fun volumeStep(up: Boolean) {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }.onFailure { Logger.w("HomeKit volume step failed: ${it.message}") }
    }

    override fun setMute(muted: Boolean) {
        runCatching {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
        }.onFailure { Logger.w("HomeKit mute failed: ${it.message}") }
    }

    override fun selectInput(identifier: Int) {
        // Inputs are advertised so the Home app renders a real TV tile rather than a bare switch.
        // Selecting one brings PhairPlay forward; the sender decides what actually plays.
        Logger.i("HomeKit input selected: $identifier")
        onBringToFront()
    }

    override fun identify() {
        Logger.i("HomeKit identify — waking the display so the user can see which device this is")
        onWakeDisplay()
        onBringToFront()
    }
}
