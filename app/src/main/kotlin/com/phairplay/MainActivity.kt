package com.phairplay

import android.app.PictureInPictureParams
import android.graphics.Rect
import androidx.annotation.RequiresApi
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Rational
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.service.PhairPlayService
import com.phairplay.service.PhotoFrame
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.airplay.DacpClient
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.settings.BackAction
import com.phairplay.settings.SettingsRepository
import com.phairplay.ui.HomeFragment
import com.phairplay.ui.MirrorControls
import com.phairplay.ui.OnboardingFragment
import com.phairplay.ui.NowPlayingScreen
import com.phairplay.ui.PhotoScreen
import com.phairplay.ui.PinScreen
import com.phairplay.ui.SettingsFragment
import com.phairplay.ui.StreamingScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainActivity — The single Activity hosting PhairPlay's navigation and fragments.
 *
 * WHY: PhairPlay uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. PhairPlayService is started on app launch.
 */
class MainActivity : AppCompatActivity() {

    // UI references
    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout

    // The SurfaceView for full-screen video output
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private lateinit var pinScreen: PinScreen
    private lateinit var mirrorControls: MirrorControls

    // Service binding — gives access to state flows for showing/hiding the streaming overlay
    private var service: PhairPlayService? = null
    private var isBound = false
    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null
    private var currentPin: String? = null
    private var currentVideoPlaying = false

    /** Cached copy of AppSettings.backAction — onBackPressed can't suspend to read DataStore. */
    private var backAction = BackAction.STOP_STREAM

    /** Which screen this session owns, latched until it ends. */
    private enum class Mode { NONE, AUDIO, VIDEO }
    private var sessionMode = Mode.NONE
    /** Last metadata seen, so an audio session keeps its card when the sender stops sending any. */
    private var lastNowPlaying: NowPlayingInfo? = null

    /** Cached AppSettings.pipEnabled — checked from onUserLeaveHint, which can't suspend. */
    private var pipEnabled = true
    private var isSeekActive = false

    // True while a stream is on screen — used to detect the active→idle edge in trackSessionEnd().
    private var hadActiveSession = false
    // True when PhairPlayService auto-opened us for an incoming sender, so we know to hand the
    // screen back when that session ends. A manual launch leaves this false.
    private var openedBySender = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { getVideoSurface() }

            // Show/hide the full-screen overlay for video streams and photos.
            observeOverlayState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Timber.d("MainActivity: unbound from PhairPlayService")
        }
    }

    // Currently selected nav item index (0 = Home, 1 = Settings)
    private var selectedNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")
        openedBySender = intent?.getBooleanExtra(EXTRA_OPENED_BY_SENDER, false) == true
        bindViews()
        setupOverlayScreens()
        setupNavigation()

        applyNowPlayingSettings()

        // Start the service immediately so it's running before any sender discovers us
        ServiceController.start(this)

        if (savedInstanceState == null) {
            // On a first run the onboarding flow owns the permission prompts, so the bare runtime
            // request must not fire underneath it — the user would get an unexplained system dialog
            // on top of the page that was about to explain it.
            lifecycleScope.launch {
                val settings = SettingsRepository(this@MainActivity).settingsFlow.first()
                if (settings.onboardingComplete) {
                    navigateTo(HomeFragment(), navItemHome)
                    requestNotificationPermission()
                } else {
                    showOnboarding()
                }
            }
        }
    }

    /**
     * Shows the first-run flow over the whole content area, hiding the nav panel so there is no way
     * to wander off mid-setup.
     */
    private fun showOnboarding() {
        navPanelVisible(false)
        val fragment = OnboardingFragment().also { f ->
            f.onFinished = {
                navPanelVisible(true)
                navigateTo(HomeFragment(), navItemHome)
                requestNotificationPermission()
                // The receivers started in onCreate, seconds before onboarding wrote the user's
                // answers, so they are still running on pre-onboarding defaults — a chosen PIN
                // showed up in DataStore while the RTSP handler kept logging pinAuth=false. Restart
                // so every choice on the preferences page actually takes effect now, not on the next
                // launch. Same for the screensaver and high-resolution settings.
                Timber.i("Onboarding finished — restarting receivers to pick up chosen settings")
                ServiceController.restart(this)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    private fun navPanelVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        navItemHome.visibility = v
        navItemSettings.visibility = v
    }

    /**
     * launchMode="singleTop" means an auto-open while we're already running is delivered here
     * rather than through onCreate, so the flag has to be picked up in both places.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPENED_BY_SENDER, false)) {
            openedBySender = true
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-read on every foregrounding: the user may have just changed these in Settings.
        // Settings are collected continuously from onCreate — no re-read needed here.
        // Start before binding. BIND_AUTO_CREATE on its own creates a bound-only service that never
        // receives onStartCommand and dies at unbind, so the receiver only lived while this Activity
        // was on screen. Both calls are idempotent.
        ServiceController.start(this)
        // Bind so we can observe StateFlows and supply the video Surface
        val intent = Intent(this, PhairPlayService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Clear surface reference before unbinding to avoid holding a dead Surface
        service?.setVideoSurfaceProvider { null }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // On API 31+ the system auto-enters PiP from setAutoEnterEnabled, so this path is only the
        // fallback for older releases. Entering again on 31+ would animate twice.
        if (pipEnabled && currentVideoPlaying &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    /**
     * PiP parameters for the video surface.
     *
     * `setAutoEnterEnabled` hands the transition to the system, which animates from the real video
     * bounds instead of snapping — the jump-cut you get from calling `enterPictureInPictureMode`
     * yourself on API 31+. `setSourceRectHint` tells it which bounds those are; without it the
     * animation scales from the whole window and the video appears to leap.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        val bounds = Rect()
        if (streamingScreen.getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
            builder.setSourceRectHint(bounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipEnabled && currentVideoPlaying)
        }
        return builder.build()
    }

    /** Re-publishes PiP params whenever the thing they describe changes. */
    private fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back closes the track-info panel before it touches the session — otherwise opening the
        // credits and pressing Back would kill the stream instead of just closing the card.
        if (nowPlayingScreen.visibility == View.VISIBLE && nowPlayingScreen.dismissInfoPanel()) return

        // While a stream is on screen, Back ends the AirPlay session rather than navigating away
        // and leaving the sender still mirroring to an app the user just left.
        if (currentVideoPlaying || currentNowPlaying != null ||
            currentAirPlayState == ProtocolState.CONNECTED
        ) {
            when (backAction) {
                BackAction.EXIT_APP -> {
                    Timber.d("Back during session, action=EXIT_APP — ending session and quitting")
                    service?.endCurrentSession()
                    ServiceController.stop(this)
                    finishAndRemoveTask()
                }
                BackAction.GO_HOME -> {
                    // moveTaskToBack backgrounds the task without finishing it, so the sender keeps
                    // playing and the service can bring us forward again when something changes.
                    Timber.d("Back during session, action=GO_HOME — backgrounding, session continues")
                    moveTaskToBack(true)
                }
                BackAction.STOP_STREAM -> {
                    Timber.d("Back during session, action=STOP_STREAM — ending AirPlay session")
                    service?.endCurrentSession()
                }
            }
            return
        }
        // No stream on screen. Only EXIT_APP has anything left to do — the other two have already
        // happened by the time you are back on the waiting screen.
        if (backAction == BackAction.EXIT_APP) {
            Timber.d("Back with action=EXIT_APP — stopping service and finishing task")
            ServiceController.stop(this)
            finishAndRemoveTask()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("MainActivity destroyed — service keeps running in background")
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews() {
        navItemHome       = findViewById(R.id.nav_item_home)
        navItemSettings   = findViewById(R.id.nav_item_settings)
        contentContainer  = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the StreamingScreen (SurfaceView for video) and adds it to the
     * streaming_container. Created eagerly so the Surface is ready before streaming starts.
     */
    private fun setupOverlayScreens() {
        streamingScreen = StreamingScreen(this)
        photoScreen = PhotoScreen(this)
        nowPlayingScreen = NowPlayingScreen(this).also {
            it.onPlayPauseClick = { nowPlayingScreen.togglePause() }
            it.onPrevClick     = { service?.sendAirPlayRemoteCommand(com.phairplay.airplay.DacpClient.CMD_PREV) }
            it.onNextClick     = { service?.sendAirPlayRemoteCommand(com.phairplay.airplay.DacpClient.CMD_NEXT) }
        }
        pinScreen = PinScreen(this)
        mirrorControls = MirrorControls(this).also {
            it.onStopClick = {
                Timber.d("Mirror controls: stop — ending session")
                service?.endCurrentSession()
            }
            it.onPipClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { enterPictureInPictureMode(pipParams()) }
                        .onFailure { e -> Timber.w(e, "PiP from mirror controls failed") }
                }
            }
        }
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        streamingContainer.addView(pinScreen)
        // Added last so it draws over the video surface rather than under it.
        streamingContainer.addView(mirrorControls)
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    /**
     * Stops the app's own UI from taking input while a session owns the screen.
     *
     * WHY: the streaming overlay is only drawn on top — the HomeFragment underneath kept its
     * focusable buttons, so during AirPlay audio the D-pad still walked an invisible Home page and
     * a click could start or stop the service behind the now-playing card.
     *
     * BLOCK_DESCENDANTS is what actually does it. Setting `isFocusable = false` on the container
     * alone does not stop focus reaching its children.
     */
    private fun setOverlayOwnsInput(owns: Boolean) {
        contentContainer.descendantFocusability =
            if (owns) FrameLayout.FOCUS_BLOCK_DESCENDANTS else FrameLayout.FOCUS_AFTER_DESCENDANTS
        navItemHome.isFocusable = !owns
        navItemSettings.isFocusable = !owns
        // Swallow stray touches so nothing beneath the full-screen overlay is clickable either.
        streamingContainer.isClickable = owns
    }

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                navigateTo(HomeFragment(), navItemHome)
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                navigateTo(SettingsFragment(), navItemSettings)
            }
        }

        // Set initial selected state
        setNavSelected(navItemHome, true)
        setNavSelected(navItemSettings, false)
    }

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        // Update nav highlight
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1

        // Replace fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Updates the nav panel item's visual state.
     *
     * @param item     The nav item TextView.
     * @param selected True if this item is currently active.
     */
    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }

    /**
     * Shows the full-screen streaming overlay (called by PhairPlayService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        // The bar stays hidden until the user asks for it; this only makes it available.
        mirrorControls.setPipAvailable(pipEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        setOverlayOwnsInput(true)
    }

    fun showPhotoScreen(photoFrame: PhotoFrame) {
        if (photoScreen.showPhoto(photoFrame.bytes)) {
            streamingScreen.visibility = View.GONE
            nowPlayingScreen.visibility = View.GONE
            pinScreen.visibility = View.GONE
            photoScreen.visibility = View.VISIBLE
            streamingContainer.visibility = View.VISIBLE
            streamingContainer.bringToFront()
            mirrorControls.hideBar()
            setOverlayOwnsInput(true)
        }
    }

    /** Shows the audio-only now-playing card (AirPlay audio with no video). */
    fun showNowPlayingScreen(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        // Audio sessions get NowPlayingScreen's own transport row, not the mirroring bar.
        mirrorControls.hideBar()
        setOverlayOwnsInput(true)
    }

    /**
     * Hides the streaming overlay and returns to the normal app UI.
     * Called when a stream ends.
     */
    fun hideStreamingScreen() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        // Hide the SurfaceView itself, not just its container. A SurfaceView holds the last frame
        // the decoder wrote and lockCanvas can't be used to wipe it while MediaCodec owns it, so
        // leaving it VISIBLE meant the final mirrored image stayed on screen. Going GONE releases
        // the Surface; the next session rebuilds the decoder against the new one, which is the same
        // path already taken when the app backgrounds.
        streamingScreen.visibility = View.GONE
        streamingContainer.visibility = View.GONE
        mirrorControls.hideBar()
        // Hand the app's own UI back its focus, or the user would be left on a Home page that
        // ignores the remote.
        setOverlayOwnsInput(false)
    }

    /** Returns the SurfaceView Surface for the VideoDecoder. */
    fun getVideoSurface() = streamingScreen.getSurface()

    /**
     * Routes TV-remote media keys to the AirPlay sender (DACP reverse control) while audio-only or a
     * stream is showing — so the remote can play/pause/skip what the Mac/iPhone is streaming. Returns
     * false for other keys so normal navigation is unaffected.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Handle Back here rather than relying on onBackPressed(). That path goes through
        // OnBackPressedDispatcher, where a fragment or the overlay can swallow the event before the
        // Activity sees it — which is why both Back settings appeared to do nothing.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            // Back closes the controls before it means anything else, matching how it already
            // closes the now-playing info panel first.
            if (mirrorControls.hideBar()) return true
            @Suppress("DEPRECATION")
            onBackPressed()
            return true
        }

        // ─── Video sessions (screen mirroring and AirPlay URL video) ────────────────────────
        //
        // Split from audio deliberately. The two have different natural mappings: on a video the
        // D-pad is for the on-screen bar, and the dedicated media keys drive the sender's
        // transport. On audio the D-pad is free, so it gets the scrubbing.
        if (sessionMode == Mode.VIDEO && streamingContainer.visibility == View.VISIBLE) {
            if (mirrorControls.isShowing()) {
                // The bar owns the D-pad while it is up — let focus search drive the buttons.
                if (isNavigationKey(keyCode)) return super.onKeyDown(keyCode, event)
            } else if (isNavigationKey(keyCode) ||
                keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                keyCode == android.view.KeyEvent.KEYCODE_INFO
            ) {
                // Menu/Info are the other "show me what I can do here" keys on TV remotes.
                mirrorControls.reveal()
                return true
            }

            // Media keys keep working either way, so the remote can drive playback without
            // bringing the bar up over the picture at all.
            val videoCommand = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> DacpClient.CMD_PLAY_PAUSE
                // Every media key on the remote skips. Fire TV remotes label these ⏪/⏩ and send
                // REWIND/FAST_FORWARD, not PREVIOUS/NEXT — there is no separate track button — so
                // both spellings have to mean skip or the physical buttons do the wrong thing.
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_VOLUME_UP   -> DacpClient.CMD_VOLUME_UP
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> DacpClient.CMD_VOLUME_DOWN
                else -> null
            }
            if (videoCommand != null) {
                service?.sendAirPlayRemoteCommand(videoCommand)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        val overlayActive = currentNowPlaying != null || currentAirPlayState == ProtocolState.CONNECTED
        if (overlayActive) {
            // Any remote press counts as presence — restart the Now Playing idle countdown.
            nowPlayingScreen.notifyActivity()

            // Menu (and Info on some remotes) flips the now-playing card over to its credits side.
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                keyCode == android.view.KeyEvent.KEYCODE_INFO
            ) {
                if (nowPlayingScreen.visibility == View.VISIBLE) return nowPlayingScreen.toggleInfoPanel()
            }

            // Audio mapping: D-pad left/right scrubs *within* the track, the dedicated media
            // keys change track. The D-pad is the one control every TV remote has, and scrubbing
            // is the thing you reach for most while listening — so it gets the good buttons.
            //
            // DACP has no "seek by N seconds": beginrew/beginff start a continuous seek that runs
            // until playresume stops it. Holding the key therefore scrubs and releasing it plays
            // on (see onKeyUp), while a quick tap lands as a short nudge. That is also why the
            // release is what resumes, rather than a second press.
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (isSeekActive) {
                        isSeekActive = false
                        DacpClient.CMD_PLAY_RESUME
                    } else {
                        nowPlayingScreen.togglePause()
                        DacpClient.CMD_PLAY_PAUSE
                    }
                }
                // Media keys skip tracks. Fire TV remotes label these ⏪/⏩ and send
                // REWIND/FAST_FORWARD rather than PREVIOUS/NEXT, so both spellings mean skip —
                // seeking lives on the D-pad, which is where it was asked for.
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (event?.repeatCount == 0) beginSeek(DacpClient.CMD_FF) else null
                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (event?.repeatCount == 0) beginSeek(DacpClient.CMD_REW) else null
                android.view.KeyEvent.KEYCODE_VOLUME_UP   -> DacpClient.CMD_VOLUME_UP
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> DacpClient.CMD_VOLUME_DOWN
                else -> null
            }
            if (command != null) {
                service?.sendAirPlayRemoteCommand(command)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Ends a hold-to-seek started in [onKeyDown]. DACP seeks run until they are told to stop, so
     * without this a single tap of D-pad right would scrub to the end of the track.
     */
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (isSeekActive && keyCode in SEEK_KEYS) {
            isSeekActive = false
            service?.sendAirPlayRemoteCommand(DacpClient.CMD_PLAY_RESUME)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Marks a seek as running so [onKeyUp] knows to stop it, and returns the command to send. */
    private fun beginSeek(command: String): String {
        isSeekActive = true
        return command
    }

    /** D-pad directions and OK — the keys the on-screen controls consume during a video session. */
    private fun isNavigationKey(keyCode: Int): Boolean = keyCode in NAVIGATION_KEYS

    /**
     * Pushes the user's screensaver preferences into [NowPlayingScreen]. Read here rather than in
     * the screen itself so the view stays free of DataStore and coroutine plumbing.
     */
    private fun applyNowPlayingSettings() {
        // Collect rather than read once. SettingsFragment is a fragment swap, not a new Activity, so
        // onStart never fires after the user flips a toggle — a one-shot read left "Back exits
        // PhairPlay" stuck on whatever it was when the app launched, which is why toggling it
        // appeared to do nothing. The flow also keeps the screensaver config live for free.
        lifecycleScope.launch {
            SettingsRepository(this@MainActivity).settingsFlow.collectLatest { settings ->
                nowPlayingScreen.setScreensaverConfig(
                    settings.screensaverEnabled, settings.screensaverTimeoutMin
                )
                // Cached because onBackPressed is synchronous and can't await DataStore.
                backAction = settings.backAction
                pipEnabled = settings.pipEnabled
                refreshPipParams()
                nowPlayingScreen.setBeatPulse(settings.beatPulse)
                // Sender-requested latency (250ms) plus the user's A/V trim, so the elapsed time
                // reflects what is coming out of the speakers rather than what has been received.
                nowPlayingScreen.setPresentationLatency(BASE_LATENCY_MS + settings.audioDelayMs)
            }
        }
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * On older versions the permission is granted automatically with the manifest declaration.
     */
    private fun requestNotificationPermission() {
        val wanted = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }
        // Wi-Fi Direct permissions are Miracast's alone. Asking for them on a build that never
        // starts Miracast means a location prompt — the scariest one we show — bought nothing.
        if (DeviceFeatures.MIRACAST_SUPPORTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The modern, location-free way to ask for Wi-Fi Direct. Only exists from API 33.
                wanted += android.Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                // Below API 33 the Wi-Fi P2P APIs are gated on location instead. Miracast silently
                // refuses to register its P2P service without one of the two, which is why the
                // receiver logged "missing Wi-Fi Direct permission" on every start — the manifest
                // declared them but nothing ever asked the user.
                wanted += android.Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        Timber.d("Requesting runtime permissions: $missing")
        ActivityCompat.requestPermissions(
            this, missing.toTypedArray(), PERMISSION_REQUEST_NOTIFICATIONS
        )
    }

    /**
     * Miracast reads its permission state only when it starts, so a grant that arrives after the
     * service is already up has no effect until the receivers are restarted.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_NOTIFICATIONS) return
        val granted = permissions.indices.filter {
            grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED
        }.map { permissions[it] }
        Timber.d("Permission result — granted: $granted")
        if (granted.any {
                it == android.Manifest.permission.ACCESS_FINE_LOCATION ||
                    it == android.Manifest.permission.NEARBY_WIFI_DEVICES
            }
        ) {
            Timber.i("Wi-Fi Direct permission granted — restarting receivers so Miracast registers")
            ServiceController.restart(this)
        }
    }

    companion object {
        /** Latency every AirPlay sender asks for in SETUP latencyMin: 11025 samples @44.1kHz. */
        private const val BASE_LATENCY_MS = 250

        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001

        /** Keys that reveal (and then drive) the on-screen controls during a video session. */
        private val NAVIGATION_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_ENTER,
        )

        /** Keys whose release ends a hold-to-seek. */
        private val SEEK_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        /** Set by PhairPlayService when it opens this Activity for an incoming sender. */
        const val EXTRA_OPENED_BY_SENDER = "com.phairplay.extra.OPENED_BY_SENDER"
    }

    // ─── Streaming overlay ────────────────────────────────────────────────────

    /**
     * Observes [PhairPlayService.airPlayState] and [PhairPlayService.photoFrame]
     * and shows the appropriate full-screen overlay.
     *
     * Called once after the service is bound. The coroutine is automatically cancelled
     * by [lifecycleScope] when the Activity stops.
     */
    private fun observeOverlayState() {
        val svc = service ?: return
        lifecycleScope.launch {
            svc.airPlayState.collectLatest { state ->
                currentAirPlayState = state
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.photoFrame.collectLatest { frame ->
                currentPhotoFrame = frame
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.nowPlaying.collect { info ->
                currentNowPlaying = info
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.videoPlaying.collectLatest { playing ->
                currentVideoPlaying = playing
                refreshPipParams()
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.pairingPin.collectLatest { pin ->
                currentPin = pin
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.audioEnergy.collect { e -> nowPlayingScreen.setEnergy(e) }
        }
        lifecycleScope.launch {
            svc.volumeReport.collect { r -> nowPlayingScreen.setVolumeReport(r?.display) }
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin
        when {
            // PIN pairing (access control) happens before streaming — show the code over everything.
            pin != null -> showPinScreen(pin)
            // Mode is latched for the whole session. Metadata comes and goes mid-track, and
            // reacting to each change dropped an audio session onto an empty black video surface.
            sessionMode == Mode.VIDEO -> showStreamingScreen()
            sessionMode == Mode.AUDIO -> showNowPlayingScreen(nowPlaying ?: lastNowPlaying!!)
            photoFrame != null -> showPhotoScreen(photoFrame)
            else -> hideStreamingScreen()
        }
        // Latch the mode on the first frame of evidence, clear it when the session ends.
        if (nowPlaying != null) lastNowPlaying = nowPlaying
        val connected = currentAirPlayState == ProtocolState.CONNECTED
        if (!connected && photoFrame == null) { sessionMode = Mode.NONE; lastNowPlaying = null }
        else if (sessionMode == Mode.NONE) {
            sessionMode = if (nowPlaying != null) Mode.AUDIO
                          else if (currentVideoPlaying) Mode.VIDEO else Mode.NONE
        } else if (sessionMode == Mode.VIDEO && nowPlaying != null && !currentVideoPlaying) {
            sessionMode = Mode.AUDIO
        }

        val sessionActive = pin != null || currentVideoPlaying || nowPlaying != null ||
                            photoFrame != null || currentAirPlayState == ProtocolState.CONNECTED
        keepScreenAwake(sessionActive)
        trackSessionEnd(sessionActive)
    }

    /**
     * Holds off Fire TV's own screensaver while a session is on screen.
     *
     * The service's wake lock is PARTIAL_WAKE_LOCK, which keeps the CPU running but deliberately
     * lets the display sleep — so during AirPlay audio or DLNA playback, where nothing is being
     * drawn to a video surface, Fire OS would blank the screen and start its screensaver over a
     * perfectly live session. FLAG_KEEP_SCREEN_ON is the only thing that suppresses it, and it is
     * cleared the moment the session ends so idle PhairPlay doesn't pin the display on.
     *
     * This is unrelated to the app's own idle screensaver, which is a visual effect drawn by
     * NowPlayingScreen and still runs on its own timeout.
     */
    private fun keepScreenAwake(active: Boolean) {
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Sends the app back to whatever the user was doing before, once a session that auto-opened
     * PhairPlay ends. [moveTaskToBack] backgrounds the task without finishing it, so the service
     * keeps advertising and the next sender can auto-open us again — we don't want a full quit.
     * Only fires for sessions we opened ourselves; if the user launched PhairPlay by hand they
     * stay on the home screen.
     */
    private fun trackSessionEnd(sessionActive: Boolean) {
        if (sessionActive) {
            hadActiveSession = true
            return
        }
        if (!hadActiveSession) return
        hadActiveSession = false
        if (!openedBySender) return
        openedBySender = false
        Timber.d("Session ended — returning to the previous app")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            // Can't moveTaskToBack straight out of PiP; finishing the PiP window drops the user
            // back to the app that was behind it.
            finishAndRemoveTask()
        } else {
            moveTaskToBack(true)
        }
    }

    /** Shows the AirPlay pairing PIN over the full screen during SRP pair-setup. */
    fun showPinScreen(pin: String) {
        pinScreen.setPin(pin)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        mirrorControls.hideBar()
        setOverlayOwnsInput(true)
    }
}
