package com.phairplay.util

import java.io.DataOutputStream

/**
 * RootShell — runs `input keyevent` as root, when the device happens to be rooted.
 *
 * WHY THIS EXISTS: the accessibility service moves focus by asking nodes to focus themselves, which
 * is a strictly weaker thing than a key press. Apps that draw their own UI -- the Fire TV launcher's
 * rows, Netflix, most players -- ignore `ACTION_FOCUS` entirely, so the HomeKit remote works in some
 * apps and does nothing in others. The API that fixes this properly, `GLOBAL_ACTION_DPAD_*`, needs
 * API 33; Fire OS is Android 9/11. `INJECT_EVENTS` is signature-level and `pm grant` cannot reach it.
 *
 * `adb shell input keyevent` works only because the shell UID holds INJECT_EVENTS. Root is another
 * UID that holds it. So on a rooted device we can run exactly the command that already works, and
 * the remote drives everything with no exceptions.
 *
 * MOST FIRE TV DEVICES ARE NOT ROOTED, and nothing here tries to change that: [isAvailable] runs
 * `su -c id` once and caches the answer, and every caller treats false as "use the normal path".
 * This is an opportunistic upgrade, not a requirement.
 */
object RootShell {

    @Volatile private var available: Boolean? = null

    /** True if `su` exists and grants root. Probed once; the result is cached for the process. */
    fun isAvailable(): Boolean = available ?: synchronized(this) {
        available ?: probe().also {
            available = it
            Logger.i(if (it) "Root available — remote will inject real key events" else "No root")
        }
    }

    /** Forgets the cached probe, so a user who grants root in Magisk need not restart the app. */
    fun recheck() {
        available = null
    }

    /**
     * Injects [keyCode] through the real input pipeline.
     *
     * @return false if root is unavailable or the command failed, in which case the caller must fall
     *   back — a silent false here would be a remote that looks enabled and does nothing.
     */
    fun sendKeyEvent(keyCode: Int): Boolean {
        if (!isAvailable()) return false
        return run("input keyevent $keyCode")
    }

    private fun probe(): Boolean = run("id")

    /**
     * Runs [command] under `su` and waits for it.
     *
     * Waiting matters: a fire-and-forget `su` leaves a zombie process per key press, and on a device
     * with no `su` at all the failure surfaces as an IOException from exec rather than an exit code,
     * which is why the whole thing sits inside runCatching.
     */
    private fun run(command: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use { out ->
            out.writeBytes("$command\n")
            out.writeBytes("exit\n")
            out.flush()
        }
        process.waitFor() == 0
    }.getOrElse {
        Logger.i("su failed — ${it.message}")
        false
    }
}
