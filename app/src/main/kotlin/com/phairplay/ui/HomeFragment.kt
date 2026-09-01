package com.phairplay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.DeviceFeatures
import com.phairplay.R
import com.phairplay.service.PhairPlayService
import com.phairplay.service.Protocol
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.service.ServiceState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * HomeFragment — The main screen of PhairPlay.
 *
 * WHY: Shows the status of all three receiver protocols (AirPlay / Miracast / Cast)
 * and provides Start / Stop / Restart controls. Designed for TV: large cards,
 * D-pad navigable, Google TV Streamer design language.
 *
 * HOW: Binds to [PhairPlayService] to receive real-time state updates.
 * User interactions call [ServiceController] to send commands to the service.
 *
 * Navigation: accessed via the "Home" item in MainActivity's nav panel.
 */
class HomeFragment : Fragment() {

    // Service binding — gives direct access to PhairPlayService StateFlows
    private var service: PhairPlayService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Logger.d("HomeFragment: bound to PhairPlayService")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Logger.d("HomeFragment: unbound from PhairPlayService")
        }
    }

    // View references — bound in onViewCreated
    private lateinit var textDeviceName: TextView
    private lateinit var textServiceState: TextView
    private lateinit var dotServiceState: View
    private lateinit var cardAirPlay: View
    private lateinit var cardMiracast: View
    private lateinit var cardDlna: View
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSettings: android.widget.Button
    private lateinit var btnRestart: Button
    private lateinit var receiverField: ReceiverFieldView
    private lateinit var textLastSender: TextView
    /** Breathing animator on the service dot; cancelled with the view. */
    private var dotBreath: android.animation.ValueAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureProtocolCards()
        configureButtons()
        showDeviceName()
    }

    override fun onResume() {
        super.onResume()
        receiverField.resume()
    }

    override fun onPause() {
        super.onPause()
        // Nothing on screen should be animating while the screen is not on screen.
        receiverField.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dotBreath?.cancel()
        dotBreath = null
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service so we can observe its StateFlows
        val intent = Intent(requireContext(), PhairPlayService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        textDeviceName   = view.findViewById(R.id.text_device_name)
        textServiceState = view.findViewById(R.id.text_service_state)
        dotServiceState  = view.findViewById(R.id.dot_service_state)
        cardAirPlay      = view.findViewById(R.id.card_airplay)
        cardMiracast     = view.findViewById(R.id.card_miracast)
        cardDlna         = view.findViewById(R.id.card_dlna)
        btnStart         = view.findViewById(R.id.btn_start)
        btnStop          = view.findViewById(R.id.btn_stop)
        btnRestart       = view.findViewById(R.id.btn_restart)
        btnSettings      = view.findViewById(R.id.btn_settings)
        receiverField    = view.findViewById(R.id.receiver_field)
        textLastSender   = view.findViewById(R.id.text_last_sender)
        FocusMotion.attach(btnStart)
        FocusMotion.attach(btnStop)
        FocusMotion.attach(btnRestart)
        FocusMotion.attach(btnSettings)
    }

    /**
     * Sets the static content on each protocol card: icon and protocol name.
     * The dynamic parts (state, detail text) are updated when service state changes.
     */
    private fun configureProtocolCards() {
        setupCard(cardAirPlay, R.drawable.ic_airplay, R.string.protocol_airplay, R.color.chip_airplay)
        setupCard(cardDlna,    R.drawable.ic_cast,    R.string.protocol_dlna,    R.color.chip_dlna)
        // Fire TV cannot complete a Miracast session, so the card would sit on "Advertising" for
        // ever and invite the user to try something that never connects. See DeviceFeatures.
        if (DeviceFeatures.MIRACAST_SUPPORTED) {
            setupCard(cardMiracast, R.drawable.ic_miracast, R.string.protocol_miracast, R.color.chip_miracast)
        } else {
            cardMiracast.visibility = View.GONE
        }
    }

    private fun setupCard(card: View, iconRes: Int, nameRes: Int, chipColorRes: Int) {
        card.findViewById<android.widget.ImageView>(R.id.img_protocol_icon)?.let { icon ->
            icon.setImageResource(iconRes)
            // The chip carries the protocol's colour and the glyph is punched out of it in the
            // page colour, so the three cards are told apart before any text is read. They used to
            // be three identical grey tiles distinguished only by their labels.
            val chip = androidx.core.content.ContextCompat.getColor(requireContext(), chipColorRes)
            icon.backgroundTintList = android.content.res.ColorStateList.valueOf(chip)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.bg_bottom)
            )
        }
        card.findViewById<TextView>(R.id.text_protocol_name)?.setText(nameRes)
        FocusMotion.attach(card)
    }

    /**
     * Configures Start / Stop / Restart button click listeners.
     * Calls [ServiceController] which sends Intent actions to [PhairPlayService].
     */
    private fun configureButtons() {
        btnSettings.setOnClickListener {
            (activity as? com.phairplay.MainActivity)?.openSettings()
        }
        btnStart.setOnClickListener {
            Logger.d("User tapped Start")
            ServiceController.start(requireContext())
        }
        btnStop.setOnClickListener {
            Logger.d("User tapped Stop")
            ServiceController.stop(requireContext())
        }
        btnRestart.setOnClickListener {
            Logger.d("User tapped Restart")
            ServiceController.restart(requireContext())
        }
    }

    /**
     * Shows the device's AirPlay name on the HomeScreen so the user knows
     * what to look for in their sender's picker.
     */
    private fun showDeviceName() {
        val name = NetworkUtils.getDeviceName(requireContext())
        // The name alone. It is the hero of the screen now, at display size, and the card's own
        // label above it already says what it is for -- "Visible as: Living Room" set in 40sp read
        // as a sentence that had been enlarged by mistake.
        textDeviceName.text = name
    }

    // ─── State Observation ───────────────────────────────────────────────────

    /**
     * Starts collecting state updates from [PhairPlayService].
     * Called after the service is bound. Each StateFlow is collected independently
     * so that a change in one protocol card doesn't trigger a full UI redraw.
     */
    private fun observeServiceState() {
        val svc = service ?: return
        // The binding is asynchronous and outlives the view. When onboarding finished it restarted
        // the service and immediately replaced this fragment, so onServiceConnected landed after
        // onDestroyView and viewLifecycleOwner threw — taking the whole app down right at the end of
        // first-run setup. There is nothing to update if the view is gone; the next onViewCreated
        // will observe again.
        if (view == null) {
            Logger.i("HomeFragment: service connected after the view was destroyed — skipping observers")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            svc.serviceState.collectLatest { state -> updateServiceStateBadge(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.airPlayState.collectLatest { state ->
                val previous = airPlayState
                airPlayState = state
                updateProtocolCard(cardAirPlay, state, showLastSender = true)
                // The receiver field is the screen-level echo of the card's own transition: a
                // sender arriving is the one event worth announcing beyond the card it lands on.
                if (state == ProtocolState.CONNECTED && previous != ProtocolState.CONNECTED) {
                    receiverField.setMode(ReceiverFieldView.Mode.CONNECTED)
                } else if (previous == ProtocolState.CONNECTED && state != ProtocolState.CONNECTED) {
                    receiverField.setMode(ReceiverFieldView.Mode.IDLE)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.miracastState.collectLatest { state -> updateProtocolCard(cardMiracast, state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.dlnaState.collectLatest { state -> updateProtocolCard(cardDlna, state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.lastSender.collectLatest { sender ->
                lastSender = sender
                updateProtocolCard(cardAirPlay, airPlayState, showLastSender = true)
                showLastSenderLine(sender)
            }
        }
    }

    /**
     * Updates the global service state badge (top-right corner of HomeScreen).
     * Colors and text reflect whether the service is running, stopped, or restarting.
     */
    private fun updateServiceStateBadge(state: ServiceState) {
        val (textRes, colorRes) = when (state) {
            is ServiceState.Running    -> Pair(R.string.service_state_running,    R.color.status_running)
            is ServiceState.Stopped    -> Pair(R.string.service_state_stopped,    R.color.status_stopped)
            is ServiceState.Restarting -> Pair(R.string.service_state_restarting, R.color.status_transitioning)
            is ServiceState.Error      -> Pair(R.string.service_state_error,      R.color.status_stopped)
        }
        textServiceState.setText(textRes)
        dotServiceState.backgroundTintList =
            android.content.res.ColorStateList.valueOf(requireContext().getColor(colorRes))

        // The dot breathes only while the service is actually up. A stopped or errored receiver
        // holds still, so "is it alive" is answerable from across the room without reading a word.
        setDotBreathing(state is ServiceState.Running)

        // The field is off when the service is: an animated receiver behind "Stopped" would be
        // saying the opposite of the text next to it.
        receiverField.setMode(
            when (state) {
                is ServiceState.Running -> if (anyConnected()) ReceiverFieldView.Mode.STREAMING
                                           else ReceiverFieldView.Mode.IDLE
                is ServiceState.Restarting -> ReceiverFieldView.Mode.DISCOVERY
                else -> ReceiverFieldView.Mode.OFF
            }
        )
        applyControlHierarchy(state)
    }

    /**
     * Which control is the obvious one, given what the service is doing.
     *
     * All four remain present and functional; only their weight changes. The service is normally
     * already running, so Start is the odd one out rather than the headline: offering it at full
     * strength next to a receiver that is already up is offering to do nothing.
     */
    private fun applyControlHierarchy(state: ServiceState) {
        val running = state is ServiceState.Running || state is ServiceState.Restarting
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        btnRestart.visibility = if (running) View.VISIBLE else View.GONE
        // Whichever action leads gets the primary treatment; the rest stay quiet.
        btnStart.setBackgroundResource(R.drawable.btn_primary_selector)
        btnRestart.setBackgroundResource(
            if (running) R.drawable.btn_primary_selector else R.drawable.btn_control_selector
        )
        // Focus must never be left on a button that just disappeared.
        if (!running && btnRestart.isFocused) btnStart.requestFocus()
        if (running && btnStart.isFocused) btnRestart.requestFocus()
    }

    private fun setDotBreathing(on: Boolean) {
        if (!on) {
            dotBreath?.cancel(); dotBreath = null
            dotServiceState.alpha = 1f
            return
        }
        if (dotBreath?.isRunning == true) return
        dotBreath = android.animation.ValueAnimator.ofFloat(1f, 0.45f).apply {
            duration = 1600L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { dotServiceState.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun anyConnected(): Boolean = airPlayState == ProtocolState.CONNECTED


    /**
     * The reassurance line under the status: this worked before, and here is what it was.
     * Hidden entirely when there is nothing to say, rather than showing an empty label.
     */
    private fun showLastSenderLine(sender: PhairPlayService.LastSender?) {
        if (sender == null || sender.name.isBlank()) {
            textLastSender.visibility = View.GONE
            return
        }
        textLastSender.text =
            getString(R.string.home_last_connected, sender.name, relativeTime(sender.atMs))
        textLastSender.visibility = View.VISIBLE
    }

    /** Coarse "2h ago" style age — precision past the hour is noise on a status card. */
    private fun relativeTime(atMs: Long): String {
        if (atMs <= 0L) return getString(R.string.last_sender_just_now)
        val minutes = (System.currentTimeMillis() - atMs) / 60_000L
        return when {
            minutes < 1    -> getString(R.string.last_sender_just_now)
            minutes < 60   -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else           -> "${minutes / 1440}d ago"
        }
    }

    /**
     * Updates a single protocol status card with the current [ProtocolState].
     *
     * @param card      The card root view (cardAirPlay, cardMiracast, or cardCast).
     * @param state     The current state of this protocol.
     */
    /** Latest AirPlay state, kept so the last-sender line can be re-rendered on its own. */
    private var airPlayState: ProtocolState = ProtocolState.DISABLED
    private var lastSender: com.phairplay.service.PhairPlayService.LastSender? = null

    private fun updateProtocolCard(
        card: View, state: ProtocolState, showLastSender: Boolean = false
    ) {
        val dot    = card.findViewById<View>(R.id.dot_protocol_status)
        val stateText = card.findViewById<TextView>(R.id.text_protocol_state)
        val detail = card.findViewById<TextView>(R.id.text_protocol_detail)

        val (stateRes, colorRes, detailRes) = when (state) {
            ProtocolState.DISABLED    -> Triple(R.string.protocol_state_disabled,    R.color.status_disabled,  R.string.protocol_detail_disabled)
            ProtocolState.ADVERTISING -> Triple(R.string.protocol_state_advertising, R.color.status_running,   R.string.protocol_detail_waiting)
            ProtocolState.CONNECTED   -> Triple(R.string.protocol_state_connected,   R.color.status_running,   R.string.protocol_detail_connected)
            ProtocolState.ERROR       -> Triple(R.string.protocol_state_error,       R.color.status_stopped,   R.string.protocol_detail_error)
        }

        // Animate the transition before the text changes, so the swell lands with the new value.
        ProtocolCardAnimator.apply(card, state, animate = true)
        stateText.setText(stateRes)
        // While idle, naming the device we last saw is more reassuring than "Waiting for sender…".
        val sender = lastSender
        if (showLastSender && state == ProtocolState.ADVERTISING && sender != null) {
            detail.text = getString(
                R.string.protocol_detail_last_sender, sender.name, relativeTime(sender.atMs)
            )
        } else if (detailRes == R.string.protocol_detail_connected) {
            // This string carries a %1$s for the sender's name. setText(resId) does no formatting,
            // so the card literally read "Streaming from %1$s" for the whole session. During
            // pairing there is no name yet, so fall back to a phrasing that doesn't need one.
            val name = sender?.name
            detail.text =
                if (name.isNullOrBlank()) getString(R.string.protocol_detail_connected_unknown)
                else getString(detailRes, name)
        } else {
            detail.setText(detailRes)
        }
        ProtocolCardAnimator.tintDot(dot, requireContext().getColor(colorRes))
    }
}
