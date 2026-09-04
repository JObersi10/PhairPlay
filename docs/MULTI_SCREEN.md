# Multi-screen casting — plan

Receiving **more than one sender at once** and showing them side by side, up to four.

Status: **working.** Two senders have been served side by side on the device. Capacity is bounded by
decoder THROUGHPUT rather than instance count — see `DecoderCapacity` — so 1440p and multi-screen
cannot both be on: one 1440p60 stream is 221M of the 249M pixels/second this Fire TV has.

## The audio/mirror policy

Mirroring may be shared; **audio may not**, and the two do not mix. One set of speakers means one
stream through them, and a mirror already carries its own audio — a separate audio sender arriving
alongside one would fight it for the output.

That policy is **not** enforced at `SessionRegistry.admit()` and cannot be. Admission happens at
`accept()`, and nothing on the wire at that point says which kind of session the connection will
become; the answer only arrives in the stream list at SETUP. So it is claimed there instead, via
`SessionRegistry.claimType()`.

**`isMirrorSession` is not the signal.** A Mac sending audio only still uses the mirror handshake and
still reports `isMirrorSession`, which is exactly why the naive test fails: what actually makes a
session a mirror is the presence of a **type-110 video stream**. Audio claims the session only when
no video stream has claimed it first.

The cost of deciding late is that a refused sender has already completed pair-verify by the time it
loses. It gets an explicit socket close rather than a SETUP reply with a stream quietly missing — a
sender handed the latter waits forever for data that never comes.

## Measured: the decoder ceiling is not the problem

Read off the target Fire TV (`/vendor/etc/media_codecs_mediatek_video_svp.xml`):

```xml
<MediaCodec name="OMX.MTK.VIDEO.DECODER.AVC" type="video/avc">
    <Limit name="concurrent-instances" max="5" />
    <Limit name="performance-point-1920x1080" value="120" />
</MediaCodec>
```

Five concurrent H.264 decoder instances, and 120fps of 1080p decode budget — enough for two 1080p60
mirrors with headroom, and a four-tile layout is within the hardware. `DecoderCapacity` reports the
same number at runtime (it is in the `:8001` header as `decode:`), because the vendor XML is what the
device claims and not necessarily what it hands out under memory pressure.

**Step 1 passes. The feature is not blocked on hardware.** It is blocked on the state untangling
below.

---

## What the code assumes today

Every one of these is a deliberate single-session assumption that has to be lifted.

**One sender is enforced, on purpose.** `RtspHandler.runServer` keeps a single `activeClient` and
answers anyone else with 503:

```kotlin
val current = activeClient
if (current != null && !current.isClosed) {
    Logger.w("Rejecting second client ${clientSocket.inetAddress.hostAddress} — already streaming")
    runCatching { sendServiceUnavailable(clientSocket) }
```

That rejection is not an oversight — the comment above it records a real bug where a queued second
socket was adopted minutes stale. Whatever replaces it has to keep answering newcomers *immediately*;
the failure mode to avoid is a sender left hanging, not a sender turned away.

**One of everything else.** `AirPlayReceiver` holds `mirrorServer`, `audioServer`, `bufferedAudioServer`
and `videoDecoder` as single `@Volatile var` fields. `MainActivity` owns one `SurfaceView`, handed
over by `videoSurfaceProvider()`. `PhairPlayService` tracks one `ProtocolState`, one sender name, one
route compensation.

## The two real ceilings

Both must be measured before any of this is worth building.

**1. Concurrent hardware H.264 decoders.** Each mirror needs its own `MediaCodec`. A Fire TV Stick
will not give you four; two is the realistic expectation and even that is not guaranteed. This is the
binding constraint on the whole feature, and it is knowable today:

```kotlin
// Allocate decoders until createDecoderByType throws, then release. Run once, cache the answer.
MediaCodecList(REGULAR_CODECS).codecInfos
    .first { it.supportedTypes.contains("video/avc") && !it.isEncoder }
    .getCapabilitiesForType("video/avc").maxSupportedInstances
```

`maxSupportedInstances` is advisory; the honest number comes from actually allocating them. **Do this
first.** If the device says two, the feature is "two screens", and building a four-tile layout is
wasted work.

**2. Whether senders tolerate it.** A real Apple TV does not accept two simultaneous mirrors, so this
is outside what iOS is tested against. [xfirefly/Airplay-SDK](https://github.com/xfirefly/Airplay-SDK)
advertises "支持多个AirPlay同时镜像" and up to four tiles, which is good evidence it works in practice —
but it is closed-source with **no license statement**, so it is proof-of-possibility only. No code
from it can be read or used. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Design

### One session object, many instances

```kotlin
class MirrorSession(
    val id: SessionId,              // from the sender's pair-verify identity, stable across reconnects
    val socket: Socket,
    val keys: SessionKeys,          // per-sender: pair-verify secret, AES key/IV
    val mirrorServer: MirrorStreamServer,
    val decoder: VideoDecoder,
    val tile: Tile,                 // which surface it draws to
)
```

Everything currently on `AirPlayReceiver` as a `@Volatile var` moves onto this. `AirPlayReceiver`
keeps a `SessionRegistry` — a capacity-bounded map — instead of a set of nullable fields. That
refactor is most of the work and is worth doing on its own even if multi-screen is never finished:
the singleton fields are why a stream-level TEARDOWN could ever kill the wrong thing.

**Ports.** Not a problem. SETUP already replies with the port for each stream, so binding port 0 and
reporting what the OS assigned gives every session its own data channels for free. Only RTSP 7000 and
mDNS are genuinely shared.

**Audio stays single.** N video, one audio. Two songs at once is not a feature, and the beat/backdrop
pipeline (`onEnergy`/`onBands` → `DynamicBackground`) is built around one PCM source. One session is
the *audio primary*; the rest are muted, decoded for video only. The primary is whoever connected
first, with an explicit way to switch. Mixing N streams into one `AudioTrack` is possible and is
deliberately out of scope.

### Capacity and refusal

`SessionRegistry` is bounded by the measured decoder count. Over capacity, keep answering
immediately — a clean 453 "Not Enough Bandwidth" or the existing 503, never silence. The one
behaviour that must not come back is a socket queued and adopted stale.

### Layout

`MultiScreenLayout`, a `FrameLayout` holding four `SurfaceView` tiles in a 1 / 2 / 3-4 grid,
re-laid-out as sessions come and go. Each tile owns its Surface for its whole life, which sidesteps
the cold-first-connect race per tile rather than re-creating it four times over.

Focus and the remote need answering before this ships: with four senders on screen, "which one does
Back end?" has no obvious answer today. Proposal — a focused tile, moved between with the D-pad,
and Back ends the focused session only.

## Order of work

1. ~~**Measure the decoder ceiling.**~~ **Done — 5 instances.** See above.
2. ~~Lift the `activeClient` field to a capacity-bounded `SessionRegistry`.~~ **Done, capacity 1.**
   Identical behaviour: one sender in, everyone else refused on the spot. Covered by
   `SessionRegistryTest`.
3. **Make `RtspHandler`'s per-connection state per-connection.** ← *this is the next real step, and
   it is the whole job.* See below.
4. `MultiScreenLayout` with tiles, still capacity 1.
5. Raise capacity to the measured number. Audio primary + muting.
6. Remote semantics for a focused tile.

### Why step 3 is the blocker

`SessionRegistry.capacity` can be set to 4 today and the accept loop will happily admit four senders.
It must not be, because `RtspHandler` still keeps the entire handshake in fields shared by every
connection:

```kotlin
private var currentSession: ...      // stream list, hasVideo
private var pairingSession: ...      // pair-verify secret
private var fairPlay: ...            // FairPlay state machine
private var isMirrorSession: Boolean
private var currentVolume: Float
private var currentRemoteAddress: InetAddress
private var activeStreamTypes / setupCount
```

`handleClient` resets `pairingSession` and `fairPlay` on entry, so a second sender connecting
mid-handshake would wipe the first one's keys and both sessions would fail — the first with a
decryption error that looks nothing like its cause. These have to move into a per-connection object
that `handleClient` owns and `routeRequest` is handed, which is a wide but mechanical change through
a 1,500-line class.

Doing that with only one iPhone to test against is the risk: the refactor is verifiable for the
single-sender case (which is every existing test) and unverifiable for the case it exists to enable.
Sequence it so single-sender behaviour is provably unchanged first, and only then raise the capacity.

## Risks

- **The decoder ceiling is 1 on the target stick.** Kills the feature; found in step 1, cheaply.
- **iOS refuses to be the second sender.** Not knowable without two devices and a session that gets
  far enough to try.
- **Thermal and bandwidth.** Two 1080p mirrors is roughly double the Wi-Fi and decode load. The
  existing macOS backlog-churn bug is a queueing problem that more senders will make worse, not
  better.
