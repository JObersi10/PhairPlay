package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * DacpClient — sends transport commands from the TV back to the AirPlay sender (DACP, Digital Audio
 * Control Protocol). This is the "reverse" control channel: the TV remote can play/pause/skip what
 * the Mac/iPhone is streaming.
 *
 * The sender includes two RTSP headers during the session:
 *   • `DACP-ID`       — identifies its DACP service, advertised over mDNS as `iTunes_Ctrl_<DACP-ID>`
 *   • `Active-Remote` — an auth token we must echo on every command
 *
 * We discover + resolve that `_dacp._tcp` service to an `host:port`, then issue
 * `GET http://host:port/ctrl-int/1/<command>` with the `Active-Remote` header. Commands include
 * `playpause`, `nextitem`, `previtem`, `beginff`, `beginrew`, `volumeup`, `volumedown`, `mutetoggle`.
 *
 * Reference: DACP (iTunes remote) over the AirPlay reverse channel.
 */
class DacpClient(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    @Volatile private var resolvedHost: String? = null
    @Volatile private var resolvedPort: Int = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val pendingCommands = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * Supplies the sender's DACP identity (from RTSP `DACP-ID` / `Active-Remote` headers). Kicks off
     * mDNS discovery the first time a new sender is seen; cheap no-op on repeats.
     */
    fun configure(newDacpId: String?, newActiveRemote: String?) {
        if (newDacpId.isNullOrBlank() || newActiveRemote.isNullOrBlank()) return
        if (newDacpId == dacpId && newActiveRemote == activeRemote) return
        dacpId = newDacpId
        activeRemote = newActiveRemote
        resolvedHost = null
        resolvedPort = 0
        Logger.i("DACP configured: id=$newDacpId — discovering iTunes_Ctrl_$newDacpId")
        startDiscovery("iTunes_Ctrl_$newDacpId")
    }

    /** True once we have a sender identity (so the UI can know remote control is available). */
    val isAvailable: Boolean get() = activeRemote != null

    /**
     * Called when the sender answers a DACP command with a non-2xx status.
     *
     * Exists because "has a DACP identity" and "honours DACP" turned out to be different things.
     */
    var onCommandRejected: ((command: String, httpCode: Int) -> Unit)? = null

    /** Sends a DACP command (e.g. "playpause", "nextitem"). Queues if not yet resolved. */
    fun sendCommand(command: String) {
        val token = activeRemote ?: run { Logger.d("DACP '$command' ignored — no sender"); return }
        val host = resolvedHost
        val port = resolvedPort
        if (host == null || port == 0) {
            Logger.w("DACP '$command' queued — not resolved yet")
            pendingCommands.add(command)
            return
        }
        dispatchCommand(command, host, port, token)
    }

    private fun dispatchCommand(command: String, host: String, port: Int, token: String) {
        scope.launch {
            runCatching {
                val conn = URL("http://$host:$port/ctrl-int/1/$command").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Active-Remote", token)
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                Logger.i("DACP $command → HTTP $code")
                // A DACP identity is not a promise that DACP works. iOS 26 senders advertise one,
                // resolve, accept the connection and then answer 501 Not Implemented to every
                // transport command -- observed on every key press in the device log. Reporting
                // that lets the caller retry over MediaRemote instead of silently doing nothing.
                if (code !in 200..299) onCommandRejected?.invoke(command, code)
            }.onFailure { Logger.e("DACP $command failed", it) }
        }
    }

    fun stop() {
        stopDiscovery()
        scope.cancel()
    }

    private fun startDiscovery(targetName: String) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == targetName) {
                    runCatching { nsdManager.resolveService(info, resolveListener()) }
                }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.w("DACP discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(DACP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Logger.e("DACP discovery failed to start", it) }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onServiceResolved(info: NsdServiceInfo) {
            resolvedHost = info.host?.hostAddress
            resolvedPort = info.port
            Logger.i("DACP resolved: $resolvedHost:$resolvedPort")
            stopDiscovery()
            val host = resolvedHost ?: return
            val port = resolvedPort
            val token = activeRemote ?: return
            val pending = pendingCommands.toList(); pendingCommands.clear()
            pending.forEach { dispatchCommand(it, host, port, token) }
        }
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            Logger.w("DACP resolve failed: $errorCode")
        }
    }

    private fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    companion object {
        private const val DACP_SERVICE_TYPE = "_dacp._tcp"

        // DACP control commands used by the TV remote.
        const val CMD_PLAY_PAUSE = "playpause"
        const val CMD_NEXT = "nextitem"
        const val CMD_PREV = "previtem"
        const val CMD_FF = "beginff"
        const val CMD_REW = "beginrew"
        const val CMD_PLAY_RESUME = "playresume"
        /** Explicit pause. Needed when ending a session: "playpause" on a paused sender resumes it. */
        const val CMD_PAUSE = "pause"
        const val CMD_FF_STOP = "stopff"
        const val CMD_REW_STOP = "stoprew"
        const val CMD_VOLUME_UP = "volumeup"
        const val CMD_VOLUME_DOWN = "volumedown"
    }
}
