package com.phairplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import com.phairplay.DeviceFeatures
import com.phairplay.MainActivity
import com.phairplay.R
import android.view.Surface
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.airplay.DacpClient
import com.phairplay.dlna.DlnaServer
import com.phairplay.miracast.MiracastReceiver
import com.phairplay.media.AudioRouteMonitor
import com.phairplay.media.DecoderCapacity
import com.phairplay.media.DeviceVolumeController
import com.phairplay.media.MediaButtonSession
import com.phairplay.media.VolumeControlMode
import com.phairplay.settings.AppSettings
import com.phairplay.BuildConfig
import com.phairplay.settings.AudioRoute
import com.phairplay.settings.SettingsRepository
import com.phairplay.diagnostic.DiagnosticServer
import com.phairplay.diagnostic.LogBuffer
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * PhairPlayService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that PhairPlay is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class PhairPlayService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _activeMirrorSlots = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Which mirroring tiles currently have a sender on them, so the Activity can lay out that many.
     *
     * A flow OWNED BY THE SERVICE, whose identity never changes.
     *
     * The first version delegated straight to the receiver's own flow. A `StateFlow` getter is
     * evaluated once, when the collector starts — and the Activity binds before the receiver has
     * been built, so it read the null branch, subscribed to a throwaway empty flow and listened to
     * that forever. Every later slot change went to a different object. The tiles simply never
     * appeared: the second sender connected, decoded, and drew into a Surface nobody had laid out.
     */
    val activeMirrorSlots: StateFlow<Set<Int>> = _activeMirrorSlots.asStateFlow()

    private val _miracastState = MutableStateFlow(ProtocolState.DISABLED)
    val miracastState: StateFlow<ProtocolState> = _miracastState.asStateFlow()


    private val _dlnaState = MutableStateFlow(ProtocolState.DISABLED)
    val dlnaState: StateFlow<ProtocolState> = _dlnaState.asStateFlow()

    private val _videoPlaying = MutableStateFlow(false)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<com.phairplay.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<com.phairplay.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    /**
     * D-pad presses arriving from the HomeKit remote, as Android key codes.
     *
     * A SharedFlow rather than a StateFlow: these are events, and two presses of the same arrow
     * must both arrive. A StateFlow would collapse the second into the first and the remote would
     * feel like it were dropping every other press.
     *
     * `extraBufferCapacity` absorbs a burst of presses while the Activity is starting; without it
     * `tryEmit` fails on a flow with no room and the press vanishes silently.
     */
    private val _remoteKeys = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val remoteKeys = _remoteKeys.asSharedFlow()

    /** Mirror of AppSettings.remoteEnabled, so the HAP thread need not touch DataStore. */
    @Volatile private var remoteEnabled: Boolean = false

    /** Mirror of AppSettings.artworkLookup, read from the DLNA artwork thread. */
    @Volatile private var artworkLookup: Boolean = false

    /**
     * What the fingerprinter last identified, and which sender it was for.
     *
     * Held rather than merged straight into the flow because the identification arrives ten to
     * fifteen seconds after the audio started, on its own thread, and the sender keeps pushing
     * (still nameless) now-playing updates the whole time. Without somewhere to keep the answer, the
     * very next push would overwrite it and the title would flash on screen and vanish.
     */
    @Volatile private var identifiedTitle: String? = null
    @Volatile private var identifiedArtist: String? = null
    @Volatile private var identifiedFor: String? = null

    /**
     * Cover art for the identified track, as BYTES rather than the URL Shazam returned.
     *
     * [NowPlayingInfo.artwork] is a ByteArray: every other path fills it from what the sender
     * pushed, and the Now Playing card and the notification both read it directly. Handing either
     * of them a URL instead would mean teaching both to fetch, on the main thread, for one case.
     */
    @Volatile private var identifiedArtwork: ByteArray? = null

    /**
     * Keeps the fingerprinter's switch in step with the setting, for as long as the service lives.
     *
     * A COLLECTOR, NOT A READ IN startReceivers(). Everything else in this service samples the
     * settings once, with `settingsFlow.first()`, at the moment the receivers start -- which is
     * correct for the things that are only consulted while BUILDING a receiver, and silently wrong
     * for anything toggled during a session. Identification is exactly that: the switch is on the
     * Now Playing settings page, so the natural moment to reach for it is while nameless audio is
     * already playing, and a one-shot read means it does nothing until the receivers next restart.
     * That failure is invisible -- the setting shows as on, and nothing happens, with nothing in the
     * log to say why.
     */
    private fun watchIdentifySetting() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                val was = com.phairplay.media.shazam.TrackIdentifier.enabled
                com.phairplay.media.shazam.TrackIdentifier.enabled = settings.identifyTracks
                com.phairplay.media.shazam.TrackIdentifier.intervalSec = identifyInterval(settings)
                if (was == settings.identifyTracks) return@collect
                Logger.i("Shazam: identification ${if (settings.identifyTracks) "enabled" else "disabled"}")
                if (!settings.identifyTracks) {
                    clearIdentification()
                } else {
                    // Switched on mid-session: the sender will not re-announce its lack of metadata,
                    // so ask now rather than waiting for a track change that may never come.
                    _nowPlaying.value?.let { if (!it.hasMetadata) com.phairplay.media.shazam.TrackIdentifier.request() }
                }
            }
        }
    }

    /**
     * The re-check interval to actually use, which is the user's choice unless the device is saving
     * power.
     *
     * Fingerprinting is a burst of FFTs plus a network round trip, repeated on a timer -- precisely
     * the sort of background work power-save mode exists to stop. The stored setting is left alone
     * and only clamped here, so leaving power-save restores whatever was chosen without the user
     * having to set it again.
     */
    private fun identifyInterval(settings: AppSettings): Int {
        val chosen = settings.identifyIntervalSec
        val saving = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        }.getOrDefault(false)
        if (!saving) return chosen
        val floored = maxOf(chosen, AppSettings.LOW_POWER_IDENTIFY_INTERVAL_SEC)
        if (floored != chosen) {
            Logger.i("Shazam: power save is on — re-checking every ${floored}s instead of ${chosen}s")
        }
        return floored
    }

    /**
     * The AudioTrack buffer to actually use, raised to a floor when the output is Bluetooth.
     *
     * The dial in Settings is calibrated for HDMI, where 100ms is comfortable. On A2DP it is thin --
     * delivery is bursty, retransmits happen, and the radio is shared with the Wi-Fi carrying the
     * stream -- so a hiccup drains the buffer and the audio stutters. Raising the floor to what the
     * sender itself advertises as its minimum (250ms) fixes that without touching the setting, so
     * the dial keeps meaning what it says the moment the speaker goes away.
     *
     * ONLY APPLIED WHEN THE TRACK IS CREATED. AudioTrack is sized once at construction, so a
     * speaker connecting mid-session does not resize it -- that is the same reason changing the
     * setting asks for a restart. The visual compensation IS live; this cannot be.
     */
    private fun effectiveAudioBufferMs(chosen: Int): Int {
        if (routeCompensationMs <= 0) return chosen
        val floored = maxOf(chosen, AudioRoute.BLUETOOTH_MIN_BUFFER_MS)
        if (floored != chosen) {
            Logger.i("Audio buffer raised ${chosen}ms → ${floored}ms for the Bluetooth route " +
                "(the setting is unchanged)")
        }
        return floored
    }

    /** Forgets any identification and stops listening. Called wherever a session is torn down. */
    private fun clearIdentification() {
        com.phairplay.media.shazam.TrackIdentifier.cancel()
        forgetIdentification()
        identifiedFor = null
    }

    /** Drops the answer but not the listening state. */
    private fun forgetIdentification() {
        identifiedTitle = null
        identifiedArtist = null
        identifiedArtwork = null
    }

    /**
     * Rewrites a cover URL to ask for a larger rendition.
     *
     * Shazam's `coverarthq` is 400x400, which is soft on a 1080p television because the Now Playing
     * card draws artwork much larger than that. The URLs are Apple's mzstatic image service, where
     * the size is a path segment (`400x400cc.jpg`) that the server will honour at other values, so
     * asking for [ARTWORK_PIXELS] costs nothing but the larger download.
     *
     * Returns the URL unchanged when it does not match that shape -- Shazam does not promise this
     * host, and a rewritten URL that 404s is why the caller keeps the original as a fallback rather
     * than trusting this.
     */
    private fun upscaleArtworkUrl(url: String): String =
        ARTWORK_SIZE_SEGMENT.replace(url) { m ->
            "/${ARTWORK_PIXELS}x$ARTWORK_PIXELS${m.groupValues[3]}."
        }

    /**
     * Downloads cover art for an identified track.
     *
     * Bounded read: the URL comes from a third party, so its size is not ours to trust. Shazam's
     * covers are a few hundred kilobytes, and anything past the cap is abandoned rather than
     * allowed to run a television out of memory.
     *
     * Called on the identifier's own worker thread, which is why it can block: it keeps
     * [ShazamClient] a lookup rather than making it a downloader too.
     */
    private fun fetchArtwork(url: String): ByteArray? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = ARTWORK_TIMEOUT_MS
                readTimeout = ARTWORK_TIMEOUT_MS
            }
            if (conn.responseCode !in 200..299) {
                Logger.i("Shazam: cover art HTTP ${conn.responseCode}")
                return null
            }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { stream ->
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_ARTWORK_BYTES) {
                        Logger.w("Shazam: cover art over ${MAX_ARTWORK_BYTES / 1024}KB — abandoned")
                        return null
                    }
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
                ?.also { Logger.i("Shazam: cover art ${it.size} bytes") }
        } catch (e: Exception) {
            Logger.i("Shazam: cover art failed (${e.javaClass.simpleName}: ${e.message})")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Applies the sender's metadata, falling back to whatever the fingerprinter found.
     *
     * The sender ALWAYS wins. A track it names is authoritative and an identification is a guess, so
     * the guess is only ever used to fill a hole -- and it is dropped outright as soon as the sender
     * starts naming things, which is what happens when someone switches from a browser tab to Apple
     * Music without ending the session.
     */
    private fun withIdentification(
        info: com.phairplay.airplay.NowPlayingInfo,
    ): com.phairplay.airplay.NowPlayingInfo {
        // `&& !info.identified` is load-bearing. hasMetadata only means "has a title", and once an
        // identification has been applied the value in _nowPlaying carries OUR title -- so without
        // this the re-check's own result comes back through here, is mistaken for the sender naming
        // the track, and clearIdentification() wipes it and cancels the identifier. That is why
        // only the FIRST song was ever identified: the second match destroyed itself on arrival.
        if (info.hasMetadata && !info.identified) {
            clearIdentification()
            return info
        }
        // Nameless audio: ask for an identification, unless one for this sender already landed.
        if (identifiedFor != info.senderName) {
            forgetIdentification()
            com.phairplay.media.shazam.TrackIdentifier.request()
            return info
        }
        val title = identifiedTitle ?: return info
        // `info.identified` decides whose artwork this is. On a re-check the value coming in is one
        // we already identified, so info.artwork is OUR PREVIOUS COVER rather than the sender's --
        // and preferring it there froze the first song's art onto every song after it, even as the
        // title updated correctly. The sender still wins when the artwork is genuinely the sender's.
        val art = if (info.identified) identifiedArtwork ?: info.artwork else info.artwork ?: identifiedArtwork
        return info.copy(
            title = title,
            artist = identifiedArtist,
            artwork = art,
            identified = true,
        )
    }

    private fun emitRemoteKey(keyCode: Int) {
        if (!remoteEnabled) {
            Logger.i("Remote key $keyCode ignored — the remote is switched off in Settings")
            return
        }
        // System-wide first. With the accessibility service enabled the remote drives whatever is
        // actually on screen -- the launcher, Netflix, anything -- which is the point of having it.
        // Crucially we must NOT bring PhairPlay forward in that case: yanking the app to the front
        // on every arrow press would make the remote useless for controlling anything else.
        // The local-adb route used to run first and has been removed entirely. Tested on hardware
        // 2026-08-17: adbd ACCEPTS a connection from the device's own address and then never answers
        // the handshake, while answering the identical packet from another host in milliseconds. Our
        // probe read that silence as "waiting for CNXN, as expected", sent the banner, and blocked
        // for READ_TIMEOUT_MS. A timeout is not RefusedByDaemon, so the permanent-refusal latch never
        // set and every press paid it again. Because HAP invokes write handlers on the hap-conn
        // thread and sends its 204 only after they return, that stalled the whole accessory: the
        // Home app showed the TV as off and the iPhone remote became unusable. Dead route, high cost.

        // Root where it exists. It runs the same `input keyevent` adb does, through the real
        // input pipeline, so it works in apps that ignore accessibility focus -- which is most of the
        // interesting ones. Unrooted devices (nearly all of them) skip straight past this.
        if (com.phairplay.util.RootShell.sendKeyEvent(keyCode)) return

        if (PhairPlayAccessibilityService.sendKey(keyCode)) return

        // With the accessibility service enabled, a key we could not deliver means the CURRENT app
        // had nowhere to send it -- not that the user wanted PhairPlay. Falling through used to drag
        // the app to the front: the log shows OK on the Fire TV home screen landing on
        // "ServiceController: start()" and PhairPlay taking over the screen. The remote exists to
        // drive the television, so a press that lands nowhere lands nowhere.
        if (PhairPlayAccessibilityService.isConnected) {
            Logger.i("Remote key $keyCode not deliverable to the foreground app — ignoring")
            return
        }

        // Without it, the honest scope is our own window, and the app has to be visible for a key
        // aimed at it to mean anything.
        bringAppToFront()
        if (!_remoteKeys.tryEmit(keyCode)) {
            Logger.w("Remote key $keyCode dropped — no collector and the buffer is full")
        }
    }

    /** True when the remote can drive the whole device rather than just PhairPlay's own window. */
    fun isSystemWideRemoteEnabled(): Boolean = PhairPlayAccessibilityService.isConnected

    private val _audioEnergy = MutableStateFlow(0f)
    val audioEnergy: StateFlow<Float> = _audioEnergy.asStateFlow()

    /** Bass/mid/treble, 0..1. Drives one orb each in the projector backdrop. */
    private val _audioBands = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val audioBands: StateFlow<FloatArray> = _audioBands.asStateFlow()

    // Non-null while a PIN should be shown on screen for SRP pair-setup (PIN access control).
    // Latest sender volume change, with what we managed to do about it — surfaced so the UI can
    // report the real level and route rather than implying more control than we have.
    private val _volumeReport = MutableStateFlow<DeviceVolumeController.VolumeReport?>(null)
    val volumeReport: StateFlow<DeviceVolumeController.VolumeReport?> = _volumeReport.asStateFlow()

    /** Most recent sender name + when, for the "Last connected" line on the waiting card. */
    data class LastSender(val name: String, val atMs: Long)

    private val _lastSender = MutableStateFlow<LastSender?>(null)
    val lastSender: StateFlow<LastSender?> = _lastSender.asStateFlow()

    private val deviceVolume by lazy { DeviceVolumeController(applicationContext) }

    /** HomeKit accessory. Null unless the user enabled it — pairing joins their Home, so it is opt-in. */
    private var homeKit: com.phairplay.homekit.HomeKitReceiver? = null

    /** App shortcuts as of the last HomeKit start, in slot order. See [launchInputApp]. */
    @Volatile private var homeKitInputApps: List<String> = emptyList()
    @Volatile private var senderVolumeMode: VolumeControlMode = VolumeControlMode.OFF

    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    // Surface provider — supplied by MainActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: ((Int) -> Surface?)? = null

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null
    private var audioRouteMonitor: AudioRouteMonitor? = null
    private var miracastReceiver: MiracastReceiver? = null
    private var dlnaServer: DlnaServer? = null

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    // Kept awake only while a session is live — see acquireStreamLocks().
    private var wakeLock: PowerManager.WakeLock? = null
    // Held for as long as any receiver is advertising — see acquireWifiLock().
    private var wifiLock: WifiManager.WifiLock? = null

    // Network watcher — re-advertises after the Wi-Fi drops (deep sleep) so senders don't chase a
    // stale mDNS record. See registerNetworkWatcher().
    /**
     * Serialises receiver startup.
     *
     * Both the Activity and the service's own onCreate promotion fire ACTION_START, so two
     * startReceivers() coroutines could run at once. The per-receiver "already running" guards
     * check a field that neither coroutine had assigned yet, so both sailed past: two Miracast
     * registrations, two mDNS records, and two RTSP handlers fighting over port 7000 — twelve
     * 250ms retries, about three seconds added to every connect, with DLNA losing 8200 outright.
     */
    private val receiverLock = kotlinx.coroutines.sync.Mutex()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastKnownIp: String? = null

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("PhairPlayService created")
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
        registerNetworkWatcher()
        registerDisplayWatcher()
        // Keep HomeKit's input tile honest when the user opens an app themselves. Only our own
        // launches used to report, so switching apps with the physical remote left the Home app
        // showing whatever it last selected.
        PhairPlayAccessibilityService.onForegroundApp = { pkg -> reportForegroundApp(pkg) }
        DiagnosticServer.statusProvider = ::diagnosticStatus
        DiagnosticServer.start(serviceScope)
        watchIdentifySetting()
        startAudioRouteWatcher()
        // A BIND_AUTO_CREATE bind creates this service WITHOUT delivering onStartCommand, so nothing
        // starts the receivers and the service dies as soon as the last client unbinds — seen as
        // "created" then "destroying" 200ms later, with no "Starting receivers" between them. That
        // left the receiver running only while the Activity was on screen, so a sender could not
        // connect in the background. Promote any bind-created instance to a properly started
        // foreground service; onStartCommand is idempotent and the receivers guard against
        // duplicate starts.
        ContextCompat.startForegroundService(
            this,
            Intent(this, PhairPlayService::class.java).setAction(ACTION_START)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents. Cleanly stop all receivers (which closes the RTSP
     * connection so an active mirror ends on the sender too) and stop the service — don't let
     * START_STICKY silently resurrect it as a zombie that keeps advertising/streaming invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service alive after task removal so AirPlay keeps advertising in the background.
        Logger.i("App task removed — service continues in background")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [MainActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [startAirPlay]. The lambda should return null
     * if the Activity's StreamingScreen is not yet available (e.g., surface not yet created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    /** Where per-tile decoded sizes go, so each tile letterboxes to its own stream. */
    @Volatile private var videoSizeSink: ((Int, Int, Int) -> Unit)? = null

    fun setVideoSizeSink(sink: (slot: Int, width: Int, height: Int) -> Unit) {
        videoSizeSink = sink
    }

    fun setVideoSurfaceProvider(provider: (Int) -> Surface?) {
        videoSurfaceProvider = provider
    }

    /**
     * Asks the bound Activity to put its video Surface on screen before a session exists.
     *
     * A SurfaceView only has a Surface once it is visible, and the overlay is normally made visible
     * in response to CONNECTED — by which point the sender has already sent the single IDR it will
     * emit for the next several seconds. Missing that IDR is what left a cold first mirroring
     * attempt black until the user disconnected and reconnected.
     */
    @Volatile private var onPrepareSurface: (() -> Unit)? = null

    /**
     * A sender opened the socket before the Activity had finished binding, so there was nobody to
     * ask for a Surface. Remembered rather than dropped — see [setSurfacePreparer].
     */
    @Volatile private var surfacePrepareMissed = false

    fun setSurfacePreparer(prepare: () -> Unit) {
        onPrepareSurface = prepare
        // Binding is asynchronous and a sender can beat it. On a fresh launch the user often opens
        // the app and connects within a few seconds, and until now that meant onSenderApproaching
        // fired against a null preparer and the pre-warm was silently lost: no visible SurfaceView,
        // no Surface, and the opening IDR decoded off-screen. Replay the missed request instead.
        if (surfacePrepareMissed) {
            surfacePrepareMissed = false
            Logger.i("Surface preparer registered after the sender arrived — preparing now")
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { prepare() } }
        }
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    /** Media-button owner, alive only while something is streaming. */
    private var mediaButtons: MediaButtonSession? = null

    private fun updateMediaButtons(streaming: Boolean) {
        if (streaming) {
            val s = mediaButtons ?: MediaButtonSession(applicationContext) { cmd ->
                dispatchTransportCommand(cmd)
            }.also { mediaButtons = it }
            s.setPlaying(true)
        } else {
            mediaButtons?.release()
            mediaButtons = null
        }
    }

    /**
     * Routes a transport command from the TV remote to whatever is actually playing.
     *
     * These are two completely different situations and sending both down the AirPlay path -- which
     * is what this used to do -- meant the DLNA case silently did nothing as well:
     *
     *  - DLNA: WE are the player. ExoPlayer is in this process, so pause/play/stop are direct calls
     *    and simply work.
     *  - AirPlay: the SENDER is the player and we have to ask it. The only back-channel we have is
     *    DACP, and AirPlay 2 senders do not offer one -- measured, see the DACP notes in CLAUDE.md.
     *    So this reaches legacy RAOP senders that advertise a DACP identity and nothing else, which
     *    is why the buttons appear dead against a modern iPhone. Fixing that means implementing
     *    MediaRemote; the log line below is here so the distinction is visible in a capture rather
     *    than being guessed at.
     */
    fun dispatchTransportCommand(command: String) {
        val player = dlnaServer?.mediaPlayer
        if (_dlnaState.value == ProtocolState.CONNECTED && player != null) {
            when (command) {
                DacpClient.CMD_PLAY_RESUME -> player.play()
                DacpClient.CMD_PAUSE -> player.pause()
                DacpClient.CMD_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                else -> Logger.i("Transport '$command' has no DLNA equivalent — ignored")
            }
            return
        }
        if (_airPlayState.value == ProtocolState.CONNECTED) {
            sendAirPlayRemoteCommand(command)
            return
        }
        Logger.i("Transport '$command' dropped — nothing is streaming")
    }

    /**
     * Ends the active AirPlay session (Back pressed while streaming) without stopping the service.
     * Restarting the receiver drops the RTSP connection, which tells the sender that mirroring
     * ended, then re-advertises so the device is immediately pickable again.
     */
    /**
     * Dismisses the AirPlay pairing code. Clears the flow even when no receiver is around to ask,
     * so the overlay always goes away — a stuck PIN with no exit is worse than a stale SRP session.
     */
    fun cancelPinPairing() {
        Logger.i("Cancelling PIN pairing on user request")
        airPlayReceiver?.cancelPinPairing()
        _pairingPin.value = null
    }

    /**
     * Stops whichever protocol is streaming, other than [keep].
     *
     * AirPlay and DLNA render to the same audio output, so two live sessions play over each other.
     * Nothing arbitrated between them: a DLNA cast during an AirPlay stream simply added itself,
     * and Back then ended only one of the two.
     */
    private fun endOtherSession(keep: Protocol) {
        if (keep != Protocol.DLNA && _dlnaState.value == ProtocolState.CONNECTED) {
            Logger.i("Ending DLNA render — ${keep.name} is taking over")
            runCatching { dlnaServer?.endSession() }
        }
        if (keep != Protocol.AIRPLAY && _airPlayState.value == ProtocolState.CONNECTED) {
            Logger.i("Ending AirPlay session — ${keep.name} is taking over")
            runCatching { airPlayReceiver?.endSession() }
        }
    }

    fun endCurrentSession() {
        Logger.i("Ending current session on user request")
        // Drop just this sender. Restarting every receiver took mDNS down and put it straight back,
        // which the sender treated as an invitation to reconnect — Back appeared to do nothing on
        // the phone. Fall back to a restart only if AirPlay isn't the thing that's streaming.
        // Whatever is actually playing is what Back has to end. This asked the AirPlay receiver and
        // nothing else — and the AirPlay receiver is non-null whenever AirPlay is merely *enabled*,
        // so a DLNA render took the first branch, ended an AirPlay session that wasn't running, and
        // left the music playing with the screen already gone.
        var ended = false
        if (dlnaServer != null && _dlnaState.value == ProtocolState.CONNECTED) {
            Logger.i("Ending DLNA render")
            dlnaServer?.endSession()
            ended = true
        }
        val receiver = airPlayReceiver
        if (receiver != null && _airPlayState.value == ProtocolState.CONNECTED) {
            receiver.endSession()
            ended = true
        }
        if (!ended) serviceScope.launch { restartReceivers() }
    }

    /**
     * Holds the CPU awake for the duration of a session. Fire TV suspends the CPU aggressively when
     * nothing is on screen, which stalls the decoder mid-mirror and glitches audio — symptoms that
     * look like network problems but are the SoC dozing. Scoped to an active session rather than the
     * whole service so an idle receiver isn't pinning the CPU all day.
     */
    /**
     * Turns the display on when a session starts.
     *
     * The session wake lock is PARTIAL, which by definition cannot wake the screen — so connecting
     * to a sleeping Fire TV played audio into a dark room and never showed the now-playing card.
     * A short SCREEN_BRIGHT lock with ACQUIRE_CAUSES_WAKEUP is the only mechanism a background
     * service has to do this; it is deprecated but still honoured, and released quickly so Fire OS
     * resumes its normal display timeout once FLAG_KEEP_SCREEN_ON has taken over in the Activity.
     */
    @Suppress("DEPRECATION")
    private fun wakeDisplay() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_DISPLAY_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_DISPLAY_MS)
            }
            Logger.i("Display woken for incoming session")
        }.onFailure { Logger.w("Could not wake display: ${it.message}") }
    }

    private fun acquireStreamLocks() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MS)   // safety timeout: never leak the lock if a release is missed
        }
        Logger.d("Wake lock acquired for session")
        wakeDisplay()
    }

    private fun releaseStreamLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    /**
     * Keeps Wi-Fi out of power-save for as long as we advertise. Without it the radio parks between
     * beacons and mDNS queries get dropped, so the TV intermittently vanishes from the AirPlay picker.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        Logger.d("Wi-Fi lock acquired")
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    /**
     * Watches for the Wi-Fi link changing underneath us and re-advertises when it does.
     *
     * Fire TV drops Wi-Fi in deep sleep and re-associates on wake, often on a new DHCP lease. The
     * NsdManager registrations from the previous link stay in place but nothing answers queries for
     * them, so a phone sees a cached entry, fails to connect, and only succeeds on a retry — which
     * is what forced the connect/disconnect dance after the TV had been asleep.
     *
     * Only restarts when the IP actually changed and no session is live, so an active mirror is
     * never torn down by a routine network event.
     */
    private fun registerNetworkWatcher() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        lastKnownIp = currentWifiIp()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = checkForIpChange("network available")
            override fun onLost(network: Network) {
                Logger.i("Network lost — will re-advertise when it returns")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                checkForIpChange("capabilities changed")
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Logger.e("Could not register network watcher", it) }
    }

    private fun checkForIpChange(reason: String) {
        val ip = currentWifiIp() ?: return
        if (ip == lastKnownIp) return
        val previous = lastKnownIp
        lastKnownIp = ip
        if (_activeConnection.value != null) {
            Logger.i("IP changed $previous -> $ip during an active session ($reason) — not restarting")
            return
        }
        Logger.i("IP changed $previous -> $ip ($reason) — restarting receivers to re-advertise")
        serviceScope.launch { restartReceivers() }
    }

    private fun currentWifiIp(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION") val raw = wm.connectionInfo.ipAddress
        if (raw == 0) return null
        return "%d.%d.%d.%d".format(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff)
    }

    private fun unregisterNetworkWatcher() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    /**
     * Mirrors the TV's real power state into HomeKit's Active characteristic.
     *
     * Active used to change only when something wrote it — the Home app's own button, or the start
     * of a stream. So the tile said whatever it had last been told, and a TV that had since gone to
     * sleep, or been woken with the physical remote, kept reporting the stale value. The Home app
     * was not wrong; nothing had ever told it.
     *
     * ACTION_SCREEN_ON/OFF is the closest thing Fire OS gives a normal app to "is the TV in use".
     * It tracks the display rather than the panel's backlight, which is the right notion here: a
     * sleeping Fire TV stick reports screen-off even while the TV itself is on another input.
     *
     * These two broadcasts cannot be declared in the manifest — the system only delivers them to
     * receivers registered at runtime — which is why this lives here rather than in the manifest
     * alongside the boot receiver.
     */
    private fun registerDisplayWatcher() {
        if (displayWatcher != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.content.Intent.ACTION_SCREEN_ON -> reportDisplayActive(true)
                    android.content.Intent.ACTION_SCREEN_OFF -> reportDisplayActive(false)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess {
                displayWatcher = receiver
                // Seed from the current state: waiting for the first transition would leave the
                // tile stale until the user happened to sleep or wake the device.
                reportDisplayActive(isDisplayInteractive())
                Logger.i("HomeKit: display watcher registered")
            }
            .onFailure { Logger.w("HomeKit: could not watch display state — ${it.message}") }
    }

    private fun unregisterDisplayWatcher() {
        displayWatcher?.let { runCatching { unregisterReceiver(it) } }
        displayWatcher = null
    }

    private fun isDisplayInteractive(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    /**
     * Maps a foregrounded package onto its HomeKit input, if the user mapped one.
     *
     * Apps with no slot are ignored rather than guessed at: reporting an identifier HomeKit does not
     * know about would leave the tile showing nothing at all, which is worse than showing the last
     * real input.
     */
    private fun reportForegroundApp(pkg: String) {
        val slot = homeKitInputApps.indexOfFirst { it == pkg }
        if (slot < 0) return
        val identifier = AppSettings.inputAppIdentifier(slot)
        Logger.i("Foreground app $pkg → HomeKit input $identifier")
        homeKit?.reportInput(identifier)
    }

    private fun reportDisplayActive(on: Boolean) {
        if (lastReportedActive == on) return
        // The accessory does not exist yet during onCreate, so the seeding call lands before there
        // is anything to tell. Leaving lastReportedActive unset in that case means the next real
        // transition still reports, instead of being suppressed as a duplicate of a value HomeKit
        // was never given.
        val receiver = homeKit ?: return
        lastReportedActive = on
        Logger.i("HomeKit: display ${if (on) "on" else "off"} — reporting Active=$on")
        receiver.reportActive(on)
    }

    private var displayWatcher: android.content.BroadcastReceiver? = null
    private var lastReportedActive: Boolean? = null

    override fun onDestroy() {
        Logger.i("PhairPlayService destroying")
        unregisterNetworkWatcher()
        unregisterDisplayWatcher()
        DiagnosticServer.statusProvider = null
        audioRouteMonitor?.stop()
        audioRouteMonitor = null
        PhairPlayAccessibilityService.onForegroundApp = null
        stopAllReceiversInternal()
        DiagnosticServer.stop()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Audio route ─────────────────────────────────────────────────────────

    /**
     * Compensates for a Bluetooth speaker automatically, without making it the user's problem.
     *
     * A Bluetooth speaker is late by roughly [AudioRoute.BLUETOOTH_COMPENSATION_MS], and Android
     * reports none of it — `AudioTrack.getTimestamp()`, which `AudioStreamServer.outputLatencyMs()`
     * already consults, stops at the HAL, which is the moment audio leaves the box and well before
     * the encoder, the radio link and the speaker's own jitter buffer. So it cannot be measured, and
     * it does not need to be asked about either: it is a property of the transport, present whenever
     * the transport is and gone the moment it isn't.
     *
     * It is therefore held entirely outside [AppSettings]. The user's Audio delay setting reads 0
     * with a Bluetooth speaker connected, because 0 extra is what they have actually chosen; the 350
     * underneath it is ours. Connect a speaker and the visuals slide back; disconnect and they
     * snap forward, with nothing to tune and nothing to undo.
     *
     * The visuals move rather than the audio, because the audio is the side that is already late.
     */
    private fun startAudioRouteWatcher() {
        val monitor = AudioRouteMonitor(applicationContext).also { audioRouteMonitor = it }
        monitor.start()
        serviceScope.launch {
            monitor.route.collect { route ->
                if (route.key == AudioRoute.UNKNOWN.key) return@collect
                routeCompensationMs = route.compensationMs
                // Live: the compensation has to follow a speaker that connects mid-track, not wait
                // for the next session. Safe because it moves the beat callback only -- the audio
                // trim is pre-buffered as silence at stream start and cannot move without a gap.
                airPlayReceiver?.setBeatDelayMs(routeCompensationMs)
                // Same number, second consumer. The compensation is a property of the OUTPUT, so
                // everything the user perceives as "in sync with the sound" owes it -- the beat
                // visuals and the mirrored picture alike. Only the audio itself is exempt, because
                // the audio is the side that is already late.
                airPlayReceiver?.setVideoDelayMs(routeCompensationMs)
                Logger.i(
                    "Audio route: ${route.label} — compensating ${routeCompensationMs}ms " +
                        "(user audio delay is separate and unchanged)"
                )
                runCatching {
                    settingsRepository.update {
                        it.copy(
                            currentAudioRoute = route.label,
                            currentRouteCompensationMs = routeCompensationMs,
                        )
                    }
                }
                    .onFailure { Logger.w("Audio route: could not record the output name - ${it.message}") }
            }
        }
    }

    /**
     * Milliseconds of visual delay owed to the current output. Not a setting, never persisted.
     *
     * Held here so a receiver built after the route was detected starts with the right value rather
     * than at zero until the next route change — which, for a speaker that was already connected
     * when the app launched, would be never.
     */
    @Volatile private var routeCompensationMs: Int = 0

    /**
     * The two facts worth having at the top of a dump: which build this is, and where the sound is
     * actually going. Both otherwise scroll out of the ring buffer long before anyone reads it.
     */
    private fun diagnosticStatus(): String {
        val route = audioRouteMonitor?.route?.value
        val where = when {
            route == null || route.key == AudioRoute.UNKNOWN.key -> "not yet detected"
            routeCompensationMs > 0 -> "${route.label} (${route.key}) — compensating ${routeCompensationMs}ms"
            else -> "${route.label} (${route.key}) — no compensation"
        }
        return buildString {
            appendLine("---- PHAIRPLAY ----")
            appendLine("build:  ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA} (${BuildConfig.BUILD_TYPE})")
            appendLine("output: $where")
            // The ceiling on multi-screen casting, and cheap enough to re-read that there is no
            // reason to guess at it from a phone in another room.
            append("decode: ${DecoderCapacity.maxConcurrentAvcDecoders()} concurrent H.264")
        }
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() = receiverLock.withLock {
        acquireWifiLock()
        val settings = settingsRepository.settingsFlow.first()
        // onCreate and onStart both call ServiceController.start(), and both are meant to: binding
        // alone never delivers onStartCommand, so dropping either one leaves the receiver tied to
        // the Activity's lifetime. The duplicate reaching here is expected and the per-receiver
        // "already running" guards handle it -- but logging it at info level three times a session
        // made a normal path look like a fault, so the arrival is only worth a line when it
        // actually changes something.
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, Miracast=${settings.miracastEnabled}, DLNA=${settings.dlnaEnabled}")

        senderVolumeMode = settings.senderVolumeMode
        remoteEnabled = settings.remoteEnabled
        artworkLookup = settings.artworkLookup
        // `enabled` is NOT set here -- watchIdentifySetting() owns it, so that toggling the switch
        // during a session takes effect immediately instead of at the next receiver restart.
        com.phairplay.media.shazam.TrackIdentifier.onIdentified = { match ->
            // Recorded against the sender that was playing when the lookup finished. If the session
            // ended in the meantime the name is simply never used -- better than attaching a track
            // to whoever connected next.
            identifiedFor = _nowPlaying.value?.senderName
            identifiedTitle = match.title
            identifiedArtist = match.artist
            identifiedArtwork = match.artworkUrl?.let { url ->
                // Ask for a bigger rendition first. Shazam hands back a 400px cover, which is soft
                // on a 1080p television -- the card draws it far larger than that.
                fetchArtwork(upscaleArtworkUrl(url)) ?: fetchArtwork(url)
            }
            Logger.i("Shazam: naming this stream \"${match.title}\"" +
                (match.artist?.let { " — $it" } ?: "") + " for sender ${identifiedFor ?: "(gone)"}")
            _nowPlaying.value?.let { current -> _nowPlaying.value = withIdentification(current) }
        }
        com.phairplay.media.shazam.TrackIdentifier.onCleared = {
            // The audio went quiet for long enough that whatever was named is over. Drop the name
            // rather than leave it sitting under silence, and re-render so the card follows.
            Logger.i("Shazam: dropping the identified name")
            forgetIdentification()
            identifiedFor = null
            _nowPlaying.value?.let { current -> _nowPlaying.value = withIdentification(current) }
        }
        // Switching the remote off must also clear what it drew and remembered, not just stop new
        // presses — otherwise a ring stays on screen over an app that never asked for one.
        if (!remoteEnabled) PhairPlayAccessibilityService.resetRemoteState()
        if (settings.lastSenderName.isNotBlank()) {
            _lastSender.value = LastSender(settings.lastSenderName, settings.lastSenderAtMs)
        }
        _serviceState.value = ServiceState.Running
        updateNotification(isRunning = true)

        if (settings.airPlayEnabled)   startAirPlay(settings)
        // The stored preference can still be true from a Google TV install or an older build; the
        // flavour constant is the authority on whether the hardware can finish a WFD session.
        if (settings.miracastEnabled && DeviceFeatures.MIRACAST_SUPPORTED) startMiracast()
        if (settings.dlnaEnabled)      startDlna()
        if (settings.homeKitEnabled)   startHomeKit(settings)
    }

    /**
     * Starts the HomeKit accessory.
     *
     * Separate from the streaming receivers on purpose: it advertises a different service, holds a
     * persistent identity, and its failure must not take AirPlay down with it — a HomeKit problem
     * should cost HomeKit, not the thing the user actually bought this for.
     */
    private fun startHomeKit(settings: com.phairplay.settings.AppSettings) {
        if (homeKit != null) {
            Logger.i("HomeKit already running — skipping duplicate start")
            return
        }
        val bridge = HomeKitBridge(
            context = applicationContext,
            onEndSession = { endCurrentSession() },
            onBringToFront = { bringAppToFront() },
            onWakeDisplay = { wakeDisplay() },
            onSendRemoteCommand = { cmd -> airPlayReceiver?.sendRemoteCommand(cmd) },
            onNavKey = { keyCode -> emitRemoteKey(keyCode) },
            onLaunchInputApp = { identifier -> launchInputApp(identifier) },
        )
        // Snapshot for launchInputApp, which runs later on a HAP connection thread and has no
        // settings of its own. Kept in slot order so an identifier still maps to the right app.
        homeKitInputApps = settings.inputApps
        runCatching {
            com.phairplay.homekit.HomeKitReceiver(
                context = applicationContext,
                actions = bridge,
                // Same name AirPlay advertises, not Build.MODEL. These disagreed whenever the user
                // left the display name blank: AirPlay fell back to the device's friendly name
                // ("Living room Fire TV") while HomeKit fell back to the model code ("AFTKM"), so
                // the Home app showed a different device from the AirPlay picker.
                deviceName = {
                    settings.effectiveDisplayName
                        .ifBlank { com.phairplay.util.NetworkUtils.getDeviceName(this) }
                },
                extraInputs = inputAppEntries(settings),
                remoteEnabled = settings.remoteEnabled,
            ).also {
                it.start()
                homeKit = it
                // Seed Active from the real display state now that there is an accessory to tell.
                // The watcher registered in onCreate had nothing to report to at the time.
                reportDisplayActive(isDisplayInteractive())
            }
        }.onFailure { Logger.e("HomeKit failed to start (streaming is unaffected)", it) }
    }

    /**
     * The user's app shortcuts as HomeKit inputs, labelled with each app's real name.
     *
     * Apps that are no longer installed are dropped rather than shown as a dead entry — an input
     * that cannot launch anything is worse than one that isn't offered.
     */
    private fun inputAppEntries(settings: AppSettings): List<Pair<Int, String>> {
        val pm = packageManager
        return settings.inputApps.mapIndexedNotNull { index, pkg ->
            if (pkg.isBlank()) return@mapIndexedNotNull null
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrElse {
                Logger.i("HomeKit input slot $index: $pkg is not installed — leaving it out")
                return@mapIndexedNotNull null
            }
            AppSettings.inputAppIdentifier(index) to label
        }
    }

    /**
     * Launches the app mapped to a HomeKit input.
     *
     * @return true if something was launched, so the caller knows not to fall back to showing
     *   PhairPlay — the point of the shortcut is to leave PhairPlay.
     */
    private fun launchInputApp(identifier: Int): Boolean {
        val slot = AppSettings.inputAppSlot(identifier) ?: return false
        val pkg = homeKitInputApps.getOrNull(slot)?.takeIf { it.isNotBlank() } ?: return false
        // Prefer the TV launcher entry: on Fire TV an app's phone-style launcher activity is often
        // absent or a stub, and getLaunchIntentForPackage picks that one.
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Logger.w("HomeKit input $identifier: $pkg has no launchable activity")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            wakeDisplay()
            startActivity(intent)
            Logger.i("HomeKit input $identifier → launched $pkg")
            // Tell the Home app which input is live. Without this the tile kept showing whatever
            // was last selected THERE, so launching an app from the Home app left the Home app
            // itself out of date about what it had just done.
            homeKit?.reportInput(identifier)
            true
        }.getOrElse {
            Logger.w("HomeKit input $identifier: could not launch $pkg — ${it.message}")
            false
        }
    }

    private fun stopHomeKit() {
        homeKit?.let { runCatching { it.stop() } }
        homeKit = null
    }

    /** Clears HomeKit pairings so the accessory can be added to a different Home. */
    fun resetHomeKitPairings() {
        homeKit?.resetPairings()
    }

    /** The pairing code to show the user, or null when HomeKit is off. */
    fun homeKitSetupCode(): String? = homeKit?.setupCode

    /** True once a controller has completed pair-setup; the code is no longer useful then. */
    fun isHomeKitPaired(): Boolean = homeKit?.isPaired == true

    /** The `X-HM://` URI behind the setup QR code, or null while HomeKit is off. */
    fun homeKitPairingUri(): String? = homeKit?.pairingUri

    /** The name the Home app lists this accessory under, which is not the AirPlay display name. */
    fun homeKitAccessoryName(): String? = homeKit?.accessoryName

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        stopAllReceiversInternal()
        _serviceState.value = ServiceState.Stopped
        _activeConnection.value = null
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        // HomeKit SURVIVES a receiver restart.
        //
        // It did not, and that was doing real damage. Every restart tore down the HAP server and
        // brought it back on a fresh port, killing every controller connection mid-flight. Worse,
        // turning the TV off from the Home app routes through endCurrentSession, which falls back
        // to a restart when no stream is running -- so the Home app's own command destroyed the
        // server it had just sent that command to, and then had nothing left to receive the state
        // change on. The tile sitting on a stale value and the iPad's remote picker being
        // unreliable are both downstream of this.
        Logger.i("Restarting streaming receivers (HomeKit stays up)")
        _serviceState.value = ServiceState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal(includeHomeKit = false)
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = { slot -> videoSurfaceProvider?.invoke(slot) },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            // Wake the screen and warm the Surface the moment a sender opens the socket, so it is
            // already there when video arrives ~half a second later.
            //
            // This deliberately does NOT launch the Activity. "Safe to fire on a probe" was wrong:
            // an iPhone opens this socket every time its AirPlay picker is shown, and each of those
            // probes threw PhairPlay in front of whatever the user was watching. The session start
            // (ProtocolState.CONNECTED, below) is the honest moment to take the screen, and it
            // already does.
            onSenderApproaching = {
                wakeDisplay()
                // Runs on an RTSP socket thread; view work has to hop to the main looper.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val prepare = onPrepareSurface
                    if (prepare == null) {
                        surfacePrepareMissed = true
                        Logger.i("Sender arrived before the Activity bound — surface prep deferred")
                    } else {
                        runCatching { prepare() }
                    }
                }
            },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            // Authoritative, straight from the receiver — see the guesses removed below.
            onVideoPlayingChanged = { playing -> _videoPlaying.value = playing },
            onNowPlayingChanged = { rawInfo ->
                val info = rawInfo?.let { withIdentification(it) }
                _nowPlaying.value = info
                if (info != null) {
                    val name = info.senderName.takeIf { it.isNotBlank() }
                    // The name known at CONNECTED time is the RTSP User-Agent fallback ("AirPlay").
                    // The sender's actual device name only arrives later in the now-playing plist,
                    // so re-remember here — otherwise the Home card stays stuck on "AirPlay".
                    if (name != null) rememberSender(name)
                    updateNotification(isRunning = true, streamingSenderName = name, artworkBytes = info.artwork)
                }
            },
            onEnergyChanged = { e -> _audioEnergy.value = e },
            onBandsChanged = { b -> _audioBands.value = b },
            onPinChanged = { pin ->
                _pairingPin.value = pin
                // The code is useless if nobody can see it. Pairing happens before CONNECTED, so
                // waiting for the session to start meant a sleeping or home-screen Fire TV showed
                // nothing while the sender sat waiting for a PIN.
                if (pin != null) { wakeDisplay(); bringAppToFront() }
            },
            rememberPinPairing = settings.rememberPinPairing,
            audioDelayMs = settings.audioDelayMs,
            audioBufferMs = effectiveAudioBufferMs(settings.audioBufferMs),
            beatDelayMs = routeCompensationMs,
            // The FLAG opts in; the HARDWARE decides how far. Asking for more streams than the
            // device can decode would hand a sender a session that negotiates cleanly and then
            // never shows a picture — strictly worse than the immediate refusal it replaces.
            onMirrorSlotsChanged = { slots -> _activeMirrorSlots.value = slots },
            onMirrorSizeChanged = { slot, w, h -> videoSizeSink?.invoke(slot, w, h) },
            maxSessions = if (settings.multiScreen) {
                minOf(DecoderCapacity.maxConcurrentMirrors(), AirPlayReceiver.MAX_SLOTS)
            } else {
                1
            },
            onVolumeRequest = { db -> applySenderVolume(db) },
            onStateChanged = { state ->
                _airPlayState.value = state
                // Own the transport keys while streaming, and only while streaming. Fire OS routes
                // PLAY/PAUSE to the active MediaSession rather than to the focused Activity, which
                // is why the Activity's key handler never saw one.
                updateMediaButtons(state == ProtocolState.CONNECTED)
                // Only while a stream is up is there an AudioTrack to ask, and its answer beats
                // the connected-device guess: this Fire TV reports HDMI and its built-in speaker as
                // permanently present, so which of them is playing is otherwise an inference.
                audioRouteMonitor?.setRoutedDevice(
                    if (state == ProtocolState.CONNECTED) airPlayReceiver?.routedAudioDevice() else null
                )
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        // See endOtherSession: AirPlay and DLNA share one audio output.
                        endOtherSession(Protocol.AIRPLAY)
                        _photoFrame.value = null
                        // A connected session with no now-playing metadata is a mirror/video
                        // stream. Setting this here (not only from onNowPlayingChanged) is what
                        // lets onUserLeaveHint enter PiP for screen mirroring — without it the
                        // Activity backgrounds, the SurfaceView destroys its Surface, and the
                        // rebuilt decoder stalls on awaitingKeyframe until the sender sends an IDR.
                        acquireStreamLocks()
                        Logger.i("Volume capability: ${deviceVolume.describeCapability(senderVolumeMode)}")
                        rememberSender(pendingSenderName)
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                        bringAppToFront()
                        // Keep the Home app tile honest: a session started from the phone should
                        // show the TV as on, not leave HomeKit reporting whatever it last set.
                        homeKit?.reportActive(true)
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR       -> {
                        // Reset the overlay drivers on disconnect. _videoPlaying had no reset path
                        // at all, so once true the Activity's updateOverlay() kept choosing
                        // showStreamingScreen() forever — a black SurfaceView with nothing decoding.
                        releaseStreamLocks()
                        _videoPlaying.value = false
                        // Deliberately NOT reportActive(false): ending a stream does not turn the
                        // TV off, and saying so made the Home tile flip to "off" while the user was
                        // sitting in front of a lit screen. Active follows the display now — see
                        // registerDisplayWatcher.
                        // ONLY clear the shared now-playing state if it is still AirPlay's.
                        //
                        // _nowPlaying and _activeConnection are shared by both protocols, and the
                        // DLNA handover runs in exactly the wrong order to survive an unguarded
                        // reset: the control point publishes its metadata, then reports CONNECTED,
                        // which calls endOtherSession -> AirPlay endSession -> this branch, which
                        // wiped the DLNA track that had just been set. The Activity keeps the last
                        // non-null value it saw, so the card sat there still showing the AirPlay
                        // sender for the whole DLNA render -- "the now playing screen doesn't
                        // switch". Whoever owns the connection owns the right to clear it.
                        if (_activeConnection.value?.protocol != Protocol.DLNA) {
                            _nowPlaying.value = null
        clearIdentification()
                            _photoFrame.value = null
                            _activeConnection.value = null
                        }
                        updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
        ).also { it.start() }
        // The route watcher's flow only re-emits when the ROUTE changes, so a receiver built while
        // a Bluetooth speaker was already connected would have started uncompensated and stayed
        // that way for the whole session. Seed it from the value we already hold.
        airPlayReceiver?.setVideoDelayMs(routeCompensationMs)
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    /**
     * Routes a sender volume change to the output device when the current route can follow it.
     *
     * @return true if the hardware took it, so [AirPlayReceiver] leaves its software gain at unity.
     */
    /**
     * Persists who just connected so the waiting card can say "Last connected: X" instead of a bare
     * "Waiting for sender…" — the quickest way to tell at a glance that the receiver is really live.
     */
    private fun rememberSender(name: String) {
        if (name.isBlank()) return
        if (name == _lastSender.value?.name) return
        // Within one session the generic fallback always arrives first and the real name second.
        // Refuse the downgrade so a late generic emission can't undo the upgrade; across sessions
        // (no active connection) a generic name is the best we have and is allowed through.
        val isGeneric = name in GENERIC_SENDER_NAMES
        if (isGeneric && _activeConnection.value != null &&
            _lastSender.value?.name?.let { it !in GENERIC_SENDER_NAMES } == true) {
            return
        }
        val now = System.currentTimeMillis()
        _lastSender.value = LastSender(name, now)
        serviceScope.launch {
            settingsRepository.update { it.copy(lastSenderName = name, lastSenderAtMs = now) }
        }
    }

    /** Clears every stored pairing and the PIN-trust marker (Settings → Forget paired senders). */
    fun forgetPairedSenders() {
        com.phairplay.airplay.handshake.PairingStore(applicationContext).clearAll()
        Logger.i("Forgot all paired senders — PIN will be required again")
    }

    private fun applySenderVolume(db: Float): Boolean {
        val report = deviceVolume.apply(db, senderVolumeMode)
        _volumeReport.value = report
        Logger.i("Sender volume ${db}dB -> ${report.display}")
        return report.appliedToDevice
    }

    private fun startMiracast() {
        if (miracastReceiver != null) {
            Logger.i("Miracast receiver already running — skipping duplicate start")
            return
        }
        _miracastState.value = ProtocolState.ADVERTISING
        miracastReceiver = MiracastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _miracastState.value = state }
        ).also { it.start() }
        Logger.d("Miracast receiver started")
    }

    private fun startDlna() {
        // Without this guard a second startReceivers() — which every auto-open triggers, because
        // MainActivity.onCreate calls ServiceController.start() — rebound port 8200, failed with
        // EADDRINUSE, and then overwrote `dlnaServer` with the dead instance. The working server was
        // orphaned: still serving, but no longer reachable by stop() or restart.
        if (dlnaServer != null) {
            Logger.i("DLNA server already running — skipping duplicate start")
            return
        }
        _dlnaState.value = ProtocolState.ADVERTISING
        dlnaServer = DlnaServer(
            context = applicationContext,
            onStateChanged = { state ->
                _dlnaState.value = state
                if (state == ProtocolState.CONNECTED) {
                    // One sender at a time. Starting a DLNA render while AirPlay was streaming left
                    // BOTH playing -- two audio sources mixed together, and neither stoppable from
                    // the sender that was no longer on screen. The protocols share one output, so
                    // taking over means taking over.
                    endOtherSession(Protocol.DLNA)
                    bringAppToFront()
                    _activeConnection.value = ActiveConnection("DLNA", Protocol.DLNA)
                } else {
                    _nowPlaying.value = null
        clearIdentification()
                    _activeConnection.value = null
                }
            },
            // DLNA control points always name what they are playing, so there is nothing here for
            // the fingerprinter to fill in.
            onNowPlayingChanged = { info -> _nowPlaying.value = info },
            artworkLookupEnabled = { artworkLookup },
        ).also {
            // Drop the reference on a failed bind so the guard above doesn't treat a dead server as
            // running, and a later restart gets a clean retry.
            if (!it.start()) dlnaServer = null
        }
    }

    /**
     * @param includeHomeKit false leaves the HomeKit accessory running across the teardown.
     *   HomeKit shares nothing with the streaming receivers -- different port, different identity,
     *   different lifetime -- so restarting AirPlay has never been a reason to drop it.
     */
    private fun stopAllReceiversInternal(includeHomeKit: Boolean = true) {
        releaseStreamLocks()
        releaseWifiLock()
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { miracastReceiver?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { dlnaServer?.stop() } catch (e: Exception) { Logger.e("DLNA stop error", e) }
        airPlayReceiver = null
        miracastReceiver = null
        dlnaServer = null
        if (includeHomeKit) stopHomeKit()
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _dlnaState.value = ProtocolState.DISABLED
        _photoFrame.value = null
        _nowPlaying.value = null
        clearIdentification()
        _pairingPin.value = null
        _videoPlaying.value = false
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_description) })
            // High-importance channel required for full-screen intents (auto-open on connect)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_INCOMING,
                "AirPlay Connection",
                NotificationManager.IMPORTANCE_HIGH
            ))
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null,
        artwork: Bitmap? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setLargeIcon(artwork)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun bringAppToFront() {
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            // Tells MainActivity we opened it, so it hands the screen back when the session ends.
            putExtra(MainActivity.EXTRA_OPENED_BY_SENDER, true)
        }

        // Try a direct start first. A full-screen intent alone is NOT enough: Android only
        // launches its activity when the screen is locked — on an awake Fire TV it degrades to a
        // heads-up notification, which is why the app never actually opened on connect.
        // The direct start needs a background-activity-launch exemption (SYSTEM_ALERT_WINDOW);
        // if that isn't granted we fall through to the notification below.
        val started = runCatching { startActivity(launch); true }.getOrElse {
            Logger.w("Direct activity start refused (no background-launch exemption) — using full-screen intent")
            false
        }

        // The direct start worked — don't also fire the heads-up popup on top of the app.
        if (started) return

        val pi = PendingIntent.getActivity(
            this, 99, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_status_running))
            .setFullScreenIntent(pi, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID_INCOMING, n)
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null, artworkBytes: ByteArray? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val bitmap = artworkBytes?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText, bitmap))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [PhairPlayService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): PhairPlayService = this@PhairPlayService
    }

    companion object {
        /** Cap on cover art fetched from Shazam. Its covers are a few hundred KB. */
        private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
        private const val ARTWORK_TIMEOUT_MS = 8000

        /** Rendition asked for in [upscaleArtworkUrl]. 800 is sharp at the card's drawn size. */
        private const val ARTWORK_PIXELS = 800

        /** `/400x400cc.` in an mzstatic URL — the size, and any suffix letters before the dot. */
        private val ARTWORK_SIZE_SEGMENT = Regex("""/(\d{2,4})x(\d{2,4})([a-z-]*)\.""")

        const val CHANNEL_ID          = "phairplay_service_channel"
        const val CHANNEL_ID_INCOMING = "phairplay_incoming_channel"
        const val NOTIFICATION_ID          = 1001
        const val NOTIFICATION_ID_INCOMING = 1002
        const val ACTION_START    = "com.phairplay.action.START"
        const val ACTION_STOP     = "com.phairplay.action.STOP"
        const val ACTION_RESTART  = "com.phairplay.action.RESTART"

        /**
         * Placeholder names derived from the RTSP User-Agent rather than the sender itself. They
         * are all we know until the now-playing plist arrives with the real device name.
         */
        private val GENERIC_SENDER_NAMES = setOf("AirPlay", "AirPlay Sender", "iTunes")

        private const val WAKE_LOCK_TAG = "PhairPlay:session"
        private const val WAKE_DISPLAY_TAG = "PhairPlay:wake"
        /** Just long enough to bring the panel up; FLAG_KEEP_SCREEN_ON keeps it there. */
        private const val WAKE_DISPLAY_MS = 5_000L
        private const val WIFI_LOCK_TAG = "PhairPlay:advertising"
        /** Safety timeout on the session wake lock — 8h is longer than any real session. */
        private const val MAX_SESSION_MS = 8L * 60 * 60 * 1000
    }
}

/**
 * PhotoFrame — latest still image received via AirPlay `/photo`.
 *
 * The bytes are kept in memory only and cleared on DELETE `/photo`, streaming
 * start, receiver stop, or service destruction.
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
