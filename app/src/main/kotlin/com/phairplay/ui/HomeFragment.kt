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
    private lateinit var btnRestart: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureProtocolCards()
        configureButtons()
        showDeviceName()
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
    }

    /**
     * Sets the static content on each protocol card: icon and protocol name.
     * The dynamic parts (state, detail text) are updated when service state changes.
     */
    private fun configureProtocolCards() {
        setupCard(cardAirPlay,   R.drawable.ic_airplay,  R.string.protocol_airplay)
        setupCard(cardDlna,      R.drawable.ic_cast,     R.string.protocol_dlna)
        // Fire TV cannot complete a Miracast session, so the card would sit on "Advertising" for
        // ever and invite the user to try something that never connects. See DeviceFeatures.
        if (DeviceFeatures.MIRACAST_SUPPORTED) {
            setupCard(cardMiracast, R.drawable.ic_miracast, R.string.protocol_miracast)
        } else {
            cardMiracast.visibility = View.GONE
        }
    }

    private fun setupCard(card: View, iconRes: Int, nameRes: Int) {
        card.findViewById<android.widget.ImageView>(R.id.img_protocol_icon)?.setImageResource(iconRes)
        card.findViewById<TextView>(R.id.text_protocol_name)?.setText(nameRes)
    }

    /**
     * Configures Start / Stop / Restart button click listeners.
     * Calls [ServiceController] which sends Intent actions to [PhairPlayService].
     */
    private fun configureButtons() {
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
        textDeviceName.text = getString(R.string.home_device_visible_as, name)
    }

    // ─── State Observation ───────────────────────────────────────────────────

    /**
     * Starts collecting state updates from [PhairPlayService].
     * Called after the service is bound. Each StateFlow is collected independently
     * so that a change in one protocol card doesn't trigger a full UI redraw.
     */
    private fun observeServiceState() {
        val svc = service ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            svc.serviceState.collectLatest { state -> updateServiceStateBadge(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.airPlayState.collectLatest { state ->
                airPlayState = state
                updateProtocolCard(cardAirPlay, state, showLastSender = true)
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
        dotServiceState.background.setTint(requireContext().getColor(colorRes))
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
        dot.background.setTint(requireContext().getColor(colorRes))
    }
}
