# PhairPlay — Handoff

Last updated: 2026-09-01.
Branch `feature/bluetooth-av-sync`, last pushed commit `e16b7cf` on the `jobersi`
remote (github.com/JObersi10/PhairPlay). `origin` points at mazer666's copy — do not push there.

The tree is clean; everything below is committed and pushed.

Read `CLAUDE.md` first — especially the native-library verification step, which is not optional,
and the build environment note (there is no system JDK or Android SDK; both live on the drive).

## Where things stand

Screen mirroring **works** from both iPhone (iOS 26.1) and macOS — 42–45 fps, drops confined to
the first second. That was the headline bug and it is fixed. What remains is rough edges around it.

The Fire TV's IP moves; it has been `192.168.1.246` and `192.168.1.108` within one session. Check
`adb devices` rather than trusting either.

## Landed 2026-08-09

- **The TV remote controls an iPhone again.** Not a new protocol — the version we advertise. iOS
  decides once, at `GET /info`, whether a receiver may control playback, and decides it from
  `srcvers`. Above ~350 it withholds `DACP-ID`/`Active-Remote` entirely and nothing later in the
  session recovers them. All three advertisement sites now read `AirPlayVersion.ADVERTISED`
  (`350.0`), so reverting is one line. Confirmed: DACP resolves and `nextitem`, `previtem`,
  `playpause`, `beginff` all return HTTP 200. Mirroring negotiation is unaffected.
- **Position counter no longer stalls**, worst of all during a seek. Two suppressions: the ticker
  emitted only on a move greater than 0.25 s, which is exactly one 250 ms tick at normal speed, so
  it sat on its own threshold; and the sender's own progress pushes were ignored unless they
  differed by over two seconds — backwards while seeking, since that push is the correction. Both
  now emit when the displayed whole second changes.
- **No more black screen when a sender drops the video connection without a TEARDOWN.** TikTok
  going fullscreen does exactly this. `stopMirrorVideo` was reachable only from TEARDOWN, so
  `videoPlaying` stayed true and the UI sat on a dead Surface. `MirrorStreamServer` now reports the
  connection ending; a deliberate `stop()` is excluded with a `stopping` flag.
- **MediaRemote decoding.** `MediaRemote.kt` reads the sender's `CommandInfo` protobufs, so
  `updateMRSupportedCommands` now names what the sender honours instead of logging `<104B>`. The
  *send* path is parked — see open work.
- **Instrumentation, awaiting a run**: which surface the decoder draws into (live / off-screen /
  none) in both `rebuildDecoder` and the stats line, and why the video reader ended (EOF, timeout,
  or exception with type and message).

## Open work

See `CLAUDE.md` "Open bugs" for the full diagnosis of each.

1. ~~Cold first connect shows nothing.~~ **Fixed and confirmed on device** (2026-08-07): first
   attempt decodes at +91 ms with no reconnect. Two things got it there — pre-warming the Surface
   from `onSenderApproaching`, and `MirrorStreamServer` falling back to an off-screen `ImageReader`
   after 300 ms instead of waiting, then retargeting the live codec with `setOutputSurface` when the
   real Surface appears (no keyframe wait). An earlier attempt that blocked the mirror SETUP reply
   was reverted — the Surface cannot exist at that point, so it only added 2.5 s per connect.
2. ~~Remote skip/next does nothing on AirPlay 2 senders.~~ **Fixed** via `srcvers` (above).
   The MediaRemote send path is **parked, and should stay parked.** shairport-sync's maintainer
   reports the AirPlay 2 control system has not been reverse engineered by anyone, and our own
   measurements agree: the iPhone opens the event channel and never writes a byte to it in either
   direction, and twelve `POST /command` messages over it drew no reply and no state change —
   under both `RTSP/1.0` and `HTTP/1.1` framing. The protobuf itself is correct (byte-identical to
   protobuf's own serializer), so if this is ever revisited the unknown is the transport, not the
   message. The decode half is worth keeping regardless.

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

## Multi-room: what exists and what does not

Measured against the openairplay/airplay2-receiver feature list, since that is the closest
comparable implementation.

**Have:**

| Feature | Where |
|---|---|
| FairPlay v3 auth + AES key decryption | `FairPlay.kt` — working; mirroring depends on it |
| AirPlay 2 service publication | `MdnsService.kt` |
| REALTIME audio streams (type 96) | `AudioStreamServer.kt` |
| BUFFERED audio streams (type 103) | `BufferedAudioServer.kt` |
| ALAC, AAC-LC, AAC-ELD decode | `AlacDecoder.kt` + native `libalac`, MediaCodec |
| ANNOUNCE + RSA AES (iTunes/Windows, unbuffered) | `RaopRsa.kt`, `handleAnnounceInternal` |
| HomeKit **non**-transient pairing, persisted across restarts | `PairingSession/Keys/Store.kt` |
| SRP PIN pair-setup | `LegacyPairSetupPin.kt` |
| NTP time sync | `AirPlayNtpClient.kt` — receiver-initiated |

**Do not have:**

| Feature | Notes |
|---|---|
| **PTP timing** | The big one. Multi-room sync is PTP, not NTP. We only do NTP. |
| **`SETPEERS` / `SETPEERSX`** | Acknowledged and logged only. This is the peer list — the actual multi-room primitive. |
| **`SETRATEANCHORTIME`** | Same: acknowledged, not implemented. This is the shared playback anchor. |
| Output latency compensation across receivers | We apply per-stream latency; nothing coordinates with another device. |
| HomeKit **transient** pairing (bit 48) | No implementation. |
| OPUS decode | Not present. |
| RFC2198 RTP redundancy (bit 61) | Not present. |
| `streamConnections` (bit 59) | We use a `streamConnectionID` for mirroring; not the same feature. |
| RTCP | `RtpInterleaved` recognises channel 1 and deliberately ignores it. |
| ~~Spotify / live streams with AES keys~~ | **Works** (confirmed 2026-08-09). |

**If multi-room is the goal, the order is:** PTP first (nothing else syncs without it), then
`SETPEERS` to learn the group, then `SETRATEANCHORTIME` to share the anchor, then cross-device
latency compensation. The three RTSP verbs already arrive and are already parsed enough to log —
`handleBufferedControl` is where they land — but note it logs at `Logger.d`, which Fire OS drops
for this package, and it collapses lists to `list[n]`. Both need fixing before a capture of a real
grouping attempt is worth anything; that is the first commit of any multi-room work. The feature bits advertised in `AIRPLAY_FEATURES`
(`0x5A7FFFF7,0x1E`) will need auditing at the same time, since claiming a capability we do not
implement is its own class of bug.

Note the interaction with the `srcvers` change above: `350.0` is what buys DACP remote control, and
it is deliberately an older-generation version. Whether iOS will offer multi-room grouping to a
receiver advertising that is unknown and should be checked early — it may force a choice between
remote control and multi-room.

---

## Deferred: Now Playing redesign (2026-08-31, still open)

Home, Settings, onboarding and the waiting screen were redesigned. **Now Playing was deliberately
left alone** — it is the largest UI file (`NowPlayingScreen.kt`, ~1,570 lines) and deserves its own
pass rather than being bolted onto a diff that was already large.

Since then only one thing has been touched there, on 2026-09-01: `letterSpacing` on the album,
secondary-meta and pill-label lines. Everything below is still to do.

**Leave the playback bar alone unless the change is provably safe.** The user has had it break on
previous Now Playing edits, and asked explicitly. That means `ProgressView`, `positionTick`,
`senderPositionMs`, `formatTime` and the two time labels. One optimisation was spotted and
deliberately *not* taken: `positionTick` keeps running while the card is hidden but still attached
(`onVisibilityChanged` removes `debugTick` and not `positionTick`). Stopping it while invisible would
save a wake-up per second, but getting the restart wrong freezes the position display — exactly the
class of breakage being avoided. Only do it with a sender connected to verify against.

The screen is already well optimised, so do not go looking for easy wins: artwork is downsampled to
the tile size before decode, the marquee is a single out-and-back pass rather than a loop, and the
`TransitionDrawable` is swapped back out after the fade so two full bitmaps are not held.

When picking it up, the plan agreed was:

- **Do not touch `DynamicBackground`.** It is audio-driven (`setEnergy`/`setBands` from decoded PCM)
  and the band analysis is correct. Nothing about the visual pass needs it changed.
- Apply the shared scale (`dimens.xml`) and the gradient page so it matches the rest of the app.
- Carry the receiver motif into the *arrival* moment only: `ReceiverFieldView` resolves as a sender
  connects, then hands off to the backdrop. It should not run underneath playback.
- Consistent focus treatment on the MENU info panel (`FocusMotion.attachFlat` for full-width rows).
- Animate track changes rather than hard-swapping text (`ProtocolCardAnimator` is the pattern).
- Leave the position/progress logic alone — it is derived from the audio clock and is correct.

## Multi-screen mirroring: measured, started, NOT usable yet (2026-09-01)

`docs/MULTI_SCREEN.md` is the full plan. State:

**Step 1 done — the hardware is not the blocker.** `OMX.MTK.VIDEO.DECODER.AVC` declares
`concurrent-instances max="5"` and `performance-point-1920x1080` of 120, so two 1080p60 mirrors fit
with headroom and a four-tile grid is within the device. `DecoderCapacity` reports it at runtime and
it is in the `:8001` header as `decode:` (reads `5` on the Fire TV, verified on device).

> Ask the **hardware** decoder specifically. The first version took the max across every AVC decoder
> and reported **32**, because `c2.android.avc.decoder` is software and advertises 32 — nothing in it
> is a fixed resource. Sizing a tile grid off that would promise mirrors the CPU cannot decode.

**Step 2 done — `SessionRegistry` replaces `RtspHandler.activeClient`.** Capacity is 1, so behaviour
is unchanged: one sender served, everyone else refused on the spot, never queued. `SessionRegistryTest`
covers the refusal, the slot coming back, the stale-release race, and reclaiming a socket whose sender
vanished without a teardown.

**Step 3 is the whole remaining job, and it is the reason capacity is still 1.** `RtspHandler` keeps
the entire handshake in fields shared by every connection — `currentSession`, `pairingSession`,
`fairPlay`, `isMirrorSession`, `currentVolume`, `currentRemoteAddress`, `activeStreamTypes`,
`setupCount`. `handleClient` resets `pairingSession` and `fairPlay` on entry, so a second sender
connecting mid-handshake wipes the first one's keys and **both** sessions fail — the first with a
decryption error that points nowhere near the cause. These have to move into a per-connection object
that `handleClient` owns and `routeRequest` is handed: wide, mechanical, through a ~1,500-line class.

Do not raise `SessionRegistry.capacity` before that lands. The registry will happily admit four
senders today; nothing below it is ready for them.

The awkward part: that refactor is verifiable for the single-sender case (every existing test) and
**unverifiable for the case it exists to enable**, without two sending devices. Sequence it so
single-sender behaviour is provably unchanged first, then raise capacity as its own commit that can
be reverted on its own.

Still unknown and not knowable from here: **whether iOS will agree to be the second sender.** A real
Apple TV refuses two simultaneous mirrors, so this is outside what Apple tests against.
[xfirefly/Airplay-SDK](https://github.com/xfirefly/Airplay-SDK) advertises it working, which is
proof-of-possibility only — it is closed-source with no license statement, so no code from it can be
read or used.

## Landed 2026-09-01

- Decoder ceiling measured; `DecoderCapacity` + the `decode:` line in the `:8001` header.
- `SessionRegistry` (capacity 1) replacing the `activeClient` field, with tests.
- **AVCC→Annex-B converts in place.** A 4-byte length and a 4-byte start code are the same width, so
  a well-formed payload converts by overwriting eight bytes per NAL and returning the same array. The
  old path built a `ByteArrayOutputStream` and then `toByteArray()`'d it — two full copies of a few
  hundred KB, sixty times a second, to rewrite a header. `MirrorAnnexBTest` covers the malformed and
  truncated tails, where it still falls back to one copy.
- Now Playing: tracking on the three small text lines only (album, secondary meta, pill label). The
  title already had negative tracking; small text never got its counterpart.

**Unverified on device:** everything above compiles, passes the matrix, and installs, but no sender
has connected since. The mirror path in particular (`avccToAnnexBInPlace` runs on every frame) has
been tested only against synthetic buffers. Connect a real sender before trusting it.
