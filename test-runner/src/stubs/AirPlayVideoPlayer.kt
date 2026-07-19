package com.phairplay.airplay

import android.content.Context
import android.view.Surface

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
    fun play(url: String, startPositionFraction: Double) = Unit
    fun setRate(rate: Float) = Unit
    fun scrub(positionSec: Double) = Unit
    fun attachSurface() = Unit
    fun info(): PlaybackInfo? = null
    fun release() = Unit
}
