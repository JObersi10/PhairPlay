# Third-party notices

PhairPlay is licensed under the **GNU General Public License v3.0 or later** (see [`LICENSE`](LICENSE)).
The components below keep their own licenses and copyright.

---

## Adapted code

Code from these projects is present in this repository and compiled into the app.

### PlayFair — `app/src/main/cpp/playfair/`
- Upstream: <https://github.com/EstebanKubata/playfair>, obtained via [RPiPlay](https://github.com/FD-/RPiPlay) `lib/playfair`
- License: **GPL-3.0**
- Relation: Apple's FairPlay v3 key decryption, compiled verbatim into `libplayfair.so` and called from Kotlin through `app/src/main/cpp/fairplay_jni.c`. **This is the component that makes PhairPlay GPL-3.0.**

### Apple ALAC reference decoder — `app/src/main/cpp/alac/`
- Upstream: <https://github.com/macosforge/alac>
- License: **Apache-2.0** — `Copyright (c) 2011 Apple Inc. All rights reserved.` (headers retained in every file)
- Relation: decodes Apple Lossless audio, compiled into `libalac.so`. `alac_jni.cpp` is PhairPlay's own JNI wrapper; it decodes from a padded copy because the upstream bit reader over-reads.

---

## Runtime dependencies

Linked at build time; no upstream source is copied into this repository.

| Project | License | Used for |
|---|---|---|
| [AndroidX](https://developer.android.com/jetpack/androidx) (core-ktx, appcompat, leanback, constraintlayout, palette, datastore) | Apache-2.0 | UI, TV leanback surface, artwork palette extraction, settings storage |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | Apache-2.0 | Parts of the UI layer |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Apache-2.0 | AirPlay video (HLS/DASH) playback |
| [Kotlin stdlib + kotlinx.coroutines](https://github.com/JetBrains/kotlin) | Apache-2.0 | Language runtime and concurrency |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) | MIT-style [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) | Ed25519, Curve25519 and AES used by pair-setup / pair-verify |
| [dd-plist](https://github.com/3breadt/dd-plist) | MIT | Binary and XML property lists on the RTSP channel |
| [ZXing](https://github.com/zxing/zxing) | Apache-2.0 | On-screen pairing QR code |
| [Timber](https://github.com/JakeWharton/timber) | Apache-2.0 | Logging |
| [JUnit 4](https://junit.org/junit4/) | EPL-1.0 | Tests only — not shipped |
| [MockK](https://mockk.io/) | Apache-2.0 | Tests only — not shipped |
| [Robolectric](https://robolectric.org/) | MIT | Tests only — not shipped |

Service discovery uses Android's own `NsdManager` and a small in-tree mDNS responder. No Bonjour,
jmdns, Avahi, OpenSSL, libplist, curl, ffmpeg/libav or FDK-AAC source is bundled or linked. AAC is
decoded by the platform's `MediaCodec`, so no AAC decoder is distributed with the app.

---

## Inspiration & reference

Consulted while implementing the protocols. **No code from these projects was copied.** Where a file
names one of them, it cites a specific behaviour or constant that was verified independently.

| Project | License | How it was used |
|---|---|---|
| [RPiPlay](https://github.com/FD-/RPiPlay) | GPL-3.0 (LGPL-2.1+ in parts) | Structure of the RAOP/mirroring handlers. Cited in `AudioStreamServer.kt`, `MirrorStreamServer.kt`, `MirrorCrypto.kt`, `PairingSession.kt`, `AirPlayNtpClient.kt`, `InfoResponder.kt`, `FairPlay.kt`. Its `lib/playfair` **is** used as code — see *Adapted code* above. |
| [UxPlay](https://github.com/FDH2/UxPlay) | GPL-3.0 | Referenced alongside RPiPlay for pairing and key-derivation behaviour |
| [shairport-sync](https://github.com/mikebrady/shairport-sync) | Per-file; GPL-2.0-or-later in the relevant files | Source of the `srcvers` observation in `AirPlayVersion.kt` (issue #2014), and of the published legacy AirPort Express RSA key referenced by `RaopRsa.kt` |
| [pyatv](https://github.com/postlund/pyatv) | MIT | DACP remote-control behaviour, and MediaRemote message shapes noted in `MediaRemote.kt` (that path is disabled) |
| [openairplay / AirPlayAuth](https://github.com/funtax/AirPlayAuth) | MIT | Apple's SRP6 variant, used to verify `LegacyPairSetupPin.kt` |

---

## Data & protocol sources

- **Legacy AirPort Express RSA private key** (`RaopRsa.kt`). Apple's own key, published for over a
  decade and shipped by every open-source AirPlay receiver. It is key material rather than
  authored code; the Kotlin around it is PhairPlay's.
- **AirPlay protocol documentation** — Apple's published `AirPlay Documentation.html`, plus the
  community notes listed in [`docs/PROTOCOL_RESOURCES.md`](docs/PROTOCOL_RESOURCES.md).
- **Cover art** — [MusicBrainz](https://musicbrainz.org/) and the
  [Cover Art Archive](https://coverartarchive.org/), queried at runtime and off by default. Data is
  CC0; the recording metadata is licensed by MusicBrainz.

---

## Trademarks

AirPlay, Apple, iPhone, iPad, macOS and Apple TV are trademarks of Apple Inc. Fire TV and Amazon are
trademarks of Amazon.com, Inc. Android and Google TV are trademarks of Google LLC. PhairPlay is not
affiliated with, endorsed by, or sponsored by any of them.
