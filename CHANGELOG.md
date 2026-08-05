# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Now Playing backdrop rebuilt: palette-driven animated blobs that react to a bass-onset beat
  detector, with a Beat Pulse strength setting (Calm / Normal / Strong / Insane)
- Beat delay setting — shifts the beat animation without touching audio timing, for Bluetooth
  speakers whose output latency `AudioTrack` cannot see
- Audio delay setting (up to 3000 ms) for A/V trim
- Debug HUD on the audio screen, and a diagnostic server on ports 8001/8002
- OLED-shift screensaver for the Now Playing card

### Changed
- **Back is now one setting instead of two.** `BackAction` — stop the stream, go to the Fire TV home
  screen, or exit PhairPlay — replaces the `backQuitsApp` and `backGoesHome` switches, which could
  both be on at once and described two different questions. Existing preferences are migrated.
- Playback position is derived from the receiver's own audio clock rather than extrapolated from
  the sender's sparse progress pushes, so it no longer drifts seconds out

### Fixed
- Pause is detected correctly. A paused iOS sender keeps transmitting at full rate with its RTP
  clock advancing; the packets are simply empty, which is why every timing-based detector failed
- Back now ends the stream — `endSession` closed the RTSP socket but left the audio and mirror UDP
  servers running
- ALAC decoder crash (SIGSEGV) on malformed frames: Apple's reference bit reader over-reads past the
  buffer, so decoding now happens from a padded copy
- Stream-level TEARDOWN no longer ends the whole session, which had been killing iOS renegotiation
- Audio buffer starvation that caused choppy playback at session start and when pressing Home
- CI: `:test-runner` is back in `settings.gradle.kts` (the JDK 17 toolchain issue that caused it to
  be commented out is fixed with the foojay resolver), ExoPlayer-based classes are stubbed for the
  AGP-free JVM build, and all lint errors are resolved

### Removed
- Google Cast, entirely — port 8009 is held by `com.amazon.cast.sink` on Fire TV and the
  `DeviceAuthMessage` handshake needs a Google-CA-signed certificate chain that cannot be obtained

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->
