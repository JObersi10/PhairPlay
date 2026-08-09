# PhairPlay — Handoff

Last updated: 2026-08-07.
Branch `feature/receiver-controls-and-miracast`, last pushed commit `03fd793` on the `jobersi`
remote (github.com/JObersi10/PhairPlay). `origin` points at mazer666's copy — do not push there.

**There is uncommitted work in the tree.** Everything in "Landed this session" below is on disk
only. A snapshot of an earlier state of it also exists on the local branch `scrap/airplay-video-hunt`.

Read `CLAUDE.md` first — especially the native-library verification step, which is not optional,
and the build environment note (there is no system JDK or Android SDK; both live on the drive).

## Where things stand

Screen mirroring **works** from both iPhone (iOS 26.1) and macOS — 42–45 fps, drops confined to
the first second. That was the headline bug and it is fixed. What remains is rough edges around it.

The Fire TV's IP moves; it has been `192.168.1.246` and `192.168.1.108` within one session. Check
`adb devices` rather than trusting either.

## Landed this session (uncommitted)

- **Bodyless `POST /command`.** The one that mattered. iOS was being answered with an empty binary
  plist, abandoned the session silently after `RECORD`, and never sent the `streams` SETUP —
  mirroring showed nothing at all. An Apple TV replies with no body; now so do we.
- **`pk` in the mDNS TXT record** (plus `protovers=1.1`). iPhones and iPads were leaving PhairPlay
  out of the AirPlay picker entirely while macOS listed it. Verified on the wire.
- **Touch support**, alongside the remote, routed by the same `sessionMode` latch: tap reveals the
  mirror control bar on video, tap is play/pause on audio, horizontal fling changes track. No
  scrubbing by touch. Gestures are ignored in PiP and when no session owns the screen.
- **Tablet-installable.** `leanback` is no longer `required`, and the launcher intent carries
  `LAUNCHER` as well as `LEANBACK_LAUNCHER`. Use the `googletv` flavour for tablets.
- **`Streaming from %1$s`** literal on the Home card — `setText(resId)` does not substitute
  arguments. Formatted properly now, with a plain "Streaming" fallback during pairing.
- **Session goes idle when the phone stops mirroring.** `TEARDOWN streams=[110]` left the overlay
  on a frozen last frame: `emitNowPlaying()` with no metadata emits null, and the service reads
  "null while CONNECTED" as a running video session. Now drops to `ADVERTISING` when nothing is
  playing, while keeping the RTSP session up for iOS renegotiation.
- **4-second handover grace** before the app hands the TV back. Mirroring→video tears down one
  session and opens another a beat later, and leaving in the gap looked like a self-quit.
- **Event channel answers requests** (200 + echoed CSeq) instead of silently draining them.
- **`Apple-Response`** for legacy RAOP senders that put an `Apple-Challenge` on OPTIONS — TikTok
  hung up immediately without it. **Untested.**
- Quieter teardown: a deliberate close no longer logs a stack trace as an error.
- Model bumped `AppleTV5,3` → `AppleTV6,2`, server version `220.68` → `377.40.00`.

## Open work

See `CLAUDE.md` "Open bugs" for the full diagnosis of each.

1. ~~Cold first connect shows nothing.~~ **Fixed and confirmed on device** (2026-08-07): first
   attempt decodes at +91 ms with no reconnect. Two things got it there — pre-warming the Surface
   from `onSenderApproaching`, and `MirrorStreamServer` falling back to an off-screen `ImageReader`
   after 300 ms instead of waiting, then retargeting the live codec with `setOutputSurface` when the
   real Surface appears (no keyframe wait). An earlier attempt that blocked the mirror SETUP reply
   was reverted — the Surface cannot exist at that point, so it only added 2.5 s per connect.
2. **Remote skip/next does nothing on AirPlay 2 senders — needs MediaRemote. Parked.**
   Not a mapping regression: the key mapping is intact and the `Active-Remote` header parsing is
   unconditional. AirPlay 2 senders send no `DACP-ID`/`Active-Remote`, so `DacpClient` has no
   address. Legacy RAOP senders (TikTok, Mac Music) still work.
   Dumping the sender's `POST /command` payload settled how the modern path works:
   `params={mrSupportedCommandsFromSender=[<104B>, <226B>, <765B>, … 36 blobs]}` — opaque
   MediaRemote **protobuf** messages in a plist wrapper, not a plist vocabulary. Implementing it
   means MRP encoding (pyatv ships the `.proto` files): a subsystem, not a tweak.
   Event-channel encryption **is done and working** (`Event channel cipher ready`, no tag
   failures) — it was a prerequisite, but the sender writes nothing there, so it was never the
   blocker.
3. **macOS audio backlog churn** — ~12 `backlog resync` trims in 3 s.
4. **FairPlay v2 key derivation** — the macOS Music app is silent. Separate RE job.
5. **`Apple-Response` / TikTok** — built, never exercised.

## Testing notes

- The Mac is slow to connect because **PIN auth is on** and macOS is not a remembered controller,
  so it runs the full SRP handshake across two TCP connections every time (~8 s, most of it a gap
  while it reconnects). The iPhone is remembered and skips it. This is configuration, not a bug.
- `Logger.d` is invisible on Fire OS even in a debug build — the whole class is dropped for this
  package. Anything you need to see must be `Logger.i`. This cost several rounds of guessing before
  it was noticed: 0 `D/Logger` lines against 370 `I/Logger` ones.
- Read the trace with `adb -s <ip>:5555 logcat -d -v time Logger:V "*:S"`, filtering out
  `UNHANDLED` and `backlog` noise.

## Cautions

- **The exFAT drive corrupts native build output.** Verify the ELF magic before every install; see
  `CLAUDE.md`. It can corrupt source files too — if a file behaves impossibly, check `git status`
  before debugging the logic.
- **A wedged Gradle daemon blocks every later build and looks like slowness.** `pkill` may not take;
  use `kill -9`. Never pipe a build through `tail` — output is buffered until exit, so a running
  build is indistinguishable from a hung one.
- **Two remotes.** `jobersi` is the user's fork and the one to push to. `origin` is mazer666's
  repository.
