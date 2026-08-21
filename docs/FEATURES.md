# PhairPlay — feature list

Fire TV receiver for AirPlay 2, DLNA and Miracast. What it does, as of 2026-08-18.

## Receiving

| Protocol | Port | State |
|---|---|---|
| AirPlay 2 audio (RAOP) | 7000 | Working — ALAC and AAC |
| AirPlay screen mirroring | — | Working, iPhone and macOS |
| AirPlay video (YouTube, Safari …) | 7000 | Working — full quality, not a mirror |
| DLNA / UPnP MediaRenderer | 8200 | Working, including GENA eventing |
| Miracast (Wi-Fi Direct + WFD) | 7236 | Advertising; needs location granted on API < 33 |

Not supported: Google Cast. Port 8009 is permanently held by `com.amazon.cast.sink`, and a
receiver must answer `DeviceAuthMessage` with a Google-CA-signed chain that cannot be obtained.

Known hard limit: **macOS Music.app audio** (FairPlay v2 key derivation). Mirroring and Safari
audio from the same Mac are fine.

## Remote control

The TV remote controls the **sender**, over DACP.

- Play / pause / next / previous
- D-pad left and right scrub — hold to seek, release to resume
- Requires both mDNS services to advertise `srcvers 350.0`; modern iOS withholds `DACP-ID`
  otherwise. See `CLAUDE.md`.

## Now Playing card

- Artwork, title, artist, album, composer/year, progress with elapsed and remaining
- Source pill — "Audio from <device>"
- **MENU** cycles six layouts: full, small centred, and each of the four corners.
  Resets to full at the end of every session.
- **Hold MENU** for the extended credits panel
- **Back** ends the session and drops the sender's route
- Marquee for long titles; artwork held briefly across track changes so it does not flash
- Idle screensaver: dims, fades the backdrop, pixel-shifts for burn-in protection
- Picture-in-picture, with a layout built for the window rather than a scaled-down copy

## Audio-reactive backdrop

Three signals extracted from the decoded PCM before it reaches the speakers:

| Signal | Method |
|---|---|
| Bass | One-pole low-pass at 160 Hz |
| Vocals | Mid/side — band-passed `(L+R)/2` minus band-passed `(L−R)/2`, isolating centre-panned content |
| Treble | Above 4 kHz |

Each band is measured as a **rise above its own recent average**, not as a fraction of its peak —
a steadily-loud band sits at its own peak permanently, which pins the visual bright and motionless.
Vocals additionally carry an absolute presence term, because a held note has no rise and the orb
would otherwise go dark exactly while someone is singing. A slow cross-band gain then evens the
three up against one another, so no band is systematically brighter than its neighbours.

Three backdrops, selectable in Settings:

- **Dynamic** — four drifting, album-coloured blobs, each bounded to its own quadrant and each
  riding its own band, so the field changes shape rather than merely growing and shrinking
- **Projector** — three orbs on true black, one per band (left bass, middle vocals, right treble),
  orbiting on slow ellipses and fusing where they overlap. Built so nothing ever meets a frame edge.
- **Black** — nothing at all behind the card. The redraw loop stops entirely rather than painting
  a black rectangle sixty times a second.

Visuals are delayed by the measured output latency so they land with the sound, not ahead of it.
Beat Pulse strength (Calm / Normal / Strong / Insane) is in Settings and applies to both Dynamic
and Projector. Timing follows Audio delay and the route compensation; there is no separate dial. Intensity is applied to the drawing rather than to the level,
so turning it up keeps adding movement instead of flattening everything against the top of the range.

Palette comes from the artwork, with greyscale covers detected and kept grey rather than having a
hue invented for them. When a cover does not contain three separable colours, the shortfall is
filled with lighter and darker shades of the accents it *does* have — never with a rotated hue,
which is how an all-orange sleeve used to end up with blue and green orbs.

## A/V sync

**Audio delay** is the one dial, and it is the user's: it holds the audio back to meet the sender's
timeline, and the beat visuals follow it automatically.

Underneath it, `AudioRouteMonitor` watches which output is actually playing — HDMI, the TV's
speakers, wired, or a named Bluetooth sink — and when it is Bluetooth the *visuals* are held back a
further **350ms**, automatically. That figure is not shown and not settable. It is a property of the
transport rather than a preference: a Bluetooth speaker is late by about this much whether or not
anyone has an opinion, and it goes away the moment the speaker does. Audio delay still reads 0,
because 0 extra is what the user chose. The row says which speaker is being compensated, so the
adjustment is visible without being something to fiddle with.

The visuals move rather than the audio because the audio is the side that is already late — delaying
it further would only make it later.

350ms was measured by ear on this hardware against an SBC speaker, which is the codec this Fire TV
negotiates. Android exposes no API for the real figure: `AudioTrack.getTimestamp()`, which
`AudioStreamServer.outputLatencyMs()` already consults, stops at the HAL — the point where audio
leaves the box — and the encoder, the radio link and the speaker's own jitter buffer are all past it.

The compensation applies to a stream already playing, so connecting a speaker mid-track works. The
user's audio delay does not: it is pre-buffered as silence at stream start, so it lands on the next
connect.

## Cover art

AirPlay senders supply artwork. DLNA control points often do not, so:

1. `upnp:albumArtURI` from the DIDL-Lite document, when present
2. MusicBrainz → Cover Art Archive lookup by album and artist, falling back to album alone

Optional, off by default — a receiver reaching out to a third party should be the user's choice.
No API key required.

## Settings

PIN pairing (off / remember senders / every time) · which receivers run · device name ·
screensaver timeout · backdrop (Dynamic / Projector / Black) · Beat Pulse · audio delay · volume mode ·
Back behaviour (stop stream / go home / exit) · stream-end behaviour · start on boot ·
Picture-in-picture · online cover art · playback quality

## Diagnostics

`http://<tv>:8001/` full dump, `:8002` streaming tail. Ring buffer holds events since app start —
reproduce first, then curl.
