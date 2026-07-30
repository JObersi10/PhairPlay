# PhairPlay — Handoff

Last updated: 2026-07-29, end of session.
Branch `feature/receiver-ux-and-cast-removal`, **not pushed**.

Read `CLAUDE.md` first for build, device and architecture notes — especially the
native-library verification step, which is not optional.

## State

Everything below is committed and the working tree is clean. Both flavors build,
`app:testFiretvDebugUnitTest` passes, and the current APK is installed on the Fire
TV at `192.168.1.246`.

## Commits this session

```
(head)   Debug HUD on the audio screen + "Back exits PhairPlay" option
349bffe  Default sender volume to software gain only
9f0dbfc  Don't end an AirPlay session on a stream-level TEARDOWN
74ecddd  Don't let a failed native library load kill the app
127a2b0  Fix sender volume having no audible effect
e69af9f  Fix ALAC decoder crash, last-sender name, and shutdown log noise
c767018  (earlier) Receiver UX, onboarding, Cast removal
```

## Verified working on device

- AirPlay audio: ALAC decode, artwork with crossfade, marquee text, MENU info panel
- Volume reads correctly in the info panel — the user confirmed it shows both the
  Fire TV Bluetooth speaker level and the phone's current level
- Miracast advertising, after `ACCESS_FINE_LOCATION` is granted at runtime
- DLNA including GENA eventing — a real control point at `192.168.1.190:5001`
  received initial events for all three services
- Onboarding, including the recommended-settings page and visible focus highlight
- Wake lock and Wi-Fi lock held during a session
- Duplicate-start guards: `bind errors this run: 0`
- Service survives the app being swiped away

## Unverified — needs one session each

1. **The TEARDOWN fix (`9f0dbfc`) — highest priority.** The user said "seems good
   now", but the log they sent was from *before* the fix. Nothing has confirmed it.
   Connect and look for:
   ```
   TEARDOWN streams=[96] — stopped those, session continues (active=[])
   ```
   and confirm the session does **not** print `Session ended — returning to the
   previous app`.

2. **Volume, hardware path.** Settings were reset mid-session and re-onboarded, so
   the stored mode is now the new `OFF` default. To test whether
   `MODIFY_AUDIO_SETTINGS` fixed the hardware path, set Settings → sender volume →
   external-only, play over Bluetooth, move the slider, then
   `grep -iE "Sender volume|rejected"`. If the index moves and audio actually gets
   quieter, flip the default back to `EXTERNAL_ONLY`. If it logs
   `Device volume rejected`, `OFF` is correct and can be documented as final.
   Note: the earlier "it doesn't work" evidence predates the permission fix, so
   this is genuinely undecided.

3. **Overnight sleep.** Leave the TV off overnight, then connect. Should work on
   the first attempt — previously took two tries. Tests the
   `ConnectivityManager` IP-change watcher.

4. **Debug HUD on the audio screen** and **"Back exits PhairPlay"** — both shipped
   in the last commit, neither exercised yet.

## Open work

### Mirroring freeze (iOS 26.1) — the real remaining bug

Diagnosed but not fixed; see `CLAUDE.md` for the full symptom and the capture
procedure. Needs the type-5 payload hex before anything can be attempted. Nothing
was guessed at deliberately: decrypting an unknown payload type would desync the
AES-CTR keystream.

### Audio buffering — FIXED, unverified on device

Caused both reported symptoms: choppy audio at session start, and glitching when
pressing Home mid-session. Not an underpowered Fire TV — `dup 0%`, `resendReq=2`
out of 5000 and a keeping-up decoder ruled that out.

Evidence was `queue=89` against `AUDIO_QUEUE_CAPACITY = 96` (a frame count, not a
percent — the trailing `%` in a pasted zsh log is the no-newline marker), i.e. a
~710ms backlog while AudioTrack held ~40ms. `AudioTrack.write` with
WRITE_BLOCKING paces at exactly realtime, so nothing drained that lead and the
queue's overflow eviction became the only relief — one glitch per dropped frame.

Three changes in `AudioStreamServer`:

1. `TARGET_BUFFER_MS = 300` — the builder had `setBufferSizeInBytes(minBuf)` (~40ms)
   while its own log line printed `minBuf * 2`, so the logged latency never matched
   reality. Both now agree.
2. `awaitPrimedQueue()` — accumulate `PRIME_FRAMES = 12` before the first decode,
   bounded by `PRIME_TIMEOUT_MS`. Playback used to start on packet zero with an
   empty pipeline, so the first scheduling delay was already an underrun.
3. `resyncIfBacklogged()` — at `RESYNC_HIGH_WATER = 64` frames, drop down to
   `RESYNC_TARGET = 16` in one go. One artefact instead of a permanent backlog.

To verify: play audio, press Home, then check the stats line. Expect
`buffer=...(~300ms latency)`, `queue=` well under 64, and `qDrop` flat. A
`backlog resync` line is the safety net working, not a regression — but if it
appears repeatedly, the producer is genuinely outrunning the consumer and needs a
real clock-rate fix rather than periodic dropping.

### Not started

- Onboarding media (recordings/pictures) — the user mentioned it, then deferred
- Lyrics — explicitly deferred
- Sender-type display — explicitly deferred

## Cautions

- **The exFAT drive corrupts native build output.** Verify before every install;
  see `CLAUDE.md`. It can in principle corrupt source files too — if a file starts
  behaving impossibly, check `git status` before debugging the logic.
- **Nothing is pushed.** The user has not asked for it.
- **Resolved:** the `pinAuth=false` log line was not a wrong setting. DataStore held
  `airplay_pin_auth` as `12 02 08 01` (true) — the user's choice saved fine. The
  receivers had simply started in `onCreate` nine seconds before onboarding wrote
  the answers, and nothing restarted them. Fixed by restarting the service from
  `onFinished`. The user was right and the log was right; only the ordering was
  wrong. Worth remembering that "the setting didn't save" and "the setting saved but
  nothing re-read it" look identical from a log line.
