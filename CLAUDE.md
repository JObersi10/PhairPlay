# PhairPlay — Claude Context

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Volumes/SABRENT/Applications/AndroidSDK" \
TMPDIR=/Volumes/SABRENT/tmp \
./gradlew app:compileGoogletvDebugKotlin
```

Only build the `googletv` variant — that's the one installed on the Fire TV at `192.168.1.246:5555`.

**Never run install/deploy commands.** User installs the APK manually.

## Key facts

- Fire TV at `192.168.1.246:5555` via ADB
- Installed app: `com.phairplay.googletv` (not `firetv`)
- Diagnostic HTTP server: `:8001` full dump, `:8002` streaming tail (`curl http://<ip>:8002`)
- Settings IP shown in Settings → About section

## Architecture

- `PhairPlayService` — foreground service, hosts `AirPlayReceiver`, `DiagnosticServer`
- `AirPlayReceiver` → `RtspHandler` — RTSP protocol, SET_PARAMETER for metadata/artwork
- `MainActivity` — single activity, observes `nowPlaying` StateFlow, drives `NowPlayingScreen`
- `NowPlayingScreen` — View-based left panel (art + progress bar) + Compose `LyricsPanel` on right
- `LyricsRepository` — fetches synced LRC from lrclib.net
- `DiagnosticServer` — singleton; call `stop()` before `start()` on service restart

## Lyrics sync

Apple Music AirPlay 2 (buffered, type 103) **never sends SET_PARAMETER position**. Timer starts from `t=0` when song title metadata arrives. Anchor resets on title change. If a seek does send position, anchor updates when divergence >2s.

`rememberSmoothProgressMs` in `LyricsPanel` interpolates at 60fps between 250ms ticks from `NowPlayingScreen.positionTick`.

## Known quirks

- `SET_PARAMETER` handler: volume check must come BEFORE `text/parameters` check or volume is silently dropped
- `DiagnosticServer.started` flag must be reset via `stop()` in `onDestroy()` or server won't restart after service restart
- `BoxWithConstraints` + `maxHeight.value.isFinite()` required in `LyricsPanel` to avoid LazyColumn infinite height crash in horizontal LinearLayout
- `NowPlayingInfo.equals()` includes `positionSec`/`durationSec` — StateFlow emits on every position change, which is fine (`collect` not `collectLatest`)

## User preferences

- Caveman words only — say done, nothing else
- No install commands
- No need to explain what code does
