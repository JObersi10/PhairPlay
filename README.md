# PhairPlay

> Based on [PhairPlay by mazer666](https://github.com/mazer666/PhairPlay) — all credit for the original AirPlay receiver goes to them. This fork adds synced lyrics, a progress bar, diagnostic server, and UI improvements.

PhairPlay is a free, open-source, ad-free AirPlay 2 receiver for Android TV and Fire TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio directly to your TV — no Apple TV required.

```
 macOS (Monterey+)            Android TV / Fire TV
 iOS / iPadOS (16+)           ┌──────────────────────┐
 ┌────────────────┐  AirPlay  │                      │
 │  [Your Screen] │ ────────► │  [Your TV Screen]    │
 │                │           │                      │
 └────────────────┘           └──────────────────────┘
      Click AirPlay →              PhairPlay
      Select your TV →             (this app)
      Done. ✓
```

---

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

## What works, what doesn't

### ✅ Works

| Feature | Notes |
|---|---|
| **Screen mirroring** (iPhone, iPad, Mac) | H.264 over AirPlay 2. A/V sync ~100 ms. |
| **AirPlay audio** from iOS / iPadOS | ALAC realtime + AirPlay 2 buffered. |
| **FairPlay v3** | Key decryption via `libplayfair`. This is the mirroring path. |
| **HomeKit** pairing and control | Non-transient pairing: SRP, Curve25519, ChaCha20-Poly1305. Power, volume, mute, inputs, remote keys. |
| **HomeKit inputs → app shortcuts** | Map inputs to any installed app and switch from the Home app. |
| **Now Playing** | Metadata, artwork, synced lyrics, progress bar. |
| **DACP + MediaRemote** reverse remote | Transport control back to the sender. |
| **DLNA / UPnP** MediaRenderer | Including GENA eventing. |
| **RSA (`rsaaeskey`) audio** | The legacy ANNOUNCE/SDP path. |
| **mDNS service publication** | `_airplay._tcp` and `_raop._tcp`. |
| **RTCP sender reports** | Sender-clock skew and loss, reported as diagnostics. |

### 🚧 Working on it

| Feature | State |
|---|---|
| **Miracast** | Google TV only, and RTSP control plane only — MPEG-TS decode is not done. Compiled out of Fire TV builds. |
| **PTP** | Now listening on 319/320 and feeding `PtpClock`. Runs alongside NTP, which stays the fallback. Not yet validated against a real grandmaster. |
| **Multi-room / output latency compensation** | `MultiRoomGroup` exists; not validated against a second receiver. |
| **`AudioPlayer` (legacy RAOP)** | Has no jitter buffer, unlike the AirPlay 2 path. |
| **Settings screen** | Outgrown a hand-written layout; being moved to a recycling list. |

### ❌ Doesn't work

| Feature | Why |
|---|---|
| **macOS Music.app audio (FairPlay v2)** | **Not fixable.** See below. |
| **Opus** | Not implemented. ALAC, AAC-LC, AAC-ELD and PCM are. |
| **HomeKit transient pairing** (bit 48) | Not implemented; only non-transient. A controller that asks for it is now logged, so it is at least visible whether anything wants it. |
| **RFC 2198 RTP redundancy** | Duplicate suppression for macOS's `redundantAudio` exists, but true RED payload parsing does not. |
| **HDMI-CEC volume** | `HdmiControlManager` is `@SystemApi` — unavailable to normal apps. |
| **System-wide key injection without accessibility** | `INJECT_EVENTS` is signature-level. |

---

### Why macOS Music.app doesn't work

Streaming from **Music.app on a Mac** connects, shows metadata, and responds to transport
controls — but the audio itself is garbage. This is a hard wall, not a bug awaiting a fix.

Music.app negotiates FairPlay **v2** and requests modes **0** and **1**. FairPlay v2 phase 1 answers
each mode with a different 142-byte constant, and only the **mode 2** table has ever been published.
PhairPlay answers every mode with it, so the handshake completes and the sender hands over a wrapped
key that unwraps to a plausible-looking 16 bytes that is simply wrong — hence ~5 of 24 ALAC frames
decoding before the stream is muted.

The missing tables are not recoverable from source:

- **shairport-sync** carries four tables, but they are FairPlay **v3** — byte-for-byte identical to
  the four PhairPlay already has. It rejects v2 outright.
- **joerg-krause/shairplay @ fairplay_v2** does exactly what PhairPlay does: one mode-2 capture with
  the mode byte patched.
- **Requiem** and similar tools decrypt FairPlay-protected *files* using `CoreFP`. That is
  file-at-rest DRM, unrelated to the `/fp-setup` streaming handshake.

The obvious workaround also fails. Advertising `et=0,1` (RSA only) to push Music.app onto the RSA
path — which PhairPlay implements correctly — does not make it fall back; it reads the receiver as
unusable and hangs up after `OPTIONS`, before `ANNOUNCE`. Advertising FairPlay is also what screen
mirroring depends on, so dropping it would trade a working feature for a broken one.

Recovering v2 modes 0/1/3 requires capturing them from real Apple hardware. They will not be
invented here.

**Everything else on a Mac works** — mirroring, and system-audio AirPlay from Control Center. It is
specifically Music.app's own AirPlay output that is affected.

---

## Current Status — v1.0.0-beta.1

PhairPlay is available as a signed beta release. Download the APK directly from the [GitHub Releases page](https://github.com/JObersi10/PhairPlay/releases). CI publishes a rolling `latest-debug` release with both flavour APKs on every push to `main`.

Implemented end-to-end: mDNS advertising, RTSP handshake, HomeKit-style pairing, FairPlay v3 key decryption, H.264 mirroring, AAC-ELD/AAC-LC/ALAC audio, NTP A/V sync, and DACP reverse remote. Real-device validation with macOS and iOS senders is the current focus.

DLNA/UPnP MediaRenderer works, including GENA eventing. Google Cast has been **removed** — see below.

Miracast is now **Google TV only**. It advertises and implements its RTSP control plane there, but
MPEG-TS media decode is still not done. It is compiled out of Fire TV builds entirely: Fire OS keeps
Wi-Fi Direct behind Amazon's own display stack, so a sender would find the receiver and then time
out — and offering it cost a runtime location prompt on Android 12 and below for a feature that was
never going to connect.

## Features

### AirPlay 2 (fully implemented)
- Screen mirroring from macOS 12+ and iOS/iPadOS 16+ — H.264 hardware decode
- FairPlay session decryption (fp-setup v2/v3 + legacy rsaaeskey) via native libplayfair
- HomeKit-style pairing (Ed25519/X25519) and legacy SRP PIN pairing
- Mirroring audio: AAC-ELD, AAC-LC, ALAC — with independent A/V start/stop
- System audio streaming (ALAC, unencrypted) — reliable path for app audio
- AirPlay video URL mode (`/play` content) + transport controls (play/pause/scrub) — the TV fetches
  and plays the stream itself, so AirPlaying from YouTube or Safari arrives at source quality
  instead of as a re-encode of the phone's screen
- Now-playing metadata (DMAP) with album artwork overlay
- DACP reverse remote — TV remote controls the sender's playback
- NTP timing and UDP audio retransmit (packet-loss recovery)
- AirPlay photo receiver — JPEG/PNG from iOS Photos app displayed full-screen
- Access-control lockout after repeated failed pairing attempts

### App & Platform
- Android TV / Fire TV app shell with foreground service and status UI
- Mirror audio toggle and PIN-auth toggle in Settings
- Works on Google TV (Android 10+) and Fire TV (Android 7+)
- DLNA/UPnP MediaRenderer (AVTransport, RenderingControl, ConnectionManager) with GENA eventing
- Miracast Wi-Fi Direct / WFD advertisement and RTSP control-plane (Google TV builds only)
- Zero ads, zero analytics, zero internet required
- Open source — Apache 2.0 license

## What PhairPlay Does NOT Do

- **FairPlay DRM content** (Netflix, Disney+, Apple TV+) — Apple DRM; not decryptable by any open-source receiver
- **macOS Music app audio (FairPlay v2)** — the Music app FairPlay-encrypts everything it sends over
  AirPlay, including local library files, and our FairPlay **v2** key derivation produces a stream key
  that does not decrypt: every ALAC frame fails and the decode-health guard mutes the output rather
  than emitting static. This is *not* an Apple Music DRM limitation — a local file fails identically.
  The FairPlay **v3** path used by screen mirroring and Safari is correct. Workaround: set the Mac's
  system audio output to the TV instead of using Music's own AirPlay picker (unencrypted ALAC, works)
- **Buffered audio playback** (AirPlay 2 type 103) — accepted but not played back yet
- **Cloud/remote streaming** — local network only
- **Miracast media playback** — control plane is ready; MPEG-TS decode is not implemented
- **Miracast on Fire TV** — compiled out; Fire OS does not expose a Wi-Fi Direct stack that can
  complete a WFD session
- **Google Cast** — removed, and not coming back. Port 8009 is permanently held by
  `com.amazon.cast.sink` on Fire TV, and a receiver must answer Google's `DeviceAuthMessage` with a
  certificate chain signed by a Google CA that cannot be obtained for an open-source project.

---

## Requirements

**On your TV:**
- Google TV (Android 10+) or Amazon Fire TV (Android 7+)
- Connected to the same Wi-Fi network as your Mac
- Sideloading enabled (for Fire TV) or ADB enabled (for Google TV)

**On your Mac:**
- macOS 12 (Monterey) or later
- Connected to the same Wi-Fi network as your TV

**Network:**
- Both devices on the same subnet (common home router setup works)
- Multicast/mDNS must not be blocked (most home routers are fine)
- 5 GHz Wi-Fi or Ethernet strongly recommended for best performance

---

## Installation

### Option A: Download a Release APK (easiest)

Go to the [Releases page](https://github.com/JObersi10/PhairPlay/releases) and download the APK for your device:

| APK | Device |
|-----|--------|
| `PhairPlay-vX.Y.Z-googletv.apk` | Google TV, Android TV (Android 10+) |
| `PhairPlay-vX.Y.Z-firetv.apk` | Amazon Fire TV (Android 7.1+) |

Then install it via ADB (see the Sideloading Guide below) or a sideloading app like *Downloader* on Fire TV.

### Option B: Build from Source

1. **Install prerequisites**
   ```bash
   # Install Android Studio from https://developer.android.com/studio
   # Install JDK 17 or later
   ```

2. **Clone the repository**
   ```bash
   git clone https://github.com/JObersi10/PhairPlay.git
   cd PhairPlay
   ```

3. **Build the APK**
   ```bash
   # For Google TV:
   ./gradlew assembleGoogletvDebug

   # For Fire TV:
   ./gradlew assembleFiretvDebug
   ```
   The APK will be in `app/build/outputs/apk/`.

   To run the same local checks used by CI before testing on a TV:
   ```bash
   ./gradlew :test-runner:test
   ./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
     :app:assembleGoogletvDebug :app:assembleFiretvDebug
   ```

4. **Install via ADB**
   ```bash
   # Enable ADB on your TV first (see below)
   adb connect <TV-IP-ADDRESS>

   # Google TV:
   adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

   # Fire TV:
   adb install app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
   ```

---

## Sideloading Guide

### Google TV (e.g., Chromecast with Google TV)

1. Go to **Settings → System → About → Android TV OS build** and click it 7 times to enable Developer Options.
2. Go to **Settings → System → Developer Options** and enable **USB debugging**.
3. Note your TV's IP address from **Settings → Network & Internet**.
4. On your Mac/PC, run:
   ```bash
   adb connect <TV-IP>
   adb install app-googletv-debug.apk
   ```
5. Launch PhairPlay from your app list.

### Fire TV (Fire TV Stick, Fire TV Cube, etc.)

1. Go to **Settings → My Fire TV → About** and click **Build** 7 times to enable Developer Options.
2. Go to **Settings → My Fire TV → Developer Options** and enable:
   - **ADB debugging** → ON
   - **Apps from Unknown Sources** → ON
3. Note your Fire TV's IP address from **Settings → My Fire TV → About → Network**.
4. On your Mac/PC, run:
   ```bash
   adb connect <FireTV-IP>
   adb install app-firetv-debug.apk
   ```
5. Launch PhairPlay from **Apps → Your Apps & Games**.

## How to Use

1. Launch PhairPlay on your TV. You will see the Waiting Screen with your TV's name.
2. On your Mac, click the **AirPlay** icon in the menu bar (or go to **System Preferences → Displays → AirPlay Display**).
3. Select your TV from the list (it should appear as your TV's name).
4. Your Mac's screen will appear on the TV instantly.
5. To stop: click the AirPlay icon on your Mac and select "Turn Off AirPlay Mirroring", or just quit PhairPlay on the TV.

---

## Known Limitations

- **Beta software** — the AirPlay 2 stack is complete but real-device validation with various macOS/iOS senders is ongoing. Please report issues.
- **The macOS Music app's own AirPlay is silent.** Music FairPlay-encrypts everything it sends, local files included, and our FairPlay v2 key derivation is wrong — so the audio decodes to nothing and is muted deliberately. Route the Mac's **system audio output** to the TV instead (works fine). Not a DRM limitation; a real bug on our side.
- **FairPlay-protected video** (Netflix, Disney+, Apple TV+) cannot be mirrored — this is Apple's DRM, not a PhairPlay limitation.
- **Buffered audio (AirPlay 2 type 103)** is accepted but not yet played back.
- **Miracast** — Google TV builds only, and even there the RTSP control plane works but MPEG-TS media decode is future work. Fire TV builds omit it.
- If your router has **AP isolation** or **multicast filtering** enabled, PhairPlay may not appear in the AirPlay menu. Disable these settings on your router.
- On very busy 2.4 GHz Wi-Fi networks, you may experience latency above 100 ms. Use 5 GHz or Ethernet for best results.
- **PIN auth is optional.** When disabled (default), any device on the same network can mirror to the TV. Enable PIN auth in Settings if you're on a shared network.

For real-device failures, run `tools/collect-device-logs.sh` before restarting the app. It captures package state, memory, CPU, and filtered PhairPlay logs into `device-test-logs/`.

---

## Contributing

Contributions are welcome! Please read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) before submitting a pull request.

Key points:
- Follow the coding rules in CONTRIBUTING.md (file size ≤400 lines soft / ≤550 lines hard max, class comments, test coverage)
- All PRs require passing CI (build + tests + lint)
- Discuss major changes in a GitHub Issue first

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) — Community-maintained AirPlay protocol documentation
- [UxPlay](https://github.com/FDH2/UxPlay) — Open-source AirPlay mirror server (reference implementation)
- [RPiPlay](https://github.com/FD-/RPiPlay) — AirPlay mirroring for Raspberry Pi (reference implementation)
