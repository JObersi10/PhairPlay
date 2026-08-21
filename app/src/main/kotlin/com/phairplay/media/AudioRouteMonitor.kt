package com.phairplay.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.phairplay.settings.AudioRoute
import com.phairplay.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches which output the Fire TV is actually playing through, so a remembered A/V trim can follow
 * the speaker rather than the app.
 *
 * ## What this can and cannot know
 *
 * It knows the *route*: HDMI, the TV's own speakers, a wired headset, or a specific Bluetooth sink,
 * identified stably enough to key a saved setting against. It does **not** know that route's
 * latency. Android has no API for a Bluetooth link's delay, and the one measurement that exists —
 * `AudioTrack.getTimestamp()`, already used by `AudioStreamServer.outputLatencyMs()` — stops at the
 * HAL. Everything past that (the SBC encoder, the radio link, the speaker's own jitter buffer) is
 * invisible from here, and it is the majority of the delay.
 *
 * So the design is not "measure it", which is not possible, but "ask once, then never again":
 * the user tunes by ear the first time a speaker is used, and the value comes back automatically
 * every time that speaker reconnects.
 *
 * ## Picking the active device
 *
 * [AudioManager.getDevices] lists what is *connected*, not what is *playing*, and on this hardware
 * HDMI and the built-in speaker are both permanently present. Order of preference below mirrors
 * Android's own routing policy: a connected A2DP sink wins, then a wired headset, then HDMI, then
 * the built-in speaker. When a stream is running, [setRoutedDevice] overrides all of that with what
 * AudioTrack reports it is genuinely writing to, which is authoritative.
 */
class AudioRouteMonitor(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _route = MutableStateFlow(AudioRoute.UNKNOWN)

    /** The current output. Re-emits only on an actual change, so collectors can act on every value. */
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    /** Set once a stream is running, and cleared when it stops. Beats the connected-device guess. */
    private var routedOverride: AudioRoute? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    private var registered = false

    fun start() {
        if (registered) return
        val manager = audioManager ?: run {
            Logger.w("Audio route: no AudioManager — per-output A/V trim disabled")
            return
        }
        // Main looper, not the caller's: the service starts this from a coroutine, and a Handler
        // built on a thread with no Looper throws rather than failing quietly at the callback.
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        audioManager?.unregisterAudioDeviceCallback(callback)
        registered = false
    }

    /**
     * Reports the device an active AudioTrack is routed to. Pass null when playback stops.
     *
     * This is the only fully trustworthy answer — the connected-device scan is an inference — so it
     * takes precedence for as long as a stream is up.
     */
    fun setRoutedDevice(device: AudioDeviceInfo?) {
        routedOverride = device?.let(::toRoute)
        refresh()
    }

    private fun refresh() {
        val next = routedOverride ?: detectFromConnectedDevices()
        val previous = _route.value
        if (next == previous) return
        _route.value = next
        Logger.i("Audio route: ${previous.label.ifBlank { previous.key }} -> ${next.label} (${next.key})")
    }

    private fun detectFromConnectedDevices(): AudioRoute {
        val manager = audioManager ?: return AudioRoute.UNKNOWN
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            ?.toList()
            .orEmpty()
        if (outputs.isEmpty()) return AudioRoute.UNKNOWN
        val best = PREFERENCE.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
            ?: outputs.first()
        return toRoute(best)
    }

    private fun toRoute(device: AudioDeviceInfo): AudioRoute {
        val bluetooth = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val name = runCatching { device.productName?.toString() }.getOrNull().orEmpty().trim()
        return if (bluetooth) {
            // The MAC is the only identity that survives a rename, but getAddress is API 28 and
            // Android 12 withholds it without BLUETOOTH_CONNECT, handing back an empty string rather
            // than an error. The product name is a fine fallback: two speakers with the same name
            // share one trim, a far smaller problem than a trim that resets whenever the address is
            // unreadable — and on a Fire TV (API 30) the address is normally there.
            val address =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    runCatching { device.address }.getOrNull().orEmpty().trim()
                } else ""
            val identity = address.ifBlank { name }.ifBlank { "unnamed" }
            AudioRoute(key = "bt:$identity", label = name.ifBlank { "Bluetooth speaker" }, isBluetooth = true)
        } else {
            val key = when (device.type) {
                AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                else -> "type:${device.type}"
            }
            AudioRoute(key = key, label = LABELS[key] ?: name.ifBlank { "Audio output" }, isBluetooth = false)
        }
    }

    private companion object {
        /** Android's own routing order, so the inference matches what the mixer will actually do. */
        val PREFERENCE = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )

        // Not string resources: this class has no Context beyond the system service, and the labels
        // are also written to the diagnostic dump, which is English-only by design.
        val LABELS = mapOf(
            "hdmi" to "HDMI / TV",
            "speaker" to "Built-in speakers",
            "wired" to "Wired headphones",
        )
    }
}
