# PhairPlay — Claude Context

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Volumes/SABRENT/Applications/AndroidSDK" \
TMPDIR=/Volumes/SABRENT/tmp \
/Volumes/SABRENT/PhairPlay/gradlew -p /Volumes/SABRENT/PhairPlay app:assembleFiretvDebug
```

**Always `firetv` flavor. Never `googletv`.**  
**Never run install/deploy commands.** User installs APK manually.

APK: `/Volumes/SABRENT/PhairPlay/app/build/outputs/apk/firetv/debug/app-firetv-debug.apk`

## Device

- Fire TV at `192.168.1.246:5555` via ADB
- App ID: `com.phairplay.firetv`
- SABRENT external drive must be mounted first

## Architecture

- `PhairPlayService` — foreground service; does NOT stop in `onDestroy` (stays alive when app closes)
- `AirPlayReceiver` → `RtspHandler` — RTSP, SET_PARAMETER for metadata/artwork
- `firetv/` source set: `CastReceiver.kt` only — reverse-engineered Cast + mDNS `_googlecast._tcp`
- `main/` source set: everything else (shared)
- `CastServer` — TLS on port 8009; Fire TV OS owns 8009, catches BindException and disables gracefully
- `DlnaServer` — SSDP multicast + UPnP HTTP port 8200; AVTransport/RenderingControl/ConnectionManager
- `SharedMediaPlayer` — ExoPlayer wrapper; all calls via main Handler; volatile state fields
- `NowPlayingScreen` — audio visualizer; triggered by AirPlay audio AND DLNA playback
- `DiagnosticServer` — `:8001` full dump, `:8002` streaming tail

## Protocols

| Protocol | Port | Status |
|----------|------|--------|
| AirPlay RTSP | 7000 | Working |
| AirPlay screen mirror | — | Working |
| Google Cast (reverse-engineered) | 8009 | Fire TV OS owns 8009 → shows DISABLED in UI |
| DLNA/UPnP MediaRenderer | 8200 | Discovery works; SOAP delivery being debugged |

## DLNA — current state (unresolved)

- SSDP discovery works (device appears in VLC/nano-dlna)
- Server logs `DLNA GET /description.xml` → response write attempted
- New logs added: `DLNA -> 200 GET /description.xml (XXB)` and `DLNA sent ... ok` to confirm delivery
- No SOAP commands seen yet (SetAVTransportURI / Play)
- Fixes already applied: pre-cache name/uuid/ip at `start()` (no lazy getters on handler threads), `client.soTimeout=15s`, `ssdpSocket.soTimeout=10s`
- Next: check if "sent ok" log appears. If yes → client rejects description.xml content. If no → write throws → check "DLNA HTTP error" log.

## Known quirks

- `SET_PARAMETER` handler: volume check must come BEFORE `text/parameters` check
- `DiagnosticServer` needs `stop()` before `start()` or it won't restart
- `BoxWithConstraints` + `maxHeight.value.isFinite()` required in `LyricsPanel` (LazyColumn crash)
- `CastReceiver.kt` must stay in `firetv/` source set only — putting it in `main/` causes Kotlin Redeclaration error
- DLNA `onNowPlayingChanged` wired in `PhairPlayService.startDlna()` → sets `_nowPlaying.value` → opens NowPlayingScreen

## Lyrics sync

Apple Music AirPlay 2 never sends SET_PARAMETER position. Timer starts from t=0 on title change. `rememberSmoothProgressMs` interpolates at 60fps between 250ms ticks.

## User preferences

- Caveman words only. Terse. Say done, nothing else.
- Never run install/deploy commands
- Only firetv flavor — never mix with googletv
