package com.phairplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.phairplay.util.Logger

/**
 * How far a sender's volume slider is allowed to reach.
 *
 * AirPlay senders transmit an absolute volume with every change (`SET_PARAMETER volume=`), in dB
 * from [MIN_DB] to 0. Historically PhairPlay only applied that as a software gain on the decoded
 * PCM, which is inaudible to anything downstream — the TV, a soundbar, or a Bluetooth speaker kept
 * whatever level they were already at.
 *
 * Routing it to [AudioManager] instead makes the phone's slider move the real output level, but only
 * on routes that actually honour a stream-volume change:
 *
 *  - **Bluetooth A2DP** — works. Absolute volume over AVRCP means the Android media stream volume
 *    *is* the speaker's volume.
 *  - **HDMI / eARC to a soundbar** — usually works, depending on whether Fire TV forwards it as CEC.
 *  - **The TV's own speakers** — generally does not. Fire TV delegates that to the panel over CEC or
 *    IR, which an app cannot drive, and [AudioManager.isVolumeFixed] reports true on those routes.
 *
 * Hence the default of [EXTERNAL_ONLY]: take over the hardware volume when the route can follow it,
 * and quietly fall back to software gain when it can't, rather than appearing to do nothing.
 */
enum class VolumeControlMode {
    /** Never touch device volume — software gain only (the original behaviour). */
    OFF,

    /** Drive device volume for Bluetooth and HDMI/external routes; software gain for TV speakers. */
    EXTERNAL_ONLY,

    /** Always attempt device volume, whatever the route. */
    ALWAYS;

    companion object {
        fun fromKey(key: String?): VolumeControlMode =
            entries.firstOrNull { it.name == key } ?: EXTERNAL_ONLY
    }
}

/**
 * Translates AirPlay volume into an Android media-stream level, and reports back what it managed to
 * do so the UI can show the user the real number instead of a guess.
 */
class DeviceVolumeController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * The outcome of one volume change.
     *
     * @param percent          0–100, derived from the sender's dB value.
     * @param step             The stream-volume index actually set (or current, if not applied).
     * @param maxStep          How many steps this route offers — 15 on most Fire TV builds, but a
     *                         Bluetooth speaker can expose a different granularity.
     * @param route            Human-readable output route, for display.
     * @param appliedToDevice  False when we fell back to software gain.
     */
    data class VolumeReport(
        val percent: Int,
        val step: Int,
        val maxStep: Int,
        val route: String,
        val appliedToDevice: Boolean,
    ) {
        /** e.g. "62% · 9/15 · Bluetooth" — compact enough for the info panel. */
        val display: String
            get() = buildString {
                append("$percent%")
                if (maxStep > 0) append(" · $step/$maxStep")
                append(" · $route")
                if (!appliedToDevice) append(" (app only)")
            }
    }

    /**
     * Applies [airplayDb] according to [mode].
     *
     * @return a report of what happened, or null if the sender's value was unusable.
     */
    fun apply(airplayDb: Float, mode: VolumeControlMode): VolumeReport {
        val fraction = toFraction(airplayDb)
        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
        val maxStep = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val route = describeRoute()

        val shouldApply = when (mode) {
            VolumeControlMode.OFF           -> false
            VolumeControlMode.ALWAYS        -> true
            VolumeControlMode.EXTERNAL_ONLY -> isExternalRoute()
        } && !isVolumeFixed() && maxStep > 0

        if (!shouldApply) {
            val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
                .getOrDefault(0)
            return VolumeReport(percent, current, maxStep, route, appliedToDevice = false)
        }

        // Round rather than truncate: with only 15 steps, truncating loses a step on almost every
        // change and the slider feels like it lags a notch behind the phone.
        val step = Math.round(fraction * maxStep).coerceIn(0, maxStep)
        val ok = runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        }.onFailure {
            // Thrown when Do Not Disturb owns the stream and we lack notification-policy access.
            Logger.w("Could not set device volume: ${it.message}")
        }.isSuccess

        return VolumeReport(percent, step, maxStep, route, appliedToDevice = ok)
    }

    /** Current level as a report, without changing anything — used to seed the UI. */
    fun currentReport(): VolumeReport {
        val maxStep = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val step = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val percent = if (maxStep > 0) step * 100 / maxStep else 0
        return VolumeReport(percent, step, maxStep, describeRoute(), appliedToDevice = false)
    }

    /** AirPlay sends −30 dB … 0 dB, with anything at or below −144 meaning muted. */
    private fun toFraction(airplayDb: Float): Float =
        if (airplayDb <= MUTE_DB) 0f else ((airplayDb - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)

    private fun isVolumeFixed(): Boolean = runCatching { audioManager.isVolumeFixed }.getOrDefault(false)

    /** True when audio is leaving the TV for something that manages its own level. */
    private fun isExternalRoute(): Boolean = outputTypes().any { it in EXTERNAL_TYPES }

    private fun describeRoute(): String {
        val types = outputTypes()
        return when {
            types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "Bluetooth"
            types.any { it == AudioDeviceInfo.TYPE_HDMI ||
                        it == AudioDeviceInfo.TYPE_HDMI_ARC } -> "HDMI"
            types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "Headphones"
            types.contains(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) -> "TV speakers"
            else -> "Unknown"
        }
    }

    private fun outputTypes(): List<Int> =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.getOrDefault(emptyList())

    companion object {
        private const val MIN_DB = -30f
        private const val MUTE_DB = -144f

        private val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
    }
}
