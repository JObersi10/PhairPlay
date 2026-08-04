# PhairPlay — Handoff

Last updated: 2026-08-04.
Branch `feature/receiver-ux-and-cast-removal`, **not pushed**.

Read `CLAUDE.md` first — especially the native-library verification step, which is not optional,
and the "Hard-won details" entry on pause detection.

## Where things stand

Installed on the Fire TV at `192.168.1.246`. Both flavors build.

### The open bug: pause is still wrong

The Now Playing progress bar keeps counting while the sender is paused, then snaps back roughly
six seconds later. Three attempts have failed, and the failures are informative:

| Attempt | Why it failed |
|---|---|
| Progress `SET_PARAMETER` pushes | Sparse, and `pos` is quantised to whole seconds |
| Decoded-PCM silence | A paused sender's stream is not necessarily silence |
| RTSP `FLUSH` | iOS sends FLUSH right after RECORD at stream start, and on seek — not just on pause |

The FLUSH one is worth dwelling on, because the spec genuinely says FLUSH means "flush the
receiver's buffer and pause/stop what is playing", and the AirPlay documentation HTML in the
project folder repeats it. The device disagrees with the spec. Trust the device.

**Current mechanism:** `AudioStreamServer.AUDIO_IDLE_MS = 400`. A socket read timeout with no
packet is treated as paused; the next packet resumes. This is *also unconfirmed* — the log
evidence that packets stop during a pause was weak, because only the first six RTP packets are
ever logged, so a gap in the log does not prove a gap in the stream.

**Next step, and do this before writing any more code.** A `PAUSE-PROBE` line now logs once a
second:

```
PAUSE-PROBE 1002ms: pkts=115 bytes=118450 rtpAdvance=1000ms
```

Play, pause for ~10s, resume, then `curl -s http://192.168.1.246:8001/ | grep PAUSE-PROBE`.
Whatever a paused sender does — stop transmitting, send silence, or freeze its RTP clock — one of
those three numbers must change, and that number is the pause signal. Remove the probe once it has
answered the question.

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

1. **Pause.** See above. Nothing about it is confirmed.
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
