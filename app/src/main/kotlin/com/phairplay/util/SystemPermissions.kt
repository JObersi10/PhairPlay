package com.phairplay.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.phairplay.service.PhairPlayAccessibilityService
import com.phairplay.service.PhairPlayDeviceAdmin

/**
 * SystemPermissions — grants the two privileged capabilities PhairPlay wants, from the device.
 *
 * Both were documented as adb-only, and for one of them that was simply wrong:
 *
 *  - **Device admin** (blanks the display so HomeKit "off" does something). `ACTION_ADD_DEVICE_ADMIN`
 *    is public AOSP API that opens a system consent screen any app may launch. PhairPlay was telling
 *    users to run `dpm set-active-admin` from a computer for a permission the user could have granted
 *    with one button. That is now [requestDeviceAdmin].
 *
 *  - **Accessibility** (D-pad keys reaching apps other than PhairPlay). This one genuinely cannot be
 *    self-granted: `WRITE_SECURE_SETTINGS` is signature-level, so no app can switch itself on. The
 *    best available is to open the system Accessibility screen so the user toggles it there without
 *    leaving the couch — see [openAccessibilitySettings]. Fire OS does have that screen, but some
 *    builds do not list third-party services in it, so the caller must be prepared for the user to
 *    come back having found nothing, and the adb command stays documented as the fallback.
 *
 * Every entry point reports whether it worked rather than assuming: a missing Settings activity is a
 * normal outcome on Fire OS, not an exception worth crashing over.
 */
object SystemPermissions {

    /** True when the D-pad can reach apps other than PhairPlay. */
    fun isAccessibilityGranted(context: Context): Boolean {
        if (PhairPlayAccessibilityService.isConnected) return true
        // isConnected only becomes true once the service is bound, which lags the setting. Read the
        // secure setting too, so a freshly granted permission is not reported as missing.
        val enabled = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        }.getOrDefault(0)
        if (enabled != 1) return false
        val services = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull() ?: return false
        val target = ComponentName(context, PhairPlayAccessibilityService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(services)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == target) return true
        }
        return false
    }

    /** True when PhairPlay can blank the display (HomeKit "off", sleep). */
    fun isDeviceAdminGranted(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(context, PhairPlayDeviceAdmin::class.java))
    }.getOrDefault(false)

    /**
     * Opens the system consent screen for device admin.
     *
     * @return false if no activity handles the intent, in which case the caller should fall back to
     *   showing [deviceAdminAdbCommand]. Fire OS builds without the Settings screen do exist.
     */
    fun requestDeviceAdmin(context: Context, explanation: String): Boolean {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, PhairPlayDeviceAdmin::class.java),
            )
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent, "device admin")
    }

    /**
     * Gives up device admin.
     *
     * Worth having a button for, because granting it silently blocks uninstall --
     * `adb uninstall` answers DELETE_FAILED_DEVICE_POLICY_MANAGER and says nothing about why. An
     * app that can take a privilege away from the user's control of their own device should be able
     * to hand it back without them needing a computer.
     *
     * An admin may always remove itself, so this needs no user consent screen.
     */
    fun revokeDeviceAdmin(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.removeActiveAdmin(ComponentName(context, PhairPlayDeviceAdmin::class.java))
        Logger.i("Device admin revoked")
        true
    }.getOrElse {
        Logger.w("Could not revoke device admin — ${it.message}")
        false
    }

    /** Opens the system Accessibility screen. @return false if the device has no such screen. */
    /** True when PhairPlay may draw the remote's focus ring over other apps. */
    fun isOverlayGranted(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)

    /**
     * Opens the "Display over other apps" consent screen, pre-filtered to PhairPlay.
     *
     * The package-scoped Uri is what makes this land on our own entry rather than an alphabetical
     * list of every app on the device — on a TV, scrolling that list with a D-pad is the difference
     * between a working instruction and one the user gives up on. Falls back to the unfiltered
     * screen, because some Fire OS builds reject the scoped form.
     */
    fun openOverlaySettings(context: Context): Boolean {
        val scoped = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, scoped, "overlay settings")) return true
        return start(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "overlay settings (unfiltered)",
        )
    }

    fun openAccessibilitySettings(context: Context): Boolean =
        start(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "accessibility settings",
        )

    /**
     * The adb fallback for accessibility, built from the real [ComponentName].
     *
     * Hand-written short forms (`pkg/.Class`) resolve against the MANIFEST package rather than the
     * applicationId, which is how the device-admin hint ended up naming a class that did not exist.
     */
    fun accessibilityAdbCommand(context: Context): String {
        val component = ComponentName(context, PhairPlayAccessibilityService::class.java)
        return "adb shell settings put secure enabled_accessibility_services " +
            "${component.flattenToString()} && " +
            "adb shell settings put secure accessibility_enabled 1"
    }

    /** The adb fallback for device admin, for the rare build with no consent screen. */
    fun deviceAdminAdbCommand(context: Context): String =
        "adb shell dpm set-active-admin " +
            ComponentName(context, PhairPlayDeviceAdmin::class.java).flattenToString()

    private fun start(context: Context, intent: Intent, what: String): Boolean = runCatching {
        context.startActivity(intent)
        Logger.i("Opened $what screen")
        true
    }.getOrElse {
        Logger.w("No $what screen on this device — ${it.message}")
        false
    }
}
