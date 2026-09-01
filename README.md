# PhairPlay — an AirPlay receiver for Fire TV and Android TV

Turn a Fire TV or Android TV into an AirPlay 2 receiver. Mirror your iPhone, iPad or Mac to the TV,
or send it music and get a full-screen Now Playing display with artwork. Free, open source, no ads,
no account.

**[⬇ Download the latest APK](https://github.com/JObersi10/PhairPlay/releases/latest)**

```
  iPhone / iPad / Mac                    Fire TV / Android TV
  ┌────────────────┐      AirPlay       ┌──────────────────────┐
  │   Your screen  │  ───────────────►  │  Your TV             │
  └────────────────┘                    └──────────────────────┘
   Control Centre → Screen Mirroring →  PhairPlay
```

## Features

- **Screen mirroring** from iPhone, iPad and macOS
- **AirPlay video** — YouTube, Safari and similar apps stream at full quality rather than as a mirror
- **AirPlay audio** with a Now Playing screen: artwork, title, artist, progress, and an
  audio-reactive backdrop driven by the music itself
- **Control the sender from your TV remote** — play, pause, skip, and hold left/right to scrub
- **DLNA / UPnP** MediaRenderer, and **Miracast** for Android senders
- **Automatic Bluetooth A/V sync** — visuals shift to match a Bluetooth speaker's delay, on their own
- **Cover art lookup** for sources that don't send any (optional, off by default)
- Runs as a background service, so the TV stays discoverable after you leave the app

Not supported: Google Cast (port 8009 is permanently held by Amazon's own receiver, and the
handshake needs a Google-signed certificate that cannot be obtained), and audio from the macOS
**Music** app specifically — see [`docs/FEATURES.md`](docs/FEATURES.md).

## Install

**Requirements:** Fire TV (Fire OS 5.2.6.9+, Android 7.1 / API 25 or newer) or Android TV / Google TV
(Android 10 / API 29 or newer), on the same Wi-Fi network as the sender.

1. Download `PhairPlay-*.apk` from [Releases](https://github.com/JObersi10/PhairPlay/releases/latest).
2. Enable **Developer options → ADB debugging** on the TV, then sideload:
   ```bash
   adb connect <tv-ip>:5555
   adb install -r PhairPlay-firetv.apk
   ```
   Or use Downloader (code `PhairPlay`) / Send Files to TV if you'd rather not use a computer.
3. Open PhairPlay once and follow the setup screen. ADB can go back off afterwards — it is not used
   at runtime.

Fire TV builds and Google TV builds are separate application IDs and can coexist. Installing the
Google TV build on a Fire TV fails with `INSTALL_FAILED_OLDER_SDK`.

Optional permissions (remote control, Miracast) are covered in
[`docs/PERMISSIONS.md`](docs/PERMISSIONS.md).

## Development

Android app, Kotlin, plain `View`/`FrameLayout` (not Compose) for the TV surfaces, with two native
libraries built through CMake/NDK.

```bash
./gradlew :app:assembleFiretvDebug     # or :app:assembleGoogletvDebug
./gradlew :test-runner:test            # protocol tests, plain JVM
```

| Doc | What's in it |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the receivers, service and UI fit together |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Full feature list and known limits |
| [`docs/PROTOCOL_RESOURCES.md`](docs/PROTOCOL_RESOURCES.md) | AirPlay protocol references, including dead ends |
| [`docs/TESTING.md`](docs/TESTING.md) | Test layout and how to run it |
| [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) | Contribution guide |

## Credits

PhairPlay began as a fork of [PhairPlay by mazer666](https://github.com/mazer666/PhairPlay).

The FairPlay decryption in `app/src/main/cpp/playfair/` is
[PlayFair by Esteban Kubata](https://github.com/EstebanKubata/playfair), obtained via
[RPiPlay](https://github.com/FD-/RPiPlay). Apple Lossless decoding uses
[Apple's reference ALAC decoder](https://github.com/macosforge/alac).
[RPiPlay](https://github.com/FD-/RPiPlay), [UxPlay](https://github.com/FDH2/UxPlay),
[shairport-sync](https://github.com/mikebrady/shairport-sync) and
[pyatv](https://github.com/postlund/pyatv) were invaluable references for the protocol work.

Full attribution and license details: **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**.

## License

**GNU General Public License v3.0 or later** — see [`LICENSE`](LICENSE).

PhairPlay links the GPL-3.0-licensed PlayFair library, so the project as a whole is GPL-3.0. Bundled
third-party components keep their own licenses; the Apple ALAC decoder remains Apache-2.0 under its
original copyright notices.

## Disclaimer

AirPlay is a trademark of Apple Inc. PhairPlay is an independent, unofficial implementation built on
publicly documented and community-reverse-engineered behaviour. It is **not affiliated with,
endorsed by, or sponsored by Apple Inc.**, Amazon.com, Inc., or Google LLC.
