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
import android.os.Handler
import android.os.Looper
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phairplay.service.PhairPlayService
import com.phairplay.service.PhotoFrame
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.airplay.DacpClient
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.settings.BackAction
import com.phairplay.settings.SettingsRepository
import com.phairplay.settings.StreamEndAction
import com.phairplay.util.Logger
import com.phairplay.ui.HomeFragment
import com.phairplay.ui.MirrorControls
import com.phairplay.ui.HomeKitSetupFragment
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
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: com.phairplay.ui.TouchOverlayFrameLayout

    // The SurfaceView for full-screen video output
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private lateinit var pinScreen: PinScreen
    private lateinit var mirrorControls: MirrorControls

    // Service binding — gives access to state flows for showing/hiding the streaming overlay
    private var service: PhairPlayService? = null

    /** Read-only access for fragments that need to ask the service something (HomeKit status). */
    val boundService: PhairPlayService? get() = service
    private var isBound = false
    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentDlnaState = ProtocolState.DISABLED
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
    /**
     * Set when Back ended the session with action=STOP_STREAM, so the stream-end action skips the
     * exit exactly once. See [onBackPressed].
     */
    private var stopRequestedByUser = false

    /** Cached from settings — the session-end path is synchronous and cannot await DataStore. */
    private var streamEndAction = com.phairplay.settings.StreamEndAction.STAY_IN_APP

    /** The current set of service collectors, so a rebind replaces them instead of duplicating. */
    private var observeJob: kotlinx.coroutines.Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { getVideoSurface() }
            // Put the SurfaceView on screen as soon as a sender opens the socket, before we know
            // what kind of session it is. A SurfaceView has no Surface until it is visible, and by
            // the time CONNECTED arrives the sender has already sent its opening IDR.
            service?.setSurfacePreparer { prepareVideoSurface() }

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
                    navigateTo(HomeFragment())
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
    /**
     * True while the onboarding fragment owns the screen.
     *
     * [onKeyDown] consults this before any of its remote mappings. Without it, a sender that
     * happened to be connected during first-run setup made `overlayActive` true, and every D-pad
     * Center press was swallowed and turned into a DACP play/pause — so the preference rows on the
     * onboarding pages could be focused but never selected, and looked simply broken.
     */
    private var onboardingVisible = false

    private fun showOnboarding() {
        onboardingVisible = true
        val fragment = OnboardingFragment().also { f ->
            f.onFinished = {
                onboardingVisible = false
                navigateTo(HomeFragment())
                requestNotificationPermission()
                // The receivers started in onCreate, seconds before onboarding wrote the user's
                // answers, so they are still running on pre-onboarding defaults — a chosen PIN
                // showed up in DataStore while the RTSP handler kept logging pinAuth=false. Restart
                // so every choice on the preferences page actually takes effect now, not on the next
                // launch. Same for the screensaver and high-resolution settings.
                Timber.i("Onboarding finished — restarting receivers to pick up chosen settings")
                ServiceController.restart(this)
                // HomeKit setup runs as its own flow at the end rather than as another onboarding
                // page: it asks the user to pick up a second device, so it does not belong in the
                // middle of a sequence they are trying to get through.
                showHomeKitSetup(startAtCode = false) {
                    navigateTo(HomeFragment())
                }
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Opens the guided HomeKit pairing flow.
     *
     * Reachable from two places on purpose — the tail of first-run onboarding, and Settings after a
     * pairing reset. HomeKit is the one feature whose setup lives mostly on ANOTHER device, so
     * "show me those instructions again" is a normal request rather than an error path.
     *
     * @param startAtCode skip the yes/no question. True when the user has already opted in and is
     *   coming back only for the code.
     */
    fun showHomeKitSetup(startAtCode: Boolean, onDone: (() -> Unit)? = null) {
        onboardingVisible = true
        val fragment = HomeKitSetupFragment().also { f ->
            f.startAtCode = startAtCode
            f.onSetEnabled = { enabled ->
                lifecycleScope.launch {
                    SettingsRepository(this@MainActivity).update { it.copy(homeKitEnabled = enabled) }
                    Timber.i("HomeKit ${if (enabled) "enabled" else "declined"} in setup — restarting receivers")
                    ServiceController.restart(this@MainActivity)
                }
            }
            f.onFinished = {
                onboardingVisible = false
                if (onDone != null) {
                    onDone()
                } else {
                    navigateTo(HomeFragment())
                }
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
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
        if (pipEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) {
            enterPip("leaving the app")
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
            builder.setAutoEnterEnabled(pipEnabled && pipHasContent())
        }
        return builder.build()
    }

    /**
     * Whether this device can do PiP at all, logged the first time we look.
     *
     * Fire TV is the reason this exists: `android:supportsPictureInPicture` in the manifest is a
     * request, not a guarantee, and many Fire TV devices simply do not ship the
     * `android.software.picture_in_picture` feature. Every entry attempt was wrapped in a
     * `runCatching` that reported through Timber, so on a device without the feature PiP did
     * nothing and said nothing — which is indistinguishable from a bug in our code.
     */
    private fun pipSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Logger.i("PiP unavailable: needs Android 8, this device is API ${Build.VERSION.SDK_INT}")
            return false
        }
        val has = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (!has) Logger.i("PiP unavailable: this device does not have FEATURE_PICTURE_IN_PICTURE")
        return has
    }

    /**
     * Enters PiP, saying out loud why when it doesn't.
     *
     * @param reason where the attempt came from, so the log distinguishes the auto path from the
     *   button the user actually pressed.
     */
    private fun enterPip(reason: String) {
        if (!pipSupported()) return
        if (!pipHasContent()) {
            Logger.i("PiP skipped ($reason): no active session to show")
            return
        }
        // Repeated inline rather than left to pipSupported(): lint cannot follow the version check
        // through a helper, and the firetv flavour's minSdk is 25 — below the API 26 this needs.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(pipParams()) }
            .onSuccess { Logger.i("PiP entered ($reason)") }
            .onFailure { Logger.w("PiP entry refused ($reason): ${it.message}") }
    }

    /**
     * True when there is something worth putting in a PiP window.
     *
     * This used to require [currentVideoPlaying], on the reasoning that PiP shows the video Surface
     * and an audio-only session has none. That reasoning is wrong: PiP renders the whole Activity
     * window, so an audio session shows the now-playing card -- artwork, title, progress -- which is
     * exactly what someone listening to music while using another app wants to keep on screen.
     * Requiring video is why "PiP doesn't work" during AirPlay audio, and the log said so plainly
     * every time ("no video stream on screen").
     *
     * Matches the predicate Back already uses to decide a session is on screen, so the two cannot
     * disagree about whether something is playing.
     */
    private fun pipHasContent(): Boolean =
        currentVideoPlaying || currentNowPlaying != null ||
            currentAirPlayState == ProtocolState.CONNECTED ||
            currentDlnaState == ProtocolState.CONNECTED

    /**
     * Swaps the now-playing screen between full and compact when the window becomes a PiP.
     *
     * Without this the PiP window was the whole full-screen layout scaled down -- every text size
     * chosen for a television, rendered into a thumbnail. The content was correct and unreadable.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Logger.i("PiP mode changed → $isInPictureInPictureMode")
        nowPlayingScreen.setCompact(isInPictureInPictureMode)
        // The video surface is sized by a cached aspect-fit pass; a PiP transition resizes the
        // container underneath it, so drop the cache and let the next pass recompute.
        streamingScreen.invalidateAspectFit()
        // The mirror control bar is driven by D-pad presses that cannot reach a PiP window, and it
        // would cover most of one.
        if (isInPictureInPictureMode) mirrorControls.hideBar()
    }

    /** Re-publishes PiP params whenever the thing they describe changes. */
    private fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back dismisses the pairing code first. The PIN overlay sits on top of everything and only
        // cleared when the sender finished, failed, or hit the lockout — so a sender that gave up
        // mid-pairing left the code on screen with no way out of it at all, whatever backAction was
        // set to. Cancel the pairing and fall back to the waiting screen instead of leaving the app.
        if (currentPin != null) {
            Timber.d("Back on PIN screen — cancelling pairing")
            service?.cancelPinPairing()
            currentPin = null
            updateOverlay()
            return
        }

        // Back leaves Settings for Home. With the nav panel gone this is the only way back, so it
        // has to come before the backAction branch below -- otherwise Back inside Settings would
        // stop the stream or quit the app, depending on a preference that is about the *stream*
        // and has nothing to say about which screen you are reading.
        if (selectedNavIndex == 1 && currentPin == null && streamingContainer.visibility != View.VISIBLE) {
            navigateTo(HomeFragment())
            return
        }

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
                    // The auto-return only exists for sessions that opened PhairPlay by themselves:
                    // when the sender is finished, so are we. Pressing Back is the opposite — the
                    // user is deliberately here and stopping the stream — so leaving anyway a few
                    // seconds later read as the app quitting on its own.
                    openedBySender = false
                    // AND SUPPRESS THE STREAM-END ACTION FOR THIS ONE STOP.
                    //
                    // Two different settings were fighting. Back=STOP_STREAM ends the session, and
                    // ending a session runs the stream-end handler, which leaves the app when
                    // "when the stream ends" is set to Exit — so Back stopped the stream and then
                    // quit anyway, which is precisely what STOP_STREAM exists not to do. Setting
                    // openedBySender was supposed to cover this, but the leaving decision stopped
                    // consulting it when STAY_IN_APP became a real setting, so that line had been
                    // doing nothing.
                    //
                    // The two questions are genuinely different: "what should happen when the
                    // SENDER finishes" versus "what should happen when I press Back". Someone
                    // pressing Back is standing in front of the TV and has just said where they
                    // want to be, so the sender-finished rule does not get to override them.
                    stopRequestedByUser = true
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
            it.onPrevClick     = { service?.dispatchTransportCommand(com.phairplay.airplay.DacpClient.CMD_PREV) }
            it.onNextClick     = { service?.dispatchTransportCommand(com.phairplay.airplay.DacpClient.CMD_NEXT) }
        }
        pinScreen = PinScreen(this)
        mirrorControls = MirrorControls(this).also {
            it.onStopClick = {
                Timber.d("Mirror controls: stop — ending session")
                service?.endCurrentSession()
            }
            it.onPipClick = { enterPip("mirror controls button") }
        }
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        streamingContainer.addView(pinScreen)
        // Added last so it draws over the video surface rather than under it.
        streamingContainer.addView(mirrorControls)
        // Touch, alongside the remote — same actions, same session split. A ViewGroup only reaches
        // its own touch listener when no child consumed the event, so the Now Playing transport
        // buttons and the mirror control bar keep taking their own taps; we get the empty space.
        streamingContainer.setOnTouchListener { v, event ->
            val handled = handleOverlayTouch(event)
            // Route a finished tap through performClick so accessibility services announce and can
            // trigger it. Without this the gesture is invisible to TalkBack — and lint is right to
            // insist: a touch surface that only ever reports raw MotionEvents cannot be driven by
            // anyone who is not physically touching the panel.
            if (handled && event.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
            handled
        }
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    /**
     * Touch equivalent of the remote mapping in [onKeyDown], routed by the same [sessionMode] latch
     * so the two input methods never disagree about what a gesture means.
     *
     * Video: a tap reveals the control bar, exactly as a D-pad press does.
     * Audio: a tap is play/pause and a horizontal fling changes track. No scrubbing — the D-pad
     * gets that on a remote, and a swipe-to-seek would fight the skip gesture for the same motion.
     */
    private fun handleOverlayTouch(event: android.view.MotionEvent): Boolean {
        // A PiP window is a thumbnail: taps there belong to the system's own controls, and acting
        // on them would fire transport commands the user never aimed at.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) return false
        if (sessionMode == Mode.NONE) return false
        return overlayGestures.onTouchEvent(event)
    }

    private val overlayGestures: android.view.GestureDetector by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            // Consuming DOWN is what keeps the rest of the gesture coming; without it a fling never
            // reaches onFling because MOVE/UP go elsewhere.
            override fun onDown(e: android.view.MotionEvent) = true

            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean = when (sessionMode) {
                Mode.VIDEO -> { mirrorControls.reveal(); true }
                Mode.AUDIO -> { nowPlayingScreen.togglePause(); true }
                Mode.NONE -> false
            }

            override fun onFling(
                down: android.view.MotionEvent?,
                up: android.view.MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (sessionMode != Mode.AUDIO || down == null) return false
                val dx = up.x - down.x
                val dy = up.y - down.y
                // Three guards, because a sloppy tap is a tiny fling and would skip a track: enough
                // distance, enough speed, and clearly more horizontal than vertical.
                if (kotlin.math.abs(dx) < dp(FLING_MIN_DP)) return false
                if (kotlin.math.abs(velocityX) < dp(FLING_MIN_VELOCITY_DP)) return false
                if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * 2) return false
                val command = if (dx < 0) DacpClient.CMD_NEXT else DacpClient.CMD_PREV
                Timber.d("Overlay fling ${if (dx < 0) "left" else "right"} — $command")
                service?.dispatchTransportCommand(command)
                return true
            }
        })
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

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
        // Swallow stray touches so nothing beneath the full-screen overlay is clickable either.
        streamingContainer.isClickable = owns
    }


    /**
     * Swaps the fragment in content_container.
     *
     * [selectedNavIndex] is kept because the streaming overlay restores whichever screen you were
     * on when a stream ends; there is no longer a highlight to update.
     */
    private fun navigateTo(fragment: Fragment) {
        selectedNavIndex = if (fragment is HomeFragment) 0 else 1
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_enter, R.anim.fragment_exit,
                R.anim.fragment_pop_enter, R.anim.fragment_pop_exit,
            )
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /** Opens Settings from Home. Back returns, via [onBackPressed]. */
    fun openSettings() {
        if (selectedNavIndex != 1) navigateTo(SettingsFragment())
    }



    /**
     * Shows the full-screen streaming overlay (called by PhairPlayService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    /**
     * Shows the (black) video surface ahead of a session so its Surface exists when the first
     * frame lands. Harmless if the session turns out to be audio or a photo: the state observers
     * swap in the right screen a moment later.
     */
    fun prepareVideoSurface() {
        if (sessionMode != Mode.NONE) return          // a session already owns the screen
        if (streamingContainer.visibility == View.VISIBLE) return
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        setOverlayOwnsInput(true)
        Timber.d("Surface prepared ahead of session")
    }

    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        // The bar stays hidden until the user asks for it; this only makes it available.
        mirrorControls.setPipAvailable(pipEnabled && pipSupported())
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
    /**
     * Feeds a key press into this window as if the physical remote had sent it.
     *
     * Both halves are required: a lone ACTION_DOWN leaves views that track press state stuck down,
     * and long-press detection never resolves.
     */
    private fun injectKey(keyCode: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        window.decorView.dispatchKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0),
        )
        window.decorView.dispatchKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0),
        )
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Handle Back here rather than relying on onBackPressed(). That path goes through
        // OnBackPressedDispatcher, where a fragment or the overlay can swallow the event before the
        // Activity sees it — which is why both Back settings appeared to do nothing.
        // Onboarding owns the remote outright. Its pages are ordinary focusable views and need
        // Center and the D-pad to reach them untouched.
        // "The media buttons do nothing" has two completely different causes — the key never
        // reaching this Activity, or the command being sent and the sender ignoring it — and they
        // are indistinguishable in a log that records neither. One line here separates them: if the
        // key appears and no DACP send follows, the mapping is wrong; if no key appears at all, the
        // press never left the system.
        Logger.i("Key ${android.view.KeyEvent.keyCodeToString(keyCode)} mode=$sessionMode")

        // Ignore auto-repeat. TV remotes repeat a held key roughly every 50ms, and every repeat was
        // being turned into another transport command: the device log shows one press of REWIND
        // producing five PreviousTrack messages inside 200ms, after which the iPhone dropped the
        // session outright. Transport commands are edge-triggered by nature -- "next track" five
        // times is not what any user pressing it once meant -- so only the initial press counts.
        // Navigation keys are exempt because holding a direction to scroll IS meaningful.
        if (event != null && event.repeatCount > 0 && !isNavigationKey(keyCode)) {
            return true
        }

        if (onboardingVisible) return super.onKeyDown(keyCode, event)

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
                service?.dispatchTransportCommand(videoCommand)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        // DLNA counts as an active overlay too. This tested AirPlay only, so during a DLNA render the
        // whole audio key mapping below was skipped and every transport key fell through to
        // super.onKeyDown -- which is why play/pause worked from the UMS control point (a UPnP action
        // we answer) but not from the TV remote (a key we never looked at).
        val overlayActive = currentNowPlaying != null ||
            currentAirPlayState == ProtocolState.CONNECTED ||
            currentDlnaState == ProtocolState.CONNECTED
        if (overlayActive) {
            // Any remote press counts as presence — restart the Now Playing idle countdown.
            nowPlayingScreen.notifyActivity()

            // Menu (and Info on some remotes) cycles the card through its six layouts: full size,
            // small and centred, then each of the four corners.
            //
            // A LONG press still flips the card over to its credits side, which is where that used
            // to live on a short press. Moving the card is the thing you do while looking at the
            // screen and want repeatable on one button; the credits are read once. The hold is
            // timed here rather than via onKeyLongPress -- see [menuLongRunnable].
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                keyCode == android.view.KeyEvent.KEYCODE_INFO
            ) {
                if (nowPlayingScreen.visibility == View.VISIBLE) {
                    if (event != null && event.repeatCount == 0) {
                        menuLongPressed = false
                        menuHandler.removeCallbacks(menuLongRunnable)
                        menuHandler.postDelayed(menuLongRunnable, MENU_LONG_PRESS_MS)
                    }
                    return true
                }
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
                service?.dispatchTransportCommand(command)
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
            service?.dispatchTransportCommand(DacpClient.CMD_PLAY_RESUME)
            return true
        }
        // The layout cycle fires on RELEASE, so a press that turned out to be a long one can be
        // claimed by the credits panel instead. Acting on the way down would run both.
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU || keyCode == android.view.KeyEvent.KEYCODE_INFO) {
            if (nowPlayingScreen.visibility == View.VISIBLE) {
                menuHandler.removeCallbacks(menuLongRunnable)
                val wasLong = menuLongPressed
                menuLongPressed = false
                return if (wasLong) true else nowPlayingScreen.cycleLayoutPreset()
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** True once the hold has already opened the credits, so the release does nothing. */
    private var menuLongPressed = false
    private val menuHandler = Handler(Looper.getMainLooper())

    /**
     * Opens the credits panel when Menu is HELD.
     *
     * Deliberately a posted delay rather than onKeyLongPress. The framework only dispatches a long
     * press for a key it is tracking, and that interacts with what onKeyDown returns for the
     * auto-repeat events -- which this Activity swallows wholesale, because one held REWIND was
     * otherwise producing five PreviousTrack commands in 200ms. Rather than have the credits depend
     * on that interaction resolving the way we hope, on remotes we cannot test every model of, the
     * hold is timed here. It behaves identically on anything that sends a key at all.
     */
    private val menuLongRunnable = Runnable {
        if (nowPlayingScreen.visibility == View.VISIBLE) {
            menuLongPressed = true
            nowPlayingScreen.toggleInfoPanel()
        }
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
                nowPlayingScreen.setOrbSpeed(settings.orbSpeed)
                nowPlayingScreen.setBackdropTheme(settings.backdropTheme)
                // Cached for the same reason as backAction: the session-end path is synchronous.
                streamEndAction = settings.streamEndAction
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
        /** How long Menu must be held to open the credits instead of moving the card. */
        private const val MENU_LONG_PRESS_MS = 600L

        /** Latency every AirPlay sender asks for in SETUP latencyMin: 11025 samples @44.1kHz. */
        private const val BASE_LATENCY_MS = 250

        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001

        /**
         * How long to stay on screen after a session ends before handing the TV back. Long enough
         * to cover a sender switching between mirroring and video, short enough that a real
         * disconnect doesn't leave the user staring at PhairPlay.
         */
        private const val SESSION_HANDOVER_GRACE_MS = 4_000L

        /**
         * Wait before leaving when the user chose "exit when the stream ends".
         *
         * Covers the mirroring-to-video handover gap without feeling like a delay.
         */
        private const val EXIT_ON_END_GRACE_MS = 800L

        /** Minimum horizontal travel for a swipe to count as a track skip rather than a tap. */
        private const val FLING_MIN_DP = 80

        /** Minimum horizontal speed, dp/s — filters the slow drag of a finger resting on glass. */
        private const val FLING_MIN_VELOCITY_DP = 200

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
     * Called once after the service is bound.
     *
     * Every collector is gated on [Lifecycle.State.STARTED] rather than merely launched in
     * [lifecycleScope]. The distinction matters: lifecycleScope is cancelled at DESTROY, not at
     * STOP, so plain `lifecycleScope.launch { flow.collect { … } }` keeps running the whole time the
     * Activity is backgrounded — behind the Fire TV launcher, with the screen off, during a PiP
     * transition. `audioEnergy` alone emits about ten times a second, so that was a continuous
     * stream of view mutations against a window nobody was looking at, and view work between onStop
     * and the next onStart is exactly where lifecycle crashes come from.
     *
     * [repeatOnLifecycle] suspends the collection at STOP and restarts it at START, which is what
     * the old doc comment on this method already claimed was happening.
     */
    private fun observeOverlayState() {
        val svc = service ?: return
        // CANCEL THE PREVIOUS SET FIRST. onStart() binds every time, so onServiceConnected fires
        // again on every return to the Activity -- but lifecycleScope jobs live until DESTROY, not
        // STOP, so each rebind stacked another full set of collectors on top of the old ones. The
        // visible symptom was the HomeKit D-pad: after six start/stop cycles one press injected six
        // KeyEvents and the cursor flew across the screen. repeatOnLifecycle suspends collection at
        // STOP but does not stop a second collector from being created.
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.airPlayState.collectLatest { state ->
                        currentAirPlayState = state
                        updateOverlay()
                    }
                }
                launch {
                    svc.dlnaState.collectLatest { state ->
                        currentDlnaState = state
                        updateOverlay()
                    }
                }
                launch {
                    svc.photoFrame.collectLatest { frame ->
                        currentPhotoFrame = frame
                        updateOverlay()
                    }
                }
                launch {
                    svc.nowPlaying.collect { info ->
                        currentNowPlaying = info
                        updateOverlay()
                    }
                }
                // The HomeKit remote's D-pad. Delivered as a real KeyEvent pair through the window
                // rather than by calling onKeyDown directly, so it travels the same path as the
                // physical remote -- focus search, fragment handling, the mirror control bar, all of
                // it -- instead of hitting only the branches the Activity happens to implement
                // itself. Gated with the rest: injecting a key into a stopped window does nothing
                // useful anyway.
                launch {
                    svc.remoteKeys.collect { keyCode -> injectKey(keyCode) }
                }
                launch {
                    svc.videoPlaying.collectLatest { playing ->
                        currentVideoPlaying = playing
                        refreshPipParams()
                        updateOverlay()
                    }
                }
                launch {
                    svc.pairingPin.collectLatest { pin ->
                        currentPin = pin
                        updateOverlay()
                    }
                }
                launch {
                    svc.audioEnergy.collect { e -> nowPlayingScreen.setEnergy(e) }
                }
                launch {
                    svc.audioBands.collect { b -> nowPlayingScreen.setBands(b) }
                }
                launch {
                    svc.volumeReport.collect { r -> nowPlayingScreen.setVolumeReport(r?.display) }
                }
            }
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin

        // Latch the mode BEFORE rendering, not after.
        //
        // This block used to run below the `when`, which meant the update that first reported video
        // playing was rendered against a mode still set to NONE — so it took the `else` branch and
        // *hid* the overlay, then set the mode to VIDEO on its way out with nothing left to redraw.
        // If that was the last emission, the screen stayed blank for the whole session while the
        // decoder happily rendered 33fps into a live Surface nobody could see. Disconnecting and
        // reconnecting worked only because it produced further emissions, one update too late.
        if (nowPlaying != null) lastNowPlaying = nowPlaying
        // DLNA counts as a live session too. This read AirPlay's state alone, so a DLNA render —
        // which publishes now-playing on the same flow — always hit the `!connected` branch below,
        // which resets the mode to NONE and clears lastNowPlaying. The audio played and the screen
        // stayed on the idle card, every time, with nothing in the log to say why.
        val connected = currentAirPlayState == ProtocolState.CONNECTED ||
                        currentDlnaState == ProtocolState.CONNECTED
        if (!connected && photoFrame == null) { sessionMode = Mode.NONE; lastNowPlaying = null }
        else if (sessionMode == Mode.NONE) {
            sessionMode = if (nowPlaying != null) Mode.AUDIO
                          else if (currentVideoPlaying) Mode.VIDEO else Mode.NONE
        } else if (sessionMode == Mode.VIDEO && nowPlaying != null && !currentVideoPlaying) {
            sessionMode = Mode.AUDIO
        } else if (sessionMode == Mode.AUDIO && currentVideoPlaying) {
            // ...and back again. Mirroring TikTok and opening search publishes now-playing metadata
            // while the mirror keeps running, which flipped the session to AUDIO permanently: there
            // was no path out of AUDIO short of disconnecting. Live video frames are the stronger
            // signal — if the decoder is being fed, that is what belongs on screen.
            sessionMode = Mode.VIDEO
        }

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

        val sessionActive = pin != null || currentVideoPlaying || nowPlaying != null ||
                            photoFrame != null || connected
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
            // A new session clears the flag: it belongs to the stop the user just asked for, not to
            // whatever the next sender does.
            stopRequestedByUser = false
            // A session started (or restarted) — cancel any pending exit.
            sessionEndHandler.removeCallbacks(returnToPreviousApp)
            return
        }
        if (!hadActiveSession) return
        hadActiveSession = false

        // The user ended this one themselves with Back, having chosen "stop the stream" — so they
        // asked to stay. The stream-end action answers a different question (what to do when the
        // SENDER finishes) and must not override an explicit instruction. See [onBackPressed].
        if (stopRequestedByUser) {
            stopRequestedByUser = false
            Timber.d("Stream ended by user request — staying put, ignoring streamEndAction")
            sessionEndHandler.removeCallbacks(returnToPreviousApp)
            return
        }

        // Leaving used to require openedBySender, so a manual launch sat on the waiting screen
        // forever after the stream ended. That is now the STAY_IN_APP setting rather than an
        // unconditional rule, and EXIT_APP leaves however the app was opened.
        // STAY_IN_APP means STAY. This used to fall through to openedBySender, so the app auto-quit
        // a few seconds after every stream that had opened it -- which is every stream started from
        // a phone. That was the old unconditional behaviour wearing a setting's name; if the user
        // has not asked to leave, we do not leave.
        val leaving = streamEndAction == StreamEndAction.EXIT_APP
        if (!leaving) return

        // Still not instant, and deliberately so. Switching from screen mirroring to AirPlay video
        // tears the first session down and opens a second a beat later; leaving on that gap made
        // PhairPlay look like it had quit by itself mid-handover. EXIT_APP uses a much shorter wait
        // -- long enough to cover the handover, short enough to read as immediate -- because a user
        // who asked to exit on stream end is telling us they do not want to sit on a dead screen.
        val grace = if (streamEndAction == StreamEndAction.EXIT_APP) {
            EXIT_ON_END_GRACE_MS
        } else {
            SESSION_HANDOVER_GRACE_MS
        }
        sessionEndHandler.removeCallbacks(returnToPreviousApp)
        sessionEndHandler.postDelayed(returnToPreviousApp, grace)
    }

    private val sessionEndHandler = Handler(Looper.getMainLooper())

    private val returnToPreviousApp = Runnable {
        if (hadActiveSession) return@Runnable   // a new session took over in the meantime
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
