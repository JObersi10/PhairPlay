package com.phairplay.ui

import android.content.Context
import android.provider.Settings

/**
 * Whether this device wants animation at all.
 *
 * Android has no `prefers-reduced-motion`; the equivalent signal is the developer-options animator
 * scale, which people genuinely set to 0 both for accessibility and to make a slow Fire TV stick
 * feel quicker. The platform applies that scale to ObjectAnimator and ViewPropertyAnimator on its
 * own, so those already honour it — but a hand-driven Handler loop like [ReceiverFieldView] does
 * not, and would keep breathing on a device where the user has asked for stillness.
 *
 * Reduced motion is not "no feedback": the field still draws, it simply holds still, and focus
 * still changes surface and ring. Only the movement goes away.
 */
object Motion {

    /** Read once — this is a developer setting, not something that changes mid-session. */
    fun animationsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }.getOrDefault(true)
}
