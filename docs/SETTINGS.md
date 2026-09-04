# Settings

Every setting in PhairPlay, what it does, and what it costs. Grouped as they appear on screen.

Settings is one continuous list; the column on the left jumps to a section rather than hiding the
others, so anything here can also be found by scrolling.

---

## General

| Setting | Default | What it does |
|---|---|---|
| **Device name** | System device name | The name senders see in their AirPlay picker. If two receivers on the network share a name, the picker shows both identically — rename one. |
| **Start on boot** | Off | Starts the receiver when the TV powers on, without opening the app. |
| **Back button** | Stop the stream | What Back does while a stream is playing: **Stop the stream** (ends it, stays in PhairPlay), **Go home** (leaves the app, stream keeps playing), or **Exit** (stops the service and quits). One ordered choice, not two switches — the two-switch version could be set to contradict itself. |
| **Exit when the stream ends** | Off | Leaves for the TV home screen as soon as the sender stops. Off keeps PhairPlay on screen, ready for the next stream. |
| **Picture-in-picture** | On | Shrinks mirrored video into a corner when you leave the app instead of ending it. |

## Protocols

Each can be turned off independently; turning one off frees its port and stops it advertising.

| Setting | Default | What it does |
|---|---|---|
| **AirPlay** | On | RTSP on port 7000: mirroring, audio, and AirPlay video. The main event. |
| **Miracast** | On | Wi-Fi Direct + WFD on 7236, for Android senders. Needs location permission on API < 33. Advertises, but this Fire TV cannot complete a session — the card is hidden on hardware where it cannot work. |
| **DLNA / UPnP** | On | MediaRenderer on 8200, including GENA eventing. What most Android media apps speak. |
| **HomeKit** | Off | Adds PhairPlay to the Home app as a TV, which enables the iPhone remote and Siri. |
| **Remote control** | Off | Lets the TV remote drive the sender over DACP. Reliable inside PhairPlay; unreliable in other apps, which is why it is off by default. See **Permissions**. |

## Audio & video

| Setting | Default | What it does |
|---|---|---|
| **Mirror audio** | On | Plays the audio that accompanies a screen mirror. Off mirrors silently. |
| **High resolution** | Off | Requests 2560×1440 instead of 1920×1080 for mirroring. More detail, more bandwidth, more decode load. |
| **Audio buffer** | 100 ms | How much audio is held before playing. Lower is more responsive and more likely to stutter on a weak network; higher is the reverse. 40–300 ms. **Raised to a floor of 250 ms whenever the output is Bluetooth** — A2DP delivery is bursty and shares an antenna with the Wi-Fi carrying the stream, and 250 ms is what senders themselves advertise as their minimum (`latencyMin=11025` at 44100 Hz). The setting is not overwritten, only floored, so it means what it says again the moment the speaker goes away. Applied when the AudioTrack is created, so a speaker connecting mid-session does not resize it. |
| **Audio delay** | 0 ms | Holds audio back to meet the sender's timeline. Yours to set; the beat visuals follow it automatically. **A Bluetooth speaker gets 350 ms of extra *visual* delay on top of this, automatically** — it is a property of the transport rather than a preference, so it is applied rather than asked about, and it disappears when the speaker does. See [FEATURES.md](FEATURES.md). |
| **Volume control** | Software only | Whether the sender's volume slider moves the real device volume. Defaults to software gain because Fire OS accepts a volume change and silently drops it — `setStreamVolume` returns success and nothing gets quieter. |

## Now Playing

| Setting | Default | What it does |
|---|---|---|
| **Backdrop** | Dynamic | **Dynamic** — album-coloured blobs riding bass, vocals and treble. **Projector** — three orbs on true black, edges faded so a projected image has no visible border. **Black** — nothing, and the redraw loop stops entirely rather than painting black 60 times a second. |
| **Beat Pulse** | Normal | How hard the backdrop reacts: Calm / Normal / Strong / Insane. Applied to the drawing rather than to the level, so turning it up keeps adding movement instead of flattening everything against the top of the range. |
| **Screensaver** | On | Dims to a drifting card on black when nothing changes, and pixel-shifts for burn-in protection. |
| **Screensaver timeout** | 15 min | Minutes of no remote input or track change before it starts. |
| **Online cover art** | Off | Looks up missing artwork by title via MusicBrainz and the Cover Art Archive. **Sends track names to those services while on**, which is why it is off by default. Needs no API key. |
| **Identify unknown tracks** | Off | When a sender streams audio without naming it, captures twelve seconds and asks Shazam what it is. Never runs when the sender supplies metadata, and the sender's own data always wins. **Sends an audio fingerprint — not audio — to Shazam while on**, hence off by default. See [TRACK_IDENTIFICATION.md](TRACK_IDENTIFICATION.md). |
| **Re-check what is playing** | Every 30s | How often to look the track up again while nameless audio plays. 12s ("continuously"), 15s, 30s, 60s. Nameless audio gives no track-change signal, so this is the only thing that keeps the name up with the music — worst case staleness is the interval plus the twelve-second capture. Floored at 30s while the device is in power-save mode. Takes effect immediately; no restart. |

## Pairing & security

| Setting | Default | What it does |
|---|---|---|
| **Require PIN** | Off | Shows a code on the TV that must be entered before a sender can connect. |
| **Remember senders** | On | A sender that has entered the PIN once is not asked again. |
| **Forget pairings** | — | Clears every remembered sender. They will be asked for the PIN again. |

## Permissions

Optional. PhairPlay streams without any of them; each unlocks one behaviour.

| Setting | What it buys |
|---|---|
| **Remote control access** | Accessibility service, so the remote can drive the sender. Granted over ADB. |
| **Display over other apps** | Lets the Now Playing card and PIN overlay appear above other apps. |
| **Keep awake** | Stops the TV sleeping mid-stream. |
| **Input apps** | Up to three apps offered as HDMI-style inputs on the home screen. |

## About

| Setting | What it does |
|---|---|
| **Version** | App version and the git commit it was built from. Quote both in bug reports — the version name does not change between development builds, so the commit is the part that identifies your build. |
| **Check for updates** | Asks GitHub Releases for a newer version. Manual only: nothing checks in the background and nothing installs itself. See [UPDATE_CHECKER.md](UPDATE_CHECKER.md). |
| **Debug overlay** | Live FPS, queue depth and drop counters while streaming. |
| **Reset to defaults** | Returns every setting above to its default. Does not forget pairings. |
| **Quit** | Stops the service and exits. The receiver stops advertising. |

---

## Diagnostics

Not a setting, but the fastest way to answer "what is it actually doing":

```bash
curl -s http://<tv-ip>:8001/     # full dump, led by build and current audio route
curl -s http://<tv-ip>:8002/     # streaming tail
```

The buffer only holds events since the app started and it is small — reproduce first, then curl.
