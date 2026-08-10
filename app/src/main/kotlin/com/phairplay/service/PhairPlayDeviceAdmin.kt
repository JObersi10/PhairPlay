package com.phairplay.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.phairplay.util.Logger

/**
 * PhairPlayDeviceAdmin — exists solely so HomeKit's "off" can blank the display.
 *
 * `lockNow()` is the only way a normal app can put a Fire TV's screen to sleep, and it requires an
 * active device-admin component. Declaring one is unusual for a media app, so the policy file it
 * points at requests NOTHING except FORCE_LOCK: no wipe, no password policy, no camera control.
 * Anything more would show up in the system's scary "this app can erase all data" consent screen
 * and would be asking for far more than turning a TV off warrants.
 *
 * Entirely optional. If the user never activates it, [HomeKitBridge.setActive] just ends the
 * session and says so.
 */
class PhairPlayDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Logger.i("Device admin enabled — HomeKit 'off' can now blank the display")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Logger.i("Device admin disabled — HomeKit 'off' will only end the session")
    }
}
