package com.phairplay.dlna

import android.content.Context
import android.net.wifi.WifiManager
import com.phairplay.media.SharedMediaPlayer
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.Timer
import java.util.TimerTask

class DlnaServer(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit,
    private val onNowPlayingChanged: (com.phairplay.airplay.NowPlayingInfo?) -> Unit = {}
) {
    val mediaPlayer = SharedMediaPlayer(context)

    @Volatile private var running = false
    private var httpSocket: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var announceTimer: Timer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Pre-computed at start() on the service thread — avoids ContentProvider/main-thread
    // calls from background HTTP handler threads (which can deadlock).
    private var cachedUuid = ""
    private var cachedUdn  = ""
    private var cachedName = ""
    private var cachedIp   = ""
    private var cachedLocation = ""

    @Volatile private var currentUrl: String? = null
    @Volatile private var transportState = "STOPPED"

    /**
     * Live GENA subscriptions, keyed by SID.
     *
     * Previously a SUBSCRIBE got exactly one event — the initial one — and was then forgotten. A
     * control point learns transport state ONLY from these NOTIFYs, so it kept believing the
     * renderer was STOPPED forever and greyed out pause, stop and the volume slider; Play was the
     * one control still legal from STOPPED, which is exactly the symptom seen in UMS.
     */
    private class Subscription(val callbackUrl: String, val service: String) {
        var seq: Int = 0
    }
    private val subscriptions = java.util.concurrent.ConcurrentHashMap<String, Subscription>()

    /** Returns true only if the HTTP listener bound; false means DLNA is not actually serving. */
    fun start(): Boolean {
        running = true
        cachedUuid     = NetworkUtils.getPersistentUuid(context)
        cachedUdn      = "uuid:$cachedUuid"
        cachedName     = NetworkUtils.getDeviceName(context)
        cachedIp       = getWifiIp() ?: "127.0.0.1"
        cachedLocation = "http://$cachedIp:$HTTP_PORT/description.xml"
        Logger.i("DLNA identity name=$cachedName udn=$cachedUdn ip=$cachedIp")
        acquireMulticastLock()
        // Previously this logged "DLNA server started" unconditionally, so a failed bind produced a
        // stack trace immediately followed by a success line. Advertising over SSDP without a
        // listener would also point control points at a dead port.
        if (!startHttpServer()) {
            Logger.w("DLNA not started — HTTP port unavailable, skipping SSDP advertisement")
            return false
        }
        startSsdp()
        onStateChanged(ProtocolState.ADVERTISING)
        Logger.i("DLNA server started — HTTP :$HTTP_PORT  SSDP multicast")
        return true
    }

    /**
     * Ends the current render without tearing the server down — the DLNA equivalent of hanging up on
     * one sender. Back on the TV calls this; the renderer stays advertised for the next push.
     */
    fun endSession() {
        setTransportState("STOPPED")
        mediaPlayer.stop()
        currentUrl = null
        onNowPlayingChanged(null)
        onStateChanged(ProtocolState.ADVERTISING)
        Logger.i("DLNA render ended by user")
    }

    fun stop() {
        running = false
        announceTimer?.cancel(); announceTimer = null
        runCatching { ssdpSocket?.leaveGroup(InetAddress.getByName(SSDP_ADDR)) }
        runCatching { ssdpSocket?.close() }; ssdpSocket = null
        runCatching { httpSocket?.close() }; httpSocket = null
        multicastLock?.release(); multicastLock = null
        mediaPlayer.release()
        onStateChanged(ProtocolState.DISABLED)
        Logger.i("DLNA server stopped")
    }

    // ── HTTP server ────────────────────────────────────────────────────────

    private fun startHttpServer(): Boolean {
        val ss = try {
            ServerSocket().also {
                it.reuseAddress = true
                it.bind(java.net.InetSocketAddress(HTTP_PORT))
                httpSocket = it
            }
        } catch (e: java.net.BindException) {
            Logger.w("DLNA port $HTTP_PORT already in use — DLNA disabled")
            running = false
            onStateChanged(ProtocolState.DISABLED)
            return false
        }
        Thread {
            while (running) {
                runCatching {
                    val client = ss.accept()
                    Thread { handleHttp(client) }.also { it.isDaemon = true; it.start() }
                }.onFailure { if (running) Logger.e("DLNA HTTP accept error", it) }
            }
        }.also { it.isDaemon = true; it.name = "dlna-http"; it.start() }
        return true
    }

    private fun handleHttp(client: Socket) {
        client.soTimeout = 15_000
        runCatching {
            val input  = client.getInputStream().bufferedReader()
            val output = client.getOutputStream()

            val requestLine = input.readLine() ?: return@runCatching
            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            while (!line.isNullOrBlank()) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                line = input.readLine()
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path   = parts.getOrElse(1) { "/" }.substringBefore('?')

            Logger.d("DLNA $method $path")

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (method == "POST" && contentLength > 0) {
                val buf = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val n = input.read(buf, total, contentLength - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total)
            } else ""

            // Set by the SUBSCRIBE branch below so the response headers can echo it back.
            var subscribeSid: String? = null

            val soapAction = headers["soapaction"]?.trim('"')?.substringAfterLast('#') ?: ""
            if (soapAction.isNotBlank()) Logger.i("DLNA SOAP action=$soapAction body(${body.length}B)=${body.take(300)}")

            val (status, ct, responseBody) = when {
                method == "GET"  && path == "/description.xml"        -> Triple(200, "text/xml", deviceDescription())
                method == "GET"  && path == "/AVTransport/scpd"       -> Triple(200, "text/xml", avtScpd())
                method == "GET"  && path == "/RenderingControl/scpd"  -> Triple(200, "text/xml", rcScpd())
                method == "GET"  && path == "/ConnectionManager/scpd" -> Triple(200, "text/xml", cmScpd())
                method == "POST" && path == "/AVTransport/control"     -> Triple(200, "text/xml", handleAvt(soapAction, body))
                method == "POST" && path == "/RenderingControl/control" -> Triple(200, "text/xml", handleRc(soapAction, body))
                method == "POST" && path == "/ConnectionManager/control" -> Triple(200, "text/xml", handleCm(soapAction))
                method == "SUBSCRIBE" -> {
                    // A SUBSCRIBE with a SID and no CALLBACK is a renewal, not a new subscription.
                    // Minting a fresh SID for it orphaned the entry we were about to send events to.
                    val renewal = headers["sid"]?.takeIf { subscriptions.containsKey(it) }
                    if (renewal != null) {
                        subscribeSid = renewal
                    } else {
                        val sid = "uuid:" + java.util.UUID.randomUUID()
                        subscribeSid = sid
                        // CALLBACK is "<http://host:port/path>" — possibly several, space separated.
                        headers["callback"]
                            ?.substringAfter('<')?.substringBefore('>')
                            ?.takeIf { it.startsWith("http") }
                            ?.let { cb ->
                                subscriptions[sid] = Subscription(cb, path.substringAfter('/').substringBefore('/'))
                                sendEvent(sid, initial = true)
                            }
                    }
                    Triple(200, "text/plain", "")
                }
                method == "UNSUBSCRIBE" -> {
                    // This used to answer 404 for every UNSUBSCRIBE, which tells a control point the
                    // renderer forgot it — some then drop the device outright rather than resubscribe.
                    headers["sid"]?.let { subscriptions.remove(it) }
                    stopIfControlPointLeft()
                    Triple(200, "text/plain", "")
                }
                else -> Triple(404, "text/plain", "Not Found")
            }

            val bytes = responseBody.toByteArray()
            val headers2 = buildString {
                append("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
                append("Content-Type: $ct; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                // A fresh SID per subscription. Previously every SUBSCRIBE — across all three
                // services — got the device UDN back as its SID. Control points key their
                // subscription table by SID, so the collision made them treat the second and third
                // subscriptions as duplicates of the first and drop the renderer.
                subscribeSid?.let { append("SID: $it\r\nTIMEOUT: Second-1800\r\n") }
                append("\r\n")
            }
            Logger.i("DLNA -> $status $method $path (${bytes.size}B)")
            output.write(headers2.toByteArray())
            if (bytes.isNotEmpty()) output.write(bytes)
            output.flush()
            Logger.i("DLNA sent $method $path ok")
        }.onFailure { Logger.e("DLNA HTTP error: ${it.message}") }
        runCatching { client.close() }
    }

    // ── AVTransport SOAP ───────────────────────────────────────────────────

    internal fun handleAvt(action: String, body: String): String {
        return when (action) {
            "SetAVTransportURI" -> {
                val url = body.extractTag("CurrentURI")?.takeIf { it.isNotBlank() }
                Logger.i("DLNA SetAVTransportURI url=$url")
                if (url != null) {
                    currentUrl = url
                    setTransportState("TRANSITIONING")
                    // The DIDL-Lite document arrives ENTITY-ESCAPED inside <CurrentURIMetaData>, so
                    // its tags are `&lt;dc:title&gt;` in the raw body and no tag regex could ever
                    // match them. Unescaping first is the whole fix — every title, artist and album
                    // logged null before this, which is also why the now-playing screen had nothing
                    // to draw.
                    val didl = body.extractTag("CurrentURIMetaData").orEmpty().ifBlank { body }.unescapeXml()
                    // A renderer with no title on screen is useless, and some control points send
                    // no metadata at all — the file name beats an empty card.
                    val title  = didl.extractDidlTag("dc:title") ?: titleFromUrl(url)
                    val artist = didl.extractDidlTag("upnp:artist") ?: didl.extractDidlTag("dc:creator")
                    val album  = didl.extractDidlTag("upnp:album")
                    Logger.i("DLNA metadata title=$title artist=$artist album=$album")
                    // Published here rather than from the load callback: the track is known the
                    // moment the control point names it, and waiting for ExoPlayer to reach READY
                    // left the screen on the idle card for the whole buffering window.
                    onNowPlayingChanged(com.phairplay.airplay.NowPlayingInfo(
                        senderName = "DLNA",
                        title = title,
                        artist = artist,
                        album = album
                    ))
                    onStateChanged(ProtocolState.CONNECTED)
                    mediaPlayer.load(url) {
                        Logger.i("DLNA ExoPlayer ready — playing")
                        setTransportState("PLAYING")
                        mediaPlayer.play()
                    }
                }
                soapOk("AVTransport", "SetAVTransportURIResponse")
            }
            "Play" -> {
                Logger.i("DLNA Play (transportState=$transportState url=$currentUrl)")
                setTransportState("PLAYING"); mediaPlayer.play()
                soapOk("AVTransport", "PlayResponse")
            }
            "Pause" -> {
                Logger.i("DLNA Pause")
                setTransportState("PAUSED_PLAYBACK"); mediaPlayer.pause()
                soapOk("AVTransport", "PauseResponse")
            }
            "Stop" -> {
                Logger.i("DLNA Stop")
                setTransportState("STOPPED"); mediaPlayer.stop(); currentUrl = null
                onNowPlayingChanged(null)
                onStateChanged(ProtocolState.ADVERTISING)
                soapOk("AVTransport", "StopResponse")
            }
            "Seek" -> {
                val target = body.extractTag("Target") ?: "0:00:00"
                mediaPlayer.seekTo(parseUpnpTime(target))
                soapOk("AVTransport", "SeekResponse")
            }
            "GetTransportInfo" -> soapResponse("AVTransport", "GetTransportInfoResponse", """
                <CurrentTransportState>$transportState</CurrentTransportState>
                <CurrentTransportStatus>OK</CurrentTransportStatus>
                <CurrentSpeed>1</CurrentSpeed>""")
            "GetPositionInfo" -> {
                val pos = mediaPlayer.currentPositionMs.toUpnpTime()
                val dur = if (mediaPlayer.durationMs > 0) mediaPlayer.durationMs.toUpnpTime() else "0:00:00"
                soapResponse("AVTransport", "GetPositionInfoResponse", """
                <Track>1</Track>
                <TrackDuration>$dur</TrackDuration>
                <TrackMetaData></TrackMetaData>
                <TrackURI>${currentUrl.orEmpty()}</TrackURI>
                <RelTime>$pos</RelTime>
                <AbsTime>$pos</AbsTime>
                <RelCount>2147483647</RelCount>
                <AbsCount>2147483647</AbsCount>""")
            }
            "GetMediaInfo" -> soapResponse("AVTransport", "GetMediaInfoResponse", """
                <NrTracks>1</NrTracks>
                <MediaDuration>${if (mediaPlayer.durationMs > 0) mediaPlayer.durationMs.toUpnpTime() else "0:00:00"}</MediaDuration>
                <CurrentURI>${currentUrl.orEmpty()}</CurrentURI>
                <CurrentURIMetaData></CurrentURIMetaData>
                <NextURI></NextURI>
                <NextURIMetaData></NextURIMetaData>
                <PlayMedium>NETWORK</PlayMedium>
                <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
                <WriteStatus>NOT_IMPLEMENTED</WriteStatus>""")
            "GetCurrentTransportActions" -> soapResponse("AVTransport", "GetCurrentTransportActionsResponse",
                "<Actions>Play,Pause,Stop,Seek</Actions>")
            else -> soapOk("AVTransport", "${action}Response")
        }
    }

    /**
     * RenderingControl. SetVolume and SetMute used to answer OK and do nothing at all, so a control
     * point's slider moved and the sound never changed. They drive the ExoPlayer gain now, and the
     * getters report the real value instead of a hardcoded 100.
     */
    internal fun handleRc(action: String, body: String): String = when (action) {
        "SetVolume" -> {
            val level = body.extractTag("DesiredVolume")?.toIntOrNull()
            if (level != null) {
                mediaPlayer.setVolumePercent(level)
                Logger.i("DLNA SetVolume $level")
                notifySubscribers("RenderingControl")
            }
            soapOk("RenderingControl", "SetVolumeResponse")
        }
        "GetVolume" -> soapResponse("RenderingControl", "GetVolumeResponse",
            "<CurrentVolume>${mediaPlayer.volumePercent}</CurrentVolume>")
        "SetMute"   -> {
            // UPnP booleans arrive as either "1"/"0" or "true"/"false" depending on the sender.
            val desired = body.extractTag("DesiredMute")?.trim()
            if (desired != null) {
                mediaPlayer.setMuted(desired == "1" || desired.equals("true", ignoreCase = true))
                Logger.i("DLNA SetMute ${mediaPlayer.muted}")
                notifySubscribers("RenderingControl")
            }
            soapOk("RenderingControl", "SetMuteResponse")
        }
        "GetMute"   -> soapResponse("RenderingControl", "GetMuteResponse",
            "<CurrentMute>${if (mediaPlayer.muted) 1 else 0}</CurrentMute>")
        else        -> soapOk("RenderingControl", "${action}Response")
    }

    private fun handleCm(action: String): String = when (action) {
        "GetProtocolInfo" -> soapResponse("ConnectionManager", "GetProtocolInfoResponse", """
            <Source></Source>
            <Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/mpeg:*,http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/flac:*,http-get:*:application/x-mpegURL:*,http-get:*:application/dash+xml:*</Sink>""")
        else -> soapOk("ConnectionManager", "${action}Response")
    }

    // ── SSDP ───────────────────────────────────────────────────────────────

    private fun startSsdp() {
        val group = InetAddress.getByName(SSDP_ADDR)
        val ms = MulticastSocket(SSDP_PORT).also {
            it.soTimeout = 10_000
            it.joinGroup(group)
            it.timeToLive = 4
            ssdpSocket = it
        }

        // Listen for M-SEARCH
        Thread {
            val buf = ByteArray(2048)
            while (running) {
                runCatching {
                    val pkt = DatagramPacket(buf, buf.size)
                    ms.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length)
                    if (msg.startsWith("M-SEARCH") && (msg.contains("ssdp:all") ||
                            msg.contains("MediaRenderer") || msg.contains("AVTransport") ||
                            msg.contains("rootdevice") || msg.contains("RenderingControl"))) {
                        val mx = Regex("MX:\\s*(\\d+)").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                        Thread.sleep((Math.random() * mx * 1000).toLong().coerceIn(0, 3000))
                        sendSsdpResponse(pkt.address, pkt.port, "urn:schemas-upnp-org:device:MediaRenderer:1")
                        sendSsdpResponse(pkt.address, pkt.port, "urn:schemas-upnp-org:service:AVTransport:1")
                        sendSsdpResponse(pkt.address, pkt.port, "urn:schemas-upnp-org:service:RenderingControl:1")
                    }
                }.onFailure { if (running && it !is java.net.SocketTimeoutException) Logger.d("SSDP receive error: ${it.message}") }
            }
        }.also { it.isDaemon = true; it.name = "dlna-ssdp"; it.start() }

        // Periodic NOTIFY alive
        sendSsdpAlive()
        announceTimer = Timer(true).also {
            it.schedule(object : TimerTask() { override fun run() { if (running) sendSsdpAlive() } }, 30_000L, 60_000L)
        }
    }

    private fun sendSsdpAlive() {
        val nts = listOf(
            "upnp:rootdevice" to "$cachedUdn::upnp:rootdevice",
            cachedUdn to cachedUdn,
            "urn:schemas-upnp-org:device:MediaRenderer:1" to "$cachedUdn::urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1" to "$cachedUdn::urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1" to "$cachedUdn::urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1" to "$cachedUdn::urn:schemas-upnp-org:service:ConnectionManager:1"
        )
        nts.forEach { (nt, usn) -> sendNotify("ssdp:alive", nt, usn) }
    }

    fun sendSsdpByebye() {
        val nts = listOf(
            "upnp:rootdevice" to "$cachedUdn::upnp:rootdevice",
            cachedUdn to cachedUdn,
            "urn:schemas-upnp-org:device:MediaRenderer:1" to "$cachedUdn::urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1" to "$cachedUdn::urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1" to "$cachedUdn::urn:schemas-upnp-org:service:RenderingControl:1"
        )
        nts.forEach { (nt, usn) -> sendNotify("ssdp:byebye", nt, usn) }
    }

    private fun sendNotify(nts: String, nt: String, usn: String) {
        val msg = "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $cachedLocation\r\nNT: $nt\r\nNTS: $nts\r\nSERVER: Android/1.0 UPnP/1.1 PhairPlay/1.0\r\nUSN: $usn\r\n\r\n"
        runCatching {
            val bytes = msg.toByteArray()
            ssdpSocket?.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))
        }
    }

    private fun sendSsdpResponse(addr: InetAddress, port: Int, st: String) {
        val usn = if (st == cachedUdn) cachedUdn else "$cachedUdn::$st"
        val msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $cachedLocation\r\nSERVER: Android/1.0 UPnP/1.1 PhairPlay/1.0\r\nST: $st\r\nUSN: $usn\r\n\r\n"
        runCatching {
            val bytes = msg.toByteArray()
            ssdpSocket?.send(DatagramPacket(bytes, bytes.size, addr, port))
        }
    }

    // ── XML / SOAP helpers ────────────────────────────────────────────────

    private fun deviceDescription() = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>1</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$cachedName</friendlyName>
    <manufacturer>PhairPlay</manufacturer>
    <modelName>PhairPlay DLNA Renderer</modelName>
    <UDN>$cachedUdn</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/event</eventSubURL>
        <SCPDURL>/AVTransport/scpd</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/event</eventSubURL>
        <SCPDURL>/RenderingControl/scpd</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/ConnectionManager/control</controlURL>
        <eventSubURL>/ConnectionManager/event</eventSubURL>
        <SCPDURL>/ConnectionManager/scpd</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""

    private fun soapOk(svc: String, action: String) = soapResponse(svc, action, "")
    private fun soapResponse(svc: String, action: String, inner: String) = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$svc:1">$inner</u:$action></s:Body>
</s:Envelope>"""

    internal fun avtScpd() = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Play</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Pause</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Stop</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Seek</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
      <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetTransportInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
      <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
      <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetPositionInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
      <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
      <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
      <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
      <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
      <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
      <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
      <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType><allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>TRANSITIONING</allowedValue></allowedValueList></stateVariable>
    <!-- See the note in rcScpd: AVTransport events through LastChange only. -->
    <stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

    internal fun rcScpd() = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetVolume</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetVolume</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>SetMute</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetMute</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
    <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
    <!-- UPnP AV events the whole service through LastChange, never the individual variables. Volume
         was marked sendEvents="yes" while the NOTIFY body carried LastChange, so a strict control
         point (UMS is Cling-based, and Cling is strict) saw an event for a variable it had not been
         told to expect and discarded it. -->
    <stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

    private fun cmScpd() = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name><argumentList>
      <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
      <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

    // ── Utilities ─────────────────────────────────────────────────────────

    /**
     * Delivers the initial GENA NOTIFY that UPnP requires right after a SUBSCRIBE. Control points
     * built on libupnp (VLC among them) wait for this first event before they consider a renderer
     * usable — without it the device is discovered, described, and then silently ignored.
     *
     * Sent over a raw socket because HttpURLConnection refuses the non-standard NOTIFY verb.
     */
    private fun sendEvent(sid: String, initial: Boolean = false) {
        val sub = subscriptions[sid] ?: return
        val callbackUrl = sub.callbackUrl
        val seq = if (initial) 0 else ++sub.seq
        Thread {
            runCatching {
                val lastChange = if (sub.service == "RenderingControl") {
                    """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0">""" +
                        """<Volume channel="Master" val="${mediaPlayer.volumePercent}"/>""" +
                        """<Mute channel="Master" val="${if (mediaPlayer.muted) 1 else 0}"/></InstanceID></Event>"""
                } else {
                    """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0">""" +
                        """<TransportState val="$transportState"/>""" +
                        """<CurrentTransportActions val="Play,Pause,Stop,Seek"/></InstanceID></Event>"""
                }
                val body = """<?xml version="1.0"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><LastChange>${lastChange.xmlEscape()}</LastChange></e:property></e:propertyset>"""

                val url = java.net.URL(callbackUrl)
                val port = if (url.port > 0) url.port else 80
                val bytes = body.toByteArray()
                val request = "NOTIFY ${url.file.ifEmpty { "/" }} HTTP/1.1\r\n" +
                    "HOST: ${url.host}:$port\r\n" +
                    "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                    "NT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                    "SID: $sid\r\nSEQ: $seq\r\n" +
                    "CONTENT-LENGTH: ${bytes.size}\r\nConnection: close\r\n\r\n"

                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(url.host, port), 5_000)
                    sock.getOutputStream().apply { write(request.toByteArray()); write(bytes); flush() }
                }
                Logger.i("DLNA event seq=$seq ${sub.service} -> $callbackUrl sid=$sid")
            }.onFailure {
                Logger.w("DLNA event to $callbackUrl failed: ${it.message}")
                // A callback that refuses connections is a control point that went away. Keeping it
                // means every later state change spends five seconds timing out against it.
                if (!initial) subscriptions.remove(sid)
            }
        }.also { it.isDaemon = true; it.name = "dlna-event"; it.start() }
    }

    /**
     * Ends playback once the last subscription is gone.
     *
     * UPnP does not say a renderer must stop when a control point unsubscribes — strictly, the two
     * are unrelated. In practice a control point unsubscribes from all three services exactly when
     * the user closes it or stops the renderer, and leaving music playing out of a TV with no way
     * left to control it is the wrong behaviour for a receiver on someone's television.
     *
     * The delay is the safety margin: a control point that is merely resubscribing (a renewal that
     * arrived as UNSUBSCRIBE + SUBSCRIBE rather than a SID refresh) gets its new subscription in
     * well inside the window, and the pending stop is abandoned.
     */
    private fun stopIfControlPointLeft() {
        if (subscriptions.isNotEmpty()) return
        if (transportState == "STOPPED") return
        Logger.i("DLNA last subscription dropped — stopping in ${LEAVE_GRACE_MS}ms unless one returns")
        Thread {
            Thread.sleep(LEAVE_GRACE_MS)
            if (!running || subscriptions.isNotEmpty() || transportState == "STOPPED") return@Thread
            Logger.i("DLNA control point gone — stopping playback")
            setTransportState("STOPPED")
            mediaPlayer.stop()
            currentUrl = null
            onNowPlayingChanged(null)
            onStateChanged(ProtocolState.ADVERTISING)
        }.also { it.isDaemon = true; it.name = "dlna-leave"; it.start() }
    }

    /** Pushes the current state to every live subscription of one service. */
    private fun notifySubscribers(service: String) {
        subscriptions.entries
            .filter { it.value.service == service }
            .forEach { sendEvent(it.key) }
    }

    /** GENA carries the LastChange document as escaped text inside the propertyset. */
    private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun acquireMulticastLock() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wm.createMulticastLock("dlna").also { it.setReferenceCounted(true); it.acquire() }
    }

    private fun getWifiIp(): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION") val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    private fun Long.toUpnpTime(): String {
        val s = this / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun parseUpnpTime(t: String): Long {
        val parts = t.split(":").map { it.toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            else -> (parts.firstOrNull() ?: 0L) * 1000L
        }
    }

    private fun String.extractTag(tag: String): String? =
        "<$tag[^>]*>([^<]*)</$tag>".toRegex().find(this)?.groupValues?.getOrNull(1)

    private fun String.extractDidlTag(tag: String): String? =
        extractTag(tag)?.trim()?.ifBlank { null }

    /**
     * `&lt;` before `&amp;`, deliberately: unescaping ampersands first would turn a literal
     * `&amp;lt;` in a track title into a spurious tag delimiter.
     */
    private fun String.unescapeXml() =
        replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")

    /** Last path segment of the media URL, cleaned up enough to read as a track name. */
    private fun titleFromUrl(url: String): String? =
        url.substringAfterLast('/').substringBefore('?')
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
            .substringBeforeLast('.')
            .replace('-', ' ').replace('_', ' ')
            .trim().ifBlank { null }

    /** Single place a transport state changes, so no path can forget to tell the subscribers. */
    private fun setTransportState(state: String) {
        if (transportState == state) return
        transportState = state
        notifySubscribers("AVTransport")
    }

    companion object {
        const val HTTP_PORT = 8200
        private const val LEAVE_GRACE_MS = 3_000L
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }
}
