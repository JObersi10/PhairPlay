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
| Tempo | Onset intervals scored by harmonic agreement, with acquire/hold hysteresis |

Two looks, selectable in Settings:

- **Dynamic** — four drifting, album-coloured blobs, each bounded to its own quadrant
- **Projector** — three orbs on true black, one per band (left bass, middle vocals, right treble),
  orbiting on slow ellipses and fusing where they overlap. Built so nothing ever meets a frame edge.

Visuals are delayed by the measured output latency so they land with the sound, not ahead of it.
Beat Pulse strength (Normal / Strong / Insane) and a manual beat delay are in Settings.

Palette comes from the artwork, with greyscale covers detected and kept grey rather than having a
hue invented for them.

## Cover art

AirPlay senders supply artwork. DLNA control points often do not, so:

1. `upnp:albumArtURI` from the DIDL-Lite document, when present
2. MusicBrainz → Cover Art Archive lookup by album and artist, falling back to album alone

Optional, off by default — a receiver reaching out to a third party should be the user's choice.
No API key required.

## Settings

PIN pairing (off / remember senders / every time) · which receivers run · device name ·
screensaver timeout · backdrop style · Beat Pulse · beat delay · volume mode ·
Back behaviour (stop stream / go home / exit) · stream-end behaviour · start on boot ·
Picture-in-picture · online cover art · playback quality

## Diagnostics

`http://<tv>:8001/` full dump, `:8002` streaming tail. Ring buffer holds events since app start —
reproduce first, then curl.
