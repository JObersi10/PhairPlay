# PhairPlay — Claude Context

Fire TV receiver for AirPlay 2, Miracast and DLNA. Views are plain Android
`View`/`FrameLayout` subclasses, not Compose.

## Build

Build **from the checkout you are editing** — `~/Documents/Claude/Projects/Fire AirPlay`:

```bash
cd ~/Documents/Claude/Projects/"Fire AirPlay" && \
JAVA_HOME="/Volumes/SABRENT/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Volumes/SABRENT/Applications/AndroidSDK" \
./gradlew :app:assembleFiretvDebug
```

**`/Volumes/SABRENT/PhairPlay` is a stale fork, not the live tree.** This file used to
say to build there. It is a separate git repo whose last commit is from early August;
the project moved to the MacBook and that copy was left behind. Building it succeeds,
reports `BUILD SUCCESSFUL`, and produces an APK without any of your changes in it —
there is no error anywhere to notice. Only the toolchain still lives on SABRENT.

There is **no system JDK and no system Android SDK** — both live on the SABRENT
drive, along with `adb` (`$ANDROID_HOME/platform-tools/adb`). Without those two
environment variables the wrapper fails with "JAVA_HOME is set to an invalid
directory".

**`firetv` for the Fire TV** (minSdk 25). Use `googletv` (minSdk 29) *only* for a
phone or tablet — installing it on the Fire TV fails with `INSTALL_FAILED_OLDER_SDK`,
and the reverse leaves a TV build on a tablet. They are separate application IDs
(`com.phairplay.firetv` / `com.phairplay.googletv`) and can coexist.

Never pipe a Gradle run through `tail`: it buffers everything until exit, so a
build that is working looks identically dead to one that is hung. Redirect to a
file instead.

APK: `/Volumes/SABRENT/phairplay-build/app/outputs/apk/firetv/debug/app-firetv-debug.apk`

Build output is redirected off the internal disk by `layout.buildDirectory` in the root
`build.gradle.kts`, so there is **no `app/build/outputs`** in the tree and the in-repo
path that used to be documented here simply does not exist.

### ⚠️ Verify the native library before every install

The repo lives on an **exFAT** volume mounted through macOS `fskit`, which
intermittently commits a file's size but not its contents. A freshly linked
`libalac.so` reads back as the right number of zero bytes, Gradle copies the
zeros into the APK without complaint, and the app dies on connect with
`UnsatisfiedLinkError: has bad ELF magic: 00000000`. Happened twice in one evening.

exFAT has no journal, so nothing detects the mismatch. Only native rebuilds are
affected — Kotlin-only changes never invoke the linker.

```bash
unzip -p /Volumes/SABRENT/phairplay-build/app/outputs/apk/firetv/debug/app-firetv-debug.apk \
  lib/armeabi-v7a/libalac.so | head -c 4 | xxd -p   # must be 7f454c46
```

If it isn't, delete `app/build/intermediates/{cxx,merged_native_libs/firetvDebug,stripped_native_libs/firetvDebug}`
and `app/build/outputs/apk/firetv`, then build again. One retry has always been enough.

The user has decided to keep the project on the external drive, so this check is
permanent, not a workaround to remove later.

## Device

- Fire TV at `192.168.0.11:5555` over ADB, API 30, **32-bit (`armeabi-v7a`)**
- App ID `com.phairplay.firetv`
- The SABRENT drive must be mounted first
- Install with `adb install -r`. **Do not** `am start` afterwards — that is what
  made the app appear to "auto-open on install"
- **Do not `pm clear`** — it wipes `onboardingComplete` and replays the setup flow
- After any `pm clear`: `adb shell appops set com.phairplay.firetv SYSTEM_ALERT_WINDOW allow`

## Diagnostics

`curl -s http://192.168.0.11:8001/` — full dump. `:8002` — streaming tail.

The buffer only holds events since the app last started, and it is small. To
capture something, reproduce **first**, then curl. An empty `grep` usually means
the event never happened, not that the server is broken.

## Architecture

- `PhairPlayService` — foreground service; deliberately survives `onDestroy` so
  the receiver keeps advertising after the app is swiped away. Holds the wake lock
  and Wi-Fi lock, watches for IP changes via `ConnectivityManager`
- `AirPlayReceiver` → `RtspHandler` — RTSP on 7000; pair-verify, fp-setup,
  SETUP/RECORD/TEARDOWN, `SET_PARAMETER` for metadata, artwork, volume, progress
- `MirrorStreamServer` — mirror data channel; AES-CTR, H.264 payloads → `VideoDecoder`
- `AudioStreamServer` — RAOP audio; AES-CBC per packet, ALAC via `libalac.so` or AAC via `MediaCodec`
- `AlacDecoder` + `cpp/alac_jni.cpp` — Apple's reference ALAC decoder
- `DlnaServer` — SSDP multicast + UPnP HTTP on 8200; AVTransport / RenderingControl / ConnectionManager
- `MiracastReceiver` — Wi-Fi Direct + WFD RTSP on 7236
- `NowPlayingScreen` — audio card: artwork crossfade, marquee text, MENU info
  panel, idle screensaver, debug HUD. Also used by DLNA playback
- `DeviceVolumeController` — maps AirPlay dB to an Android stream volume
- `AudioRouteMonitor` — which output is playing (HDMI / speakers / a named Bluetooth sink); the
  service swaps the remembered A/V trim to match. Bluetooth link latency is **not** measurable —
  `getTimestamp()` stops at the HAL — so a first-seen BT speaker is seeded at 350ms of visual delay
- `DiagnosticServer` — `:8001` dump, `:8002` tail

`docs/FEATURES.md` is the current feature list. `docs/UPDATE_CHECKER.md` covers the in-app
GitHub-Releases updater — read it before touching that code, and before publishing a release:
three of its four bugs were silent, and one printed a log line that lied. Projector mode, the band/tempo
analysis and the Now Playing card are written up for
reuse in `docs/PROJECTOR_MODE.md` — a from-scratch porting guide, including the
constants that only look arbitrary until you have shipped the wrong one.

Google Cast was removed entirely. Port 8009 is permanently held by
`com.amazon.cast.sink`, and a receiver must answer `DeviceAuthMessage` with a
Google-CA-signed certificate chain that cannot be obtained. Don't reintroduce it.

## Protocols

| Protocol | Port | Status |
|----------|------|--------|
| AirPlay RTSP + audio | 7000 | Working — ALAC and AAC |
| AirPlay screen mirror | — | Working (iPhone + macOS) — see "cold first connect" below |
| Miracast | 7236 | Advertising; needs `ACCESS_FINE_LOCATION` granted at runtime on API < 33 |
| DLNA/UPnP MediaRenderer | 8200 | Working, including GENA eventing |

## Open bugs

### Cold first connect shows nothing (mirroring)

Mirroring works, but the **first** attempt after the app has been cold-started
often stays black until the user disconnects and reconnects. Chain:

1. A `SurfaceView` has no `Surface` until it is visible.
2. The overlay is made visible in response to `CONNECTED`.
3. By then the sender has already sent the single IDR it will emit for the next
   several seconds, so `MirrorStreamServer` parks on `awaitingKeyframe`.
4. The reconnect works because the Activity is warm and the Surface already exists.

Two attempts so far. Blocking the mirror `SETUP` reply until the Surface appeared
**did not work and was reverted** — the Surface cannot exist at that point by
construction, so the wait always ran its full timeout and added that delay to the
sender's round trip (`Connect timing: RECORD +3380ms`). The current attempt is
`PhairPlayService.setSurfacePreparer` → `MainActivity.prepareVideoSurface()`,
called from `onSenderApproaching` when the control socket opens, which puts the
black SurfaceView up before anyone knows the session type. **Unverified.**

If that is still not early enough, the next honest option is asking the sender for
a keyframe rather than racing it.

### The event channel is encrypted and we treat it as plaintext

`AirPlay Documentation.html` (project folder, not the repo) is explicit: after SETUP the sender
connects to the event port and **enables encryption**. Keys come from the pair-verify secret —
salt `Events-Salt`, info `Events-Write-Encryption-Key` (output) and `Events-Read-Encryption-Key`
(input), with the two reversed on the sender side. The channel is logically *receiver → sender*
even though the sender opens the socket.

Our handler reads raw bytes off that socket and replies in cleartext RTSP, so it has never parsed
anything real. The doc also says the receiver is expected to `POST /command` with a
`updateInfo` plist over this channel once RECORD completes, which we never send.

### Remote play/pause/skip — SOLVED, and it was the advertised version all along

**Fixed 2026-08-18.** Working on iOS 26.1:

```
DACP configured: id=40BDA1669DF38307 — discovering iTunes_Ctrl_40BDA1669DF38307
DACP resolved: 192.168.0.6:54394
GET /info granted remote authority: DACP-ID=40BDA1669DF38307 (srcvers 350.0)
DACP playpause → HTTP 200
```

The mechanism was never exotic. Remote control **is** DACP, exactly as pyatv, UxPlay,
openairplay and every other reference says, and it requires the sender to send `DACP-ID`
+ `Active-Remote` so the receiver can find the sender's `_dacp._tcp` service. Modern iOS
withholds both unless the receiver advertises an OLD ENOUGH `srcvers` — the
shairport-sync #2014 workaround.

That workaround had been applied to `_raop._tcp` (350.0) and appeared not to work.
**It was only half-applied**: `_airplay._tcp` was still advertising `377.40.00`, a current
Apple TV. The sender reads both services, saw the modern generation on one of them, and
withheld remote authority. Aligning both to `DACP_CAPABLE` in `AirPlayVersion.kt` is the
entire fix.

The confusing part was that our own log line prints the srcvers we ADVERTISE, so
`WITHOUT DACP-ID — sender withheld remote authority (srcvers 350.0)` read as "the
workaround is applied and ineffective" when it in fact only described one of two records.

**Cost:** `ADVERTISED_AIRPLAY` is deliberately a lie about our generation, so any AirPlay 2
feature a sender gates on a newer version is off the table. Mirroring and audio are
unaffected in testing. One-line revert to `APPLE_TV_CURRENT` if something turns up.

**The MediaRemote/MRP work was a dead end and is still disabled** (`MRP_SEND_ENABLED =
false` in `AirPlayReceiver`). Its two payload key names were invented and appear in no
public implementation. Do not revive it — DACP is the answer and it works. External
references, and which are already-checked dead ends, are in `docs/PROTOCOL_RESOURCES.md`.

### macOS audio backlog churn

`Audio: backlog resync — dropped N frames` fires ~12 times in 3 seconds on Mac
system audio. The Mac delivers faster than realtime and the queue is trimmed
repeatedly. Audible as the "laggy and weird" playback.

### FairPlay v2 (RAOP audio) key derivation

`ALACDecoder.Decode failed: -50`, "decoded only 4/24 frames" — silence from the
macOS Music app. v3 (mirroring/Safari) is fine. Separate RE job.

## Hard-won details

- **Ending a session must make the receiver DISAPPEAR, not just pause the sender.**
  Back used to send DACP pause and close the RTSP socket. That stops the audio and
  leaves the phone SELECTED on this output, paused — the log reads as a clean teardown
  (RTSP closed, media released, mDNS re-advertised) while the iPad still shows itself
  connected. DACP is a transport protocol; it has no "deselect output". What makes iOS
  let go is the service going away, so `onStreamingStopped` withdraws mDNS and holds it
  withdrawn for `KICK_WINDOW_MS` on a user-initiated end.
  **The first attempt got the order backwards** — it delayed and *then* called
  `restart()`, but the withdrawal happens inside `restart()`, so the receiver stayed
  advertised for the whole hold and vanished only for the ~650ms the restart itself
  takes. `Holding mDNS withdrawn for 3992ms` at 01:50:57 and `Stopping mDNS
  advertising` at 01:51:01 in the same log is what that looks like.
- **Store suffixes break MusicBrainz lookups.** Apple reports singles as
  `"Bluewave - Single"`, which is how the *store* lists it, not how MusicBrainz
  catalogues the release — so the fielded query matched nothing. In the log that is
  indistinguishable from the album not being in the database, and it is why the cover
  lookup appeared to work "about a quarter of the time": it failed on exactly the
  singles and EPs. `CoverArtFinder.cleanRelease` strips them, and the search retries
  without the artist term before giving up.


- **`setText(resId)` does not format.** `protocol_detail_connected` is
  `"Streaming from %1$s"`; passed to `setText(resId)` the card literally displayed
  the placeholder. Use `getString(resId, arg)`. Anything with a `%` needs an arg
  and a no-arg fallback for when the name is not known yet.
- **iOS needs `pk` in the mDNS TXT record.** Without the Ed25519 public key,
  iPhones and iPads leave the receiver out of the AirPlay picker entirely while
  macOS connects happily — which reads as a network problem and is not one.
  Verify what is actually on the wire with `dns-sd -Z _airplay._tcp`, and force-stop
  the app first: an `adb install -r` restarts the process but the old record can
  still be cached.
- **A bodyless 200 is not the same as an empty plist.** Answering
  `POST /command` with `PlistCodec.encode(emptyMap())` made iOS abandon mirroring
  silently after `RECORD`; replying with no body at all, the way an Apple TV does,
  is what finally made the `streams` SETUP arrive.
- **`leanback` marked `required="true"` filters the app off every non-TV device**,
  even after a successful `adb install`. It is declared `required="false"` with both
  `LEANBACK_LAUNCHER` and `LAUNCHER` categories so tablets get an icon too.
- **A wedged Gradle daemon looks exactly like a slow build.** One sat at 230–312%
  CPU for three hours and silently blocked every later invocation; `pkill -f
  GradleDaemon` did not take, `kill -9 <pid>` did. Check `ps aux | grep GradleDaemon`
  and its accumulated CPU time before believing a build is merely slow.

- **Pause = empty packets, not absent ones.** A paused iOS sender keeps transmitting at the full
  ~128 packets/sec with its RTP clock still advancing in real time; the packets are just 44 bytes
  of header with no payload (~5.6 KB/s paused vs ~120 KB/s playing). Four detectors failed before
  this was measured — packet arrival, RTP-clock advance, decoded-PCM silence, and RTSP `FLUSH` —
  because a paused stream is byte-for-byte indistinguishable from playback on every axis *except*
  payload size. `handleRtpPacket` now counts consecutive sub-`KEEPALIVE_MAX_BYTES` packets.
  Note FLUSH in particular: the spec calls it "flush the receiver's buffer and pause/stop what is
  playing", but iOS sends it right after RECORD at the start of every stream and again on seek.
  The device disagrees with the spec; trust the device.
- **Position comes from the audio clock, not wall time.** Senders push progress only every few
  seconds, so the UI used to extrapolate from the last push and drift a couple of seconds. The
  progress push carries the track's first/last sample as RTP timestamps, and
  `AudioStreamServer.playingRtpTimestamp()` reports the RTP timestamp currently reaching the
  speakers (newest arrival minus the queue minus AudioTrack's own buffer). Position is the
  difference. It cannot drift, and it stops by itself during a pause.
- **`endSession` must release the media servers, not just the RTSP socket.** The RTSP control
  connection, the audio UDP socket and the mirror socket are independent. Closing the first leaves
  the other two receiving and playing, so Back appeared to do nothing.
- **Back is one ordered choice, not two switches.** `BackAction` (STOP_STREAM / GO_HOME /
  EXIT_APP) replaced `backQuitsApp` + `backGoesHome`, which could both be on and described two
  different questions. `SettingsRepository` migrates the old keys on first read.

- **Sender name.** At `CONNECTED` the only name available is the RTSP User-Agent
  fallback ("AirPlay"). The real name arrives ~40 ms later in the now-playing
  plist, so `rememberSender` is called again from `onNowPlayingChanged`, and a
  generic name can't downgrade a real one mid-session. iOS 26 genuinely reports
  `name=Unknown iPhone` in its SETUP plist — that is the phone's own string, not
  our placeholder.
- **Stream-level TEARDOWN must not end the session.** iOS renegotiates by removing
  a stream and adding a replacement on the same session
  (`supportsDynamicStreamID=true`, `POST /audioMode` immediately before). Treating
  an emptied stream list as "session over" killed sessions half a second after
  audio started. Only a bodyless TEARDOWN, or the socket closing, ends a session —
  the socket-close path already does full cleanup.
- **ALAC over-reads.** Apple's `ag_dec.c` bit reader looks ahead past the current
  position with no bounds check. `alac_jni.cpp` decodes from a padded copy so a
  corrupt frame over-reads into zeroed slack instead of SIGSEGV-ing the process.
- **`UnsatisfiedLinkError` is an `Error`, not an `Exception`** — `catch (e: Exception)`
  will not catch it. Decoder construction uses `runCatching`.
- **Device volume can't be trusted to report failure.** `setStreamVolume` returns
  normally even when Fire OS drops the change. `DeviceVolumeController` reads the
  index back and only claims success if it moved. `MODIFY_AUDIO_SETTINGS` is
  required and was still not sufficient, so the default is software gain (`OFF`).
- **`TransitionDrawable.startTransition(0)`** divides by a zero duration and
  `min(NaN, 1f)` stays NaN, so alpha evaluates to 0 and the layer draws fully
  transparent. Set the drawable directly when there is no previous one.
- **Trust the decoder's reported size.** An aspect-ratio sanity check on
  `publishOutputSize` broke portrait iPhone mirroring — a portrait mirror really is
  ~886×1920. Reverted; don't re-add it.
- **Idempotent starters.** `MainActivity.onCreate` calls `ServiceController.start()`
  on every launch, so every receiver checks whether it is already running. Without
  that, DLNA rebound 8200, threw `EADDRINUSE`, logged success anyway, and orphaned
  the working server.
- **GENA needs a unique SID per subscription** plus an initial NOTIFY, sent over a
  raw socket (`HttpURLConnection` rejects the NOTIFY method).
- **Shutdown noise.** Socket loops must check a `stopping`/`started` flag *before*
  logging, and the flag must be set before `close()` — `close()` unblocks
  `receive()`/`accept()` immediately, so the catch block can run first.
- **`SET_PARAMETER`**: the volume check must come before the `text/parameters` check.
- `DiagnosticServer` needs `stop()` before `start()` or it won't restart.
- **Onboarding must not re-render on every keypress.** `updateDraft` originally
  called a full `render()`, rebuilding ~10 rows and stealing focus — that was both
  the lag and the invisible highlight. Repaint rows in place, and use a
  `StateListDrawable` with `state_focused` so TV focus is visible.

## CI

Three GitHub Actions jobs, all of which must stay green: `:test-runner:test` (JVM protocol tests),
plus lint and a debug APK for each of the `firetv` and `googletv` flavors.

- **`:test-runner` compiles `app/src/main` on a plain JVM**, without AGP, so anything in app code
  that touches an AAR-only dependency (androidx.media3/ExoPlayer) or a real Android class breaks
  it. The fix is a stub in `test-runner/src/stubs/` plus an `exclude` entry in
  `test-runner/build.gradle.kts` — see `SharedMediaPlayer`, `AirPlayVideoPlayer`, `VideoDecoder`.
  A stub must keep the real class's public surface, or the protocol tests stop compiling.
- **`lint { warningsAsErrors = true }`**, so any new warning fails CI. Prefer fixing over adding to
  the `disable` set; suppress only where the check genuinely does not apply, and say why.
- **The `foojay-resolver-convention` plugin in `settings.gradle.kts` is load-bearing.**
  `:test-runner` pins `jvmToolchain(17)`, and Gradle configures every module on every build, so on
  a machine whose only JDK is Android Studio's bundled 21 even `:app:assembleFiretvDebug` failed.
  The module had been commented out of `settings.gradle.kts` to work around that, which silently
  meant CI ran a module local builds never compiled.

Run the whole matrix before pushing:

```bash
./gradlew :test-runner:test :app:lintFiretvDebug :app:assembleFiretvDebug :app:lintGoogletvDebug :app:assembleGoogletvDebug
```

## Testing

`./gradlew app:testFiretvDebugUnitTest`. Both flavors must compile.

Tests encode intended behaviour, so a failing test after a change is a question,
not an obstacle — `startOnBoot`'s default was wrong in the model and the test
caught it. But device evidence outranks a test: the two TEARDOWN tests asserted
behaviour that demonstrably broke iOS, and were rewritten.

## Working style

- Terse by default, but explain the root cause when something is genuinely
  surprising. The exFAT zeroing and the volume permission both needed it
- Installing to the device is expected. Verify on-device rather than asking the
  user to check, and say plainly when something is still unverified
- Never `am start` after install; never `pm clear` without saying so first
