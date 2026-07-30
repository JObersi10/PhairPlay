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

### Audio buffering — undersized (offered twice, user deferred both times)

Causes two separate symptoms the user reported: choppy audio at session start, and
glitching when pressing Home mid-session. Same root cause. **Not** an underpowered
Fire TV — the user suspected that, and the stats rule it out.

```
AudioTrack: minBuf=7184B (~40ms), buffer=14368B (~81ms latency)
Audio stats: recv=5000 dup=2 (0% dup) qDrop=10 resendReq=2 resendFill=2 queue=89
```

Read those numbers carefully:

- `queue=89` is a **frame count**, not a percent — the format string is
  `queue=${frameQueue.size}` and `AUDIO_QUEUE_CAPACITY` is 96. So the queue was
  93% full. (In a pasted terminal log a trailing `%` is usually zsh's
  no-newline marker, not part of the output. Cost time to notice.)
- At 352 samples/frame, 89 queued frames is ~710 ms of audio backed up, while
  AudioTrack holds only 81 ms.
- `qDrop=10` is the overflow eviction at `AudioStreamServer:246` —
  `frameQueue.poll()` then re-offer. Each eviction is an audible glitch.
- `dup 0%` and `resendReq=2` out of 5000 mean the network is fine, and decode is
  keeping up. A CPU-bound decoder would show a pinned queue and drops in the
  hundreds.

`AudioTrack.write` with `WRITE_BLOCKING` paces at exactly realtime, so once
playback falls ~700 ms behind it stays there permanently — nothing drains the lead,
and eviction is the only relief valve. Pressing Home tips it over: the Surface is
destroyed, the main thread does teardown work, the writer thread is descheduled
briefly, and 81 ms of slack isn't enough to absorb it.

The sender advertises `latencyMin=11025, latencyMax=88200` — 250 ms to 2 s at
44100 Hz. We run at a third of its stated *minimum*.

Fix, all contained to `AudioStreamServer`:

1. Size the AudioTrack buffer to ~250–400 ms instead of 81 ms.
2. Prime several frames before calling `play()` rather than starting on packet zero.
3. Add a high-water mark that resyncs when the queue runs persistently deep, so one
   delay can't leave playback permanently behind.

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
