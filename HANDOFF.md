# PhairPlay — Handoff

Last updated: 2026-08-04.
Branch `feature/receiver-ux-and-cast-removal`, **not pushed**.

Read `CLAUDE.md` first — especially the native-library verification step, which is not optional,
and the "Hard-won details" entry on pause detection.

## Where things stand

Installed on the Fire TV at `192.168.1.246`. Both flavors build.

### Pause — solved, needs confirming on device

A paused iOS sender keeps sending at full rate (~128 packets/sec) with its RTP clock advancing in
real time. The packets are empty: 44 bytes of header, no audio.

```
12:22:13  pkts=128  bytes=5632     paused
12:22:16  pkts=128  bytes=5632     paused
12:22:18  pkts=125  bytes=117763   playing
```

That is the whole reason four earlier detectors failed — packet arrival, RTP-clock advance,
decoded-PCM silence and RTSP `FLUSH` all look identical whether or not the sender is paused. Only
the payload size differs, by a factor of twenty.

`handleRtpPacket` now treats 12 consecutive packets of ≤ `KEEPALIVE_MAX_BYTES` (64) as a pause,
and the first real frame as a resume — roughly 100ms either way. Dropping those packets also stops
them reaching the decoder, which had been decoding 32-byte fragments into garbage.

Worth recording: FLUSH is documented as "flush the receiver's buffer and pause/stop what is
playing", in the spec and in the AirPlay HTML in the project folder. iOS nonetheless sends it
immediately after RECORD at the start of every stream and again on every seek. Acting on the
documentation instead of the device cost several rounds.

Confirmed working on device. The `PAUSE-PROBE` instrumentation has been removed.

### Position sync

Position now derives from the receiver's own audio clock rather than wall-clock extrapolation.
`AudioStreamServer.playingRtpTimestamp()` returns the RTP timestamp currently reaching the
speakers; the progress push supplies the track's starting timestamp in the same units; the
difference is the position, re-read four times a second. The old path extrapolated from pushes that
arrive only every few seconds and are quantised to whole frames, which is where the reported
"2–4 seconds off" came from. `NowPlayingScreen`'s resync tolerance dropped from 2000ms to 400ms
to match.

## Landed this session

- `AirPlayReceiver.endSession()` now calls `releaseMediaComponents()`. Closing the RTSP socket left
  the audio and mirror UDP servers running, which is why Back looked like it did nothing.
- **Back is now one setting, not two.** `BackAction` — STOP_STREAM (default) / GO_HOME / EXIT_APP —
  replaces `backQuitsApp` and `backGoesHome`. Those were two booleans answering two different
  questions with overlapping answers, and nobody could tell which was which. Each dialog option
  states its consequence, including whether the receiver keeps advertising.
  `SettingsRepository` migrates the old DataStore keys, so an upgrade keeps the user's choice.
- **Beat delay** setting (0–1000ms, `AppSettings.beatDelayMs`), separate from audio delay. A
  Bluetooth speaker's output latency is invisible to `AudioTrack.getTimestamp`, so the beat fires
  when PCM leaves the device rather than when it is heard. Correcting that via the audio delay
  would desync the audio itself, hence a second dial that moves visuals only.

## Unverified — needs one session each

1. **Position sync** — the audio-clock path is new and unlistened-to. If position looks stuck,
   check that progress pushes are arriving at all (`grep "SET_PARAMETER progress"`): without one,
   `anchorStartTs` stays -1 and the ticker does nothing.
2. **Back ending the stream.** The `releaseMediaComponents` fix is untested on device.
3. **`BackAction` migration** from an install that had the old booleans set.
4. **Beat delay** — the plumbing compiles; nobody has listened to it.
5. **Overnight sleep.** Leave the TV off overnight, then connect. Should work first try.

## Open work, not started

### Mirroring freeze (iOS 26.1)

Diagnosed, not fixed; see `CLAUDE.md` for the symptom and capture procedure. Needs the type-5
payload hex first. Do not feed an unknown payload through `cipher.update()` to see what happens —
that advances the AES-CTR keystream and corrupts every later frame.

### 7-second mirror connect

Measured: our own path is ~111ms, the RTSP handshake ~600ms. The remaining ~6s is iOS-side and may
not be ours to fix.

### Deferred by the user

Onboarding media, lyrics, sender-type display.

## Cautions

- **The exFAT drive corrupts native build output.** Verify the ELF magic before every install; see
  `CLAUDE.md`. It can corrupt source files too — if a file behaves impossibly, check `git status`
  before debugging the logic.
- A killed Gradle run can leave `~/.gradle/caches/journal-1` locked. The error names the owner PID.
- **Nothing is pushed.** The user has not asked for it.
