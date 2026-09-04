# Permissions and ADB setup

PhairPlay streams with no extra permissions. A few optional ones unlock behaviour people ask about
often; this page covers what they buy and how to grant them. Moved here out of the README so the
front page stays about what the app is.

## Permissions (read this first)

PhairPlay streams fine with no extra permissions. A few optional ones unlock behaviour people ask
about constantly, so they are up here rather than buried.

### ADB debugging is only needed for setup

Turn it on to grant the permissions below and to sideload, then it can go back off. It is **not**
used at runtime.

<details>
<summary>Why the app does not drive the remote through adb (tested, does not work)</summary>

`adb shell input keyevent` reaches the real input pipeline, which is exactly what the HomeKit remote
needs to move focus in apps that manage focus themselves. PhairPlay contains a full adb client that
connects to the device's own daemon on `127.0.0.1:5555` to do this.

**Fire OS refuses it.** adbd accepts the TCP connection and then closes it before the handshake
begins — the app never gets to send a byte, let alone offer a key, so no "Allow debugging?" prompt
ever appears. That is an adbd/SELinux policy decision about local app connections, and no amount of
protocol work gets around it. The client is left in place because it costs nothing (one refused
socket per process, then it stops trying) and it would work on a device that permits it.

What remains is the accessibility service, which moves focus by asking nodes to focus themselves.
That works in apps that expose a focus graph and does nothing in apps that draw their own UI.

</details>

### The one command

On Fire TV, **accessibility cannot be enabled from the on-device Settings menu** — Fire OS does not
list third-party accessibility services there. adb is the only route. This grants both permissions:

```bash
adb shell settings put secure enabled_accessibility_services com.phairplay.firetv/com.phairplay.service.PhairPlayAccessibilityService && adb shell settings put secure accessibility_enabled 1 && adb shell dpm set-active-admin com.phairplay.firetv/com.phairplay.service.PhairPlayDeviceAdmin
```

Connect adb first: enable **Settings → My Fire TV → Developer Options → ADB Debugging**, find the IP
under **Settings → My Fire TV → About → Network**, then:

```bash
adb connect 192.168.1.50:5555
```

> **Device admin blocks uninstall.** While it is granted, `adb uninstall` fails with
> `DELETE_FAILED_DEVICE_POLICY_MANAGER`, and `dpm remove-active-admin` will NOT undo it — that only
> accepts test-only admins. Use **Settings → Permissions → Turn the display off → Revoke** in the
> app. Note you rarely need to uninstall anyway: `adb install -r` replaces in place.

### What each one buys

| Permission | Without it | With it |
|---|---|---|
| **Accessibility** | The HomeKit/iPhone remote only works while PhairPlay is on screen | Back and Home work system-wide |
| **Device admin** | HomeKit "off" ends the session but leaves the TV awake | HomeKit "off" blanks the display |

> **Fire TV caveat on the remote:** even with accessibility granted, **arrow keys will not navigate
> other apps on Fire TV.** `GLOBAL_ACTION_DPAD_*` requires Android 13 (API 33) and Fire OS is built
> on Android 9/11. Below that, the only tool is accessibility node manipulation, which apps drawing
> custom focus (Netflix, the Fire TV launcher) ignore. Back and Home use `GLOBAL_ACTION_BACK`/`HOME`,
> which have existed since API 16, so those do work. Injecting real D-pad events needs
> `INJECT_EVENTS`, a signature-level permission no sideloaded app can hold.

### Turning them off

Accessibility:

```bash
adb shell settings put secure enabled_accessibility_services ""
```

Device admin: **Settings → Permissions → Turn the display off → Revoke** in the app.

---
