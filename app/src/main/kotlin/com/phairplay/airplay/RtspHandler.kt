package com.phairplay.airplay

import com.phairplay.airplay.handshake.AirPlayVersion
import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.InfoResponder
import com.phairplay.airplay.handshake.MediaRemote
import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PairingSession
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Base64Util
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import com.phairplay.airplay.handshake.RaopRsa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * AirPlay uses RTSP to negotiate codecs, ports, and encryption before media flows.
 * The handler accepts one sender at a time, parses ANNOUNCE SDP, acknowledges SETUP
 * and RECORD, then hands binary interleaved RTP frames to [RtpInterleaved].
 */
open class RtspHandler(
    private val context: android.content.Context,
    private val displayWidth: Int = 1920,
    private val displayHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    /** MediaRemote commands the sender advertised as enabled (see `updateMRSupportedCommands`). */
    private val onSupportedRemoteCommands: (Set<Int>) -> Unit = {},
    /**
     * AirPlay 2 mirror SETUP msg 1: supply decrypted AES key + pairing secret + the sender's
     * address and timing port (so the receiver can start NTP). Returns (eventPort, timingPort).
     */
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int, latencyMinSamples: Int) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    /** AirPlay 2 buffered audio-only SETUP (type 103, Apple Music → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    /** Sender volume change (AirPlay dB: −30…0, or ≤ −144 = mute) via SET_PARAMETER. */
    private val onVolume: (Float) -> Unit = {},
    /** Now-playing track metadata (DMAP) from SET_PARAMETER — any field may be null. */
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?, genre: String?, composer: String?, year: Int?, durationMs: Long?) -> Unit = { _, _, _, _, _, _, _ -> },
    /** Album artwork (JPEG/PNG bytes) from SET_PARAMETER; empty bytes = artwork cleared. */
    private val onArtwork: (ByteArray) -> Unit = {},
    /** Playback position + duration (seconds) from SET_PARAMETER text/parameters. */
    private val onPlaybackPosition: (positionSec: Double, durationSec: Double) -> Unit = { _, _ -> },
    /** AirPlay video URL mode: POST /play with a media URL + start fraction (0..1). */
    private val onVideoPlay: (url: String, startFraction: Double) -> Unit = { _, _ -> },
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> com.phairplay.airplay.PlaybackInfo? = { null },
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** When true, require HomeKit-style SRP PIN pairing before streaming (gated by AppSettings). */
    private val pinAuthEnabled: Boolean = false,
    /** Skip the PIN when this receiver has already completed a PIN pairing (AppSettings). */
    private val rememberPinPairing: Boolean = true,
    /** Persistent store of paired controllers' Ed25519 keys (for pair-verify). */
    private val pairingStore: com.phairplay.airplay.handshake.PairingStore? = null,
    /** Shows ([pin]) or hides (null) the on-screen pairing PIN during SRP pair-setup. */
    private val onShowPin: (pin: String?) -> Unit = {},
    /** PAUSE received (paused=true) or RECORD after PAUSE (paused=false). */
    private val onPlaybackPaused: (paused: Boolean) -> Unit = {},
    /**
     * The progress push in its native units: the RTP timestamps of the track's first and last
     * sample. Reported alongside the derived seconds because the receiver's own audio clock is in
     * the same units, which lets position be measured rather than extrapolated.
     */
    private val onPlaybackAnchor: (startTs: Long, endTs: Long) -> Unit = { _, _ -> },
    /** Sender name + device type resolved from mirror SETUP plist (called when mirror audio starts). */
    private val onSenderInfoChanged: (name: String, type: SenderDeviceType) -> Unit = { _, _ -> },
    /**
     * Fired the instant a sender opens the control socket — before pairing, before any decision
     * about what kind of session this is.
     *
     * The point is purely to start the Activity early. A cold Activity launch plus SurfaceView
     * creation costs more than the ~450 ms between the mirror key exchange and the first video
     * packet, so the decoder had no Surface for the opening IDR and then had to wait for the
     * sender's next one — seconds of black screen, which is why the first connect "didn't grab"
     * and a reconnect (warm Activity) did.
     */
    private val onSenderApproaching: () -> Unit = {}
) {

    // ─── Legacy AirPlay SRP PIN pairing (only used when pinAuthEnabled) ───────
    @Volatile private var legacyPin: com.phairplay.airplay.handshake.LegacyPairSetupPin? = null
    // True once a controller has completed SRP PIN pairing. Until then, with PIN auth on, we reject
    // pair-verify — which is what makes macOS fall back to the /pair-pin-start + /pair-setup-pin PIN
    // flow (an accepted pair-verify means "already trusted, no PIN needed").
    @Volatile private var pinPaired = false

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER volume queries. */
    @Volatile private var currentVolume: Float = 0f

    private var serverSocket: ServerSocket? = null

    /**
     * The senders currently being served. Capacity 1 is the shipped policy and is what every
     * other field on this class still assumes — `currentSession`, `pairingSession`, `fairPlay`
     * and `isMirrorSession` are all handler-wide, so a second admitted sender would overwrite the
     * first one's handshake. Raising [SessionRegistry.capacity] is safe only after those become
     * per-connection; `docs/MULTI_SCREEN.md` tracks that work.
     */
    private val sessions = SessionRegistry(capacity = 1)

    @Volatile
    private var running = false

    private var currentCSeq: Int = 0

    /**
     * Group membership and the shared playback anchor, from SETPEERS / SETRATEANCHORTIME.
     *
     * Lives for the whole handler rather than per-session: the sender sends peers and an anchor
     * around SETUP/RECORD, and a per-session object would be rebuilt underneath them.
     */
    val group = com.phairplay.airplay.handshake.MultiRoomGroup()

    @Volatile
    private var currentSession: SessionDescription? = null

    /** Per-connection AirPlay pairing state (pair-setup / pair-verify). */
    @Volatile
    private var pairingSession: PairingSession? = null

    /** Per-connection FairPlay state (fp-setup handshake + stream-key decrypt). */
    @Volatile
    private var fairPlay: FairPlay? = null

    /** Remote (sender) address of the active control connection — needed for AirPlay 2 NTP. */
    @Volatile
    private var currentRemoteAddress: java.net.InetAddress? = null

    /** Our own address on the socket the sender is talking to — goes into the Apple-Response blob. */
    private var currentLocalAddress: java.net.InetAddress? = null

    /** True once an AirPlay 2 mirroring SETUP has run on this connection (no ANNOUNCE/SDP). */
    @Volatile
    private var isMirrorSession = false

    /** Consumes the RTCP Sender Reports arriving on the interleaved control channel. */
    private val senderReports = SenderReportTracker()

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes = mutableSetOf<Int>()

    private var setupCount = 0
    private var pendingDeviceName: String? = null
    private var pendingDeviceType: SenderDeviceType = SenderDeviceType.UNKNOWN
    private var lastLoggedNpTitle: String? = null
    private var lastLoggedNpArtist: String? = null

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES
    )

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /** Starts the RTSP server. */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    /** Stops the RTSP server. */
    /**
     * Drops the current sender without stopping the RTSP server.
     *
     * Ending a session used to go through a full restartReceivers(), which tore down mDNS and
     * brought it straight back — so the sender saw the receiver reappear and simply reconnected,
     * and pressing Back looked like it did nothing on the phone. Closing just the client socket
     * ends the session the way a sender expects, while the server keeps listening.
     */
    /** Milestone timing for connect diagnosis: how long each handshake leg takes. */
    private var connectStartMs = 0L
    private fun stamp(what: String) {
        if (connectStartMs == 0L) return
        Logger.i("Connect timing: $what +${System.currentTimeMillis() - connectStartMs}ms")
    }

    fun disconnectActiveClient() {
        if (sessions.isEmpty()) return
        Logger.i("Dropping active RTSP client on user request")
        sessions.closeAll()
    }

    fun stop() {
        running = false
        try {
            sessions.closeAll()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

    /**
     * Binds the RTSP port with SO_REUSEADDR, retrying briefly if a just-stopped instance hasn't
     * released it yet. A quick service stop→start (the activity being destroyed and relaunched)
     * could otherwise fail with EADDRINUSE, leaving PhairPlay advertising over mDNS while port 7000
     * was dead — macOS would discover it and try to mirror but nothing could connect ("casting but
     * nothing shows"). SO_REUSEADDR handles TIME_WAIT; the retry covers the close/rebind race.
     */
    private fun bindRtspSocket(): ServerSocket {
        var lastError: java.io.IOException? = null
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (!running) throw java.io.IOException("RTSP server stopped before bind")
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(RTSP_PORT))
                }
            } catch (e: java.io.IOException) {
                lastError = e
                Logger.w("RTSP port $RTSP_PORT busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { throw e }
            }
        }
        throw lastError ?: java.io.IOException("RTSP bind to $RTSP_PORT failed")
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = bindRtspSocket()
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                val clientSocket = serverSocket!!.accept()

                // THE ACCEPT LOOP MUST NOT BLOCK.
                //
                // handleClient used to be called inline here, so for as long as one sender was
                // connected this loop never came back to accept(). A second sender's connection
                // then sat unanswered in the kernel's backlog: nothing rejected it, nothing served
                // it, it simply queued. By the time the first sender disconnected and the loop
                // reached accept() again, that queued socket was minutes stale -- the phone had
                // long given up -- yet we adopted it as activeClient and sat on it, so the phone's
                // *real* retry was then turned away as a "second client". That is the state where
                // only restarting the receiver helped.
                //
                // Handling each client on its own coroutine keeps accept() responsive, which is
                // what makes the one-sender-at-a-time policy below actually work: the newcomer
                // gets an immediate 503 instead of being left hanging, and its next attempt
                // succeeds the moment the first sender goes away.
                if (!sessions.admit(clientSocket)) {
                    Logger.w("Rejecting client ${clientSocket.inetAddress.hostAddress} — " +
                             "at capacity (${sessions.size()}/${sessions.capacity})")
                    runCatching { sendServiceUnavailable(clientSocket) }
                    runCatching { clientSocket.close() }
                    continue
                }

                connectStartMs = System.currentTimeMillis()
                Logger.i("New client connected: ${clientSocket.inetAddress.hostAddress}")
                // Only for a sender we are actually going to serve. Firing this for a rejected
                // connection would put the video surface up for a session that never happens.
                runCatching { onSenderApproaching() }

                scope.launch(Dispatchers.IO) { handleClient(clientSocket) }
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()

        // Fresh pairing + FairPlay state for each control connection.
        pairingSession = PairingSession(PairingKeys.get(context))
        fairPlay = FairPlay()
        // NOTE: legacyPin and pinPaired are deliberately NOT reset here. macOS runs the PIN handshake
        // across SEPARATE TCP connections (/pair-pin-start on one, /pair-setup-pin on the next), so the
        // PIN/verifier and the "paired" flag must survive a reconnect. They live for the receiver's
        // lifetime — replaced by the next /pair-pin-start, set on a successful pairing.
        currentRemoteAddress = socket.inetAddress
        currentLocalAddress = socket.localAddress

        try {
            while (running && !socket.isClosed) {
                val request = requestReader.read(inputStream) ?: break
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = routeRequest(request)
                sendResponse(outputStream, response)

                // After RECORD on a legacy SDP session: a session WITH video switches to interleaved
                // RTP (video arrives $-framed over this TCP socket). An audio-only session (e.g. Apple
                // Music) keeps the RTSP control loop — audio arrives on the UDP port, and macOS sends
                // now-playing metadata / volume / FLUSH / TEARDOWN as RTSP requests here that we must
                // keep handling (switching to interleaved mode would skip them → no metadata).
                if (request.method == "RECORD" && response.statusCode == 200 && !isMirrorSession &&
                    currentSession?.hasVideo == true) {
                    Logger.i("RTSP handshake complete — switching to interleaved RTP (video)")
                    break
                }
                if (response.statusCode !in 200..299 && response.statusCode != 101) {
                    Logger.w("RTSP ${request.method} ${request.uri} → ${response.statusCode} " +
                             "${response.statusMessage} (sender may abandon the session)")
                }
            }

            val session = currentSession
            if (session != null && session.hasVideo && running) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = {
                        Logger.i("RTP stream ended")
                    },
                    onSenderReport = { report -> senderReports.accept(report) },
                )
            }
        } catch (e: Exception) {
            // "Socket closed" is how a deliberate teardown surfaces on the blocked read — we closed
            // the socket ourselves. Logging it as an error with a stack trace made every normal
            // disconnect look like a crash.
            when {
                !running -> Unit
                e is java.net.SocketException && socket.isClosed -> Logger.i("RTSP client closed")
                else -> Logger.e("Error handling RTSP client", e)
            }
        } finally {
            Logger.i("Client disconnected")
            socket.close()
            // Only disown the slot if it is still OURS. Now that each client runs on its own
            // coroutine, a newcomer can be accepted in the window between this socket erroring and
            // this block running; clearing unconditionally would hand that newcomer's slot away and
            // let a third connection in behind it.
            sessions.release(socket)
            currentSession = null
            pairingSession = null
            fairPlay = null
            isMirrorSession = false
            activeStreamTypes.clear()
            setupCount = 0
            onStreamingStopped()
        }
    }

    private fun routeRequest(request: RtspRequest): RtspResponse {
        // Senders POST /feedback every ~2s as a keepalive. It carries nothing useful and drowns the
        // diagnostic buffer, so it is the one request we don't trace.
        if (!request.uri.endsWith("/feedback")) {
            // INFO, not DEBUG: Fire OS drops DEBUG-level logs for this package even in a debug
            // build, so this trace — the one thing that shows what a sender actually sent — was
            // invisible on the only device it matters on.
            Logger.i("RTSP ${request.method} ${request.uri}")
        }
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) handleMirrorSetup(request) else handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> handleSetRateAnchorTime(request)
            "SETPEERS", "SETPEERSX"                 -> handleSetPeers(request)
            "FLUSHBUFFERED"                         -> handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> handlePhotoPutInternal(request)
            "DELETE"        -> handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        // Whether this carries DACP-ID decides whether the TV remote can control the sender at all,
        // and the sender makes that call once, here, from the version we advertise. Log it either
        // way — a missing header is the finding, and it is invisible unless named.
        "/info"          -> handleInfo(request).also {
            if (!loggedInfoRemoteAuthority) {
                loggedInfoRemoteAuthority = true
                val id = request.headers["DACP-ID"]
                Logger.i(
                    if (id != null) "GET /info granted remote authority: DACP-ID=$id (srcvers ${AirPlayVersion.ADVERTISED})"
                    else "GET /info WITHOUT DACP-ID — sender withheld remote authority (srcvers ${AirPlayVersion.ADVERTISED})"
                )
            }
        }
        "/playback-info" -> handlePlaybackInfo(request)
        "/scrub"         -> handleScrubGet(request)
        "/server-info"   -> handleServerInfo(request)
        else             -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> handlePairSetup(request)
        "/pair-setup-pin" -> handleLegacyPairSetupPin(request)   // legacy AirPlay PIN SRP (plist)
        "/pair-pin-start" -> handlePairPinStart(request)
        "/pair-verify" -> handlePairVerify(request)
        "/fp-setup"    -> handleFpSetup(request)
        "/feedback"    -> handleFeedback(request)
        "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        "/reverse"     -> handleReverse(request)
        "/command"     -> handleCommand(request)
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/play"        -> handleVideoPlay(request)
        "/rate"        -> handleVideoRate(request)
        "/scrub"       -> handleVideoScrubPost(request)
        "/stop"        -> handleVideoStop(request)
        else           -> handleUnknownInternal(request)
    }

    // ─── AirPlay video URL mode (POST /play, /rate, /scrub, /stop; GET /playback-info, /scrub) ──

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    private fun handleVideoPlay(request: RtspRequest): RtspResponse {
        val (url, start) = parsePlayBody(request)
        if (url.isNullOrBlank()) {
            Logger.w("POST /play with no Content-Location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        Logger.i("POST /play url=$url start=$start")
        onVideoPlay(url, start)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Extracts the media URL + start fraction from a /play body (plist `Content-Location` or text). */
    private fun parsePlayBody(request: RtspRequest): Pair<String?, Double> {
        if (request.isPlistBody()) {
            val p = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull() ?: return null to 0.0
            val url = p["Content-Location"] as? String
            val start = (p["Start-Position"] as? Double) ?: 0.0
            return url to start
        }
        // Legacy text body: "Content-Location: <url>\r\nStart-Position: <float>\r\n"
        var url: String? = null
        var start = 0.0
        request.body.lineSequence().forEach { line ->
            when {
                line.startsWith("Content-Location:", true) -> url = line.substringAfter(":").trim()
                line.startsWith("Start-Position:", true) -> start = line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
            }
        }
        return url to start
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    private fun handleVideoRate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull() ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    private fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    private fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /**
     * POST /command — the AirPlay 2 media-remote control channel.
     *
     * iOS (not macOS) sends this immediately after RECORD, carrying a binary plist whose `type` is
     * usually `updateMRSupportedCommands` — the sender telling the receiver which transport commands
     * it will honour. It expects a 200 with a plist body.
     *
     * We do not act on the contents; the DACP reverse channel already gives the TV remote its
     * control path. What matters is answering at all: this used to fall through to 501, and iOS
     * treats that as a receiver that cannot hold up its end and abandons the session — it completed
     * RECORD and then never sent the SETUP carrying `streams`, so no data server started and the
     * screen stayed black. macOS never sends /command, which is why mirroring from a Mac worked
     * throughout and only iPhone mirroring was broken.
     */
    /** One dump per session — the list does not change while a sender is connected. */
    private var loggedSupportedCommands = false

    /** One line per session about whether the sender granted DACP reverse-control authority. */
    private var loggedInfoRemoteAuthority = false

    /**
     * Renders a decoded plist value as readable text. Deliberately structural rather than a raw
     * hex dump: what matters is the command identifiers and their nesting, not the bytes.
     */
    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${it.key}=${describe(it.value)}" }
        is List<*> -> value.joinToString(", ", "[", "]") { describe(it) }
        is ByteArray -> "<${value.size}B>"
        else -> value.toString()
    }

    /**
     * Turns `mrSupportedCommandsFromSender` into the set of MediaRemote commands this sender will
     * accept, and hands it to the receiver so the TV remote knows what it can drive.
     *
     * The blobs are serialized `CommandInfo` protobufs, not plists — see [MediaRemote]. Decoding
     * them is what turned "36 opaque blobs" into a vocabulary; anything that fails to decode is
     * counted and reported rather than dropped, because a silent miss here would look identical to
     * a sender that supports nothing.
     */
    /**
     * Decodes one `mrSupportedCommandsFromSender` entry, whatever form it arrives in.
     *
     * Senders have been observed describing commands two ways, so both are handled rather than
     * betting on one: a plist dictionary keyed `kCommandInfoCommandKey`/`kCommandInfoEnabledKey`
     * (either inline or as a nested binary plist inside a data blob), and a serialized MediaRemote
     * `CommandInfo` protobuf. They carry the same two facts.
     */
    private fun decodeSupportedCommand(entry: Any?): MediaRemote.SupportedCommand? = when (entry) {
        is Map<*, *> -> fromCommandInfoDict(entry)
        is ByteArray ->
            if (entry.size >= BPLIST_MAGIC.size && entry.copyOf(BPLIST_MAGIC.size).contentEquals(BPLIST_MAGIC)) {
                runCatching { fromCommandInfoDict(PlistCodec.decode(entry)) }.getOrNull()
            } else {
                MediaRemote.decodeCommandInfo(entry)
            }
        else -> null
    }

    private fun fromCommandInfoDict(dict: Map<*, *>): MediaRemote.SupportedCommand? {
        val command = (dict["kCommandInfoCommandKey"] as? Number)?.toInt() ?: return null
        val enabled = when (val e = dict["kCommandInfoEnabledKey"]) {
            is Boolean -> e
            is Number -> e.toInt() != 0
            else -> true
        }
        return MediaRemote.SupportedCommand(command, enabled)
    }

    private fun captureSupportedCommands(plist: Map<String, Any?>?) {
        val params = plist?.get("params") as? Map<*, *> ?: return
        val blobs = params["mrSupportedCommandsFromSender"] as? List<*> ?: return
        val commands = blobs.mapNotNull { decodeSupportedCommand(it) }
        val undecodable = blobs.size - commands.size
        if (commands.isEmpty()) {
            // Say what the entries actually *are* rather than only that they failed — a leading
            // "bplist00" means a nested plist, 0x08 means a protobuf, anything else means neither.
            val sample = blobs.firstOrNull()
            val shape = when (sample) {
                is ByteArray -> "bytes[${sample.size}] " +
                    sample.take(COMMAND_SAMPLE_BYTES).joinToString("") { "%02x".format(it) }
                null -> "null"
                else -> "${sample.javaClass.simpleName}: ${describe(sample).take(COMMAND_SAMPLE_CHARS)}"
            }
            Logger.w("updateMRSupportedCommands: ${blobs.size} entries, none decoded. First = $shape")
            return
        }
        val enabled = commands.filter { it.enabled }.map { it.command }.toSet()
        Logger.i(
            "Sender supports ${commands.size} MediaRemote commands" +
                (if (undecodable > 0) " ($undecodable undecodable)" else "") +
                ": ${commands.joinToString(", ")}"
        )
        onSupportedRemoteCommands(enabled)
    }

    private fun handleCommand(request: RtspRequest): RtspResponse {
        val plist = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull()
        val type = plist?.get("type") as? String ?: "unknown"
        Logger.i("POST /command type=$type (${request.bodyBytes.size}B) — acknowledged")
        // Dump the payload for updateMRSupportedCommands. The sender is listing the transport
        // commands it will accept, which is the only authoritative source for the vocabulary a
        // receiver may send back — the public protocol notes document controller→Apple TV control
        // (DACP ctrl-int, MRP, _hidC) and nothing in this direction. Logged once per session at
        // INFO because DEBUG is dropped on Fire OS, and truncated so a 7 KB plist stays readable.
        // Dump the whole plist structure once, not a guessed key. An earlier version assumed a
        // "value" entry and logged null for every message because no such key exists — the point of
        // this trace is to discover the shape, so it must not presuppose one.
        if (!loggedSupportedCommands && request.bodyBytes.size > COMMAND_DUMP_MIN_BYTES && plist != null) {
            loggedSupportedCommands = true
            Logger.i("POST /command keys=${plist.keys} body=${describe(plist).take(SUPPORTED_COMMANDS_LOG_CHARS)}")
        }
        if (type == "updateMRSupportedCommands") captureSupportedCommands(plist)
        // Bodyless 200, the way a real Apple TV answers. An empty *plist* is not the same thing as
        // no body: the sender parses what it is given, and a zero-key plist where it expects either
        // nothing or a populated ack is a parse it can reject silently.
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * POST /reverse — the sender's event channel for AirPlay video.
     *
     * Before it will play a URL, a video sender asks to turn this socket around: it sends
     * `Upgrade: PTTH/1.0` and expects `101 Switching Protocols`, after which the *receiver* may push
     * unsolicited events (playback state changes) back down the same connection. It is a plain HTTP
     * upgrade handshake, backwards.
     *
     * We answer the upgrade and then never push anything, which is legitimate — the sender polls
     * `GET /playback-info` regardless, and that is where it actually reads our state from. What is
     * not legitimate is the 501 this used to fall through to: senders treat a refused event channel
     * as a receiver that cannot do video at all and abandon the whole attempt, which is what a
     * generic "something went wrong" in the sending app looks like from the outside.
     */
    private fun handleReverse(request: RtspRequest): RtspResponse {
        // Headers keep the casing the sender used, and this one is not consistently cased across
        // iOS versions — match it without caring.
        val purpose = request.headers.entries
            .firstOrNull { it.key.equals("X-Apple-Purpose", ignoreCase = true) }?.value ?: "unknown"
        Logger.i("POST /reverse — event channel requested (purpose=$purpose)")
        return RtspResponse(
            101, "Switching Protocols",
            protocol = request.responseProtocol(),
            headers = mapOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade")
        )
    }

    /** POST /stop — stop URL playback. */
    private fun handleVideoStop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (video URL)")
        onVideoStop()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    private fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            mapOf("readyToPlay" to false)
        } else {
            val ranges = listOf(mapOf("start" to 0.0, "duration" to info.durationSec))
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to true,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to ranges,
                "seekableTimeRanges" to ranges,
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** GET /server-info — legacy XML plist of receiver identity for AirPlay video senders. */
    private fun handleServerInfo(request: RtspRequest): RtspResponse {
        val info = mapOf(
            "deviceid" to com.phairplay.util.NetworkUtils.getMacAddress(),
            "features" to 0x1E5A7FFFF7L,
            "model" to "AppleTV6,2",
            "protovers" to "1.1",
            "srcvers" to AirPlayVersion.ADVERTISED,
        )
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(info),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")

    /** POST /feedback — macOS health-checks the session every ~2 s; acknowledge with 200 OK. */
    private fun handleFeedback(request: RtspRequest): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("/feedback body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("/feedback body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Acknowledges an AirPlay 2 buffered-audio control verb (SETRATEANCHORTIME / SETPEERS /
     * FLUSHBUFFERED). Returning 200 keeps an audio-only session alive (a 501 would make macOS abort).
     */
    private fun handleBufferedControl(request: RtspRequest, label: String): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("$label body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("$label body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * SETPEERS / SETPEERSX — the sender's list of every device in the group.
     *
     * Recorded rather than merely acknowledged because it is the group's membership: which PTP
     * grandmaster to follow comes from here, and a receiver following a different master than its
     * peers has no shared timebase no matter how well its own clock is synchronised.
     */
    private fun handleSetPeers(request: RtspRequest): RtspResponse {
        if (request.bodyBytes.isNotEmpty()) {
            runCatching {
                // The body is a bare plist ARRAY, not a dictionary, so the dictionary decoder does
                // not fit it -- decodeRoot returns whatever the root object actually is.
                val peers = com.phairplay.airplay.handshake.MultiRoomGroup
                    .parsePeers(PlistCodec.decodeRoot(request.bodyBytes))
                group.setPeers(peers)
            }.onFailure { Logger.w("SETPEERS body could not be parsed: ${it.message}") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * SETRATEANCHORTIME — the shared playback anchor, and the thing that actually synchronises a
     * group: every receiver maps the same RTP timestamp to the same network time, so agreeing on
     * the clock is enough to agree on which sample to play.
     */
    private fun handleSetRateAnchorTime(request: RtspRequest): RtspResponse {
        if (request.bodyBytes.isNotEmpty()) {
            runCatching {
                val dict = PlistCodec.decode(request.bodyBytes)
                val anchor = com.phairplay.airplay.handshake.MultiRoomGroup.parseAnchor(dict)
                if (anchor != null) group.setAnchor(anchor)
                else Logger.w("SETRATEANCHORTIME without rtpTime/networkTimeSecs — ignoring")
            }.onFailure { Logger.w("SETRATEANCHORTIME body could not be parsed: ${it.message}") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /info — advertises receiver identity + capabilities (binary plist). */
    private fun handleInfo(request: RtspRequest): RtspResponse {
        // Client may send a body plist with its own info (name, model, etc.)
        if (request.bodyBytes.isNotEmpty()) {
            runCatching {
                val info = PlistCodec.decode(request.bodyBytes)
                val plistName = info["name"] as? String
                val plistModel = info["model"] as? String
                if (plistName != null || plistModel != null) {
                    val dtype = when {
                        plistModel?.startsWith("iPhone") == true -> SenderDeviceType.IPHONE
                        plistModel?.startsWith("iPad") == true   -> SenderDeviceType.IPAD
                        plistModel?.startsWith("Mac") == true || plistModel?.startsWith("iMac") == true
                            || plistModel?.startsWith("MacBook") == true -> SenderDeviceType.MAC
                        else -> SenderDeviceType.UNKNOWN
                    }
                    pendingDeviceName = plistName
                    pendingDeviceType = dtype
                    Logger.i("GET /info sender: name=$plistName model=$plistModel type=$dtype")
                }
            }
        }
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            bodyBytes = InfoResponder.build(context, displayWidth, displayHeight, pinRequired = pinAuthEnabled),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    }

    /**
     * POST /pair-setup. With PIN auth off (default) this is the anonymous Ed25519 exchange. With PIN
     * auth on, it runs the HomeKit-style SRP pair-setup (TLV8) — showing a PIN on the TV that the
     * user types on the Mac — so only someone with screen access can pair.
     */
    private fun handlePairSetup(request: RtspRequest): RtspResponse {
        // /pair-setup is the anonymous key exchange; PIN access control runs on /pair-setup-pin.
        return try {
            val body = pairingSession!!.pairSetup(request.bodyBytes)
            Logger.i("pair-setup OK (returned ${body.size}-byte public key)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-setup failed", e)
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-verify — the anonymous ECDH handshake. AirPlay uses this same raw exchange even with
     * PIN access control on (the PIN is a SEPARATE /pair-pin-start + /pair-setup-pin layer), so this
     * is never the HomeKit TLV8 variant.
     */
    private fun handlePairVerify(request: RtspRequest): RtspResponse {
        // PIN access control: refuse pair-verify until the controller has PIN-paired this connection.
        // macOS responds to the rejection by starting the PIN flow (/pair-pin-start → /pair-setup-pin).
        val alreadyTrusted = rememberPinPairing && pairingStore?.isPinTrusted() == true
        if (pinAuthEnabled && !pinPaired && !alreadyTrusted) {
            Logger.i("pair-verify rejected — PIN pairing required first (triggers /pair-pin-start)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val body = pairingSession!!.pairVerify(request.bodyBytes)
            Logger.i("pair-verify ${if (request.bodyBytes.firstOrNull()?.toInt() == 1) "M1" else "M2"} OK (returned ${body.size} bytes)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-pin-start — macOS asks the receiver to begin PIN pairing and display the code. We
     * generate + show the PIN and prime the SRP session, then reply 200; the SRP exchange follows on
     * /pair-setup-pin. (This precedes pair-setup in the AirPlay PIN flow — see the logs.)
     */
    private fun handlePairPinStart(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-pin-start blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        newSrpSession()
        Logger.i("pair-pin-start — PIN shown, SRP session primed")
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Abandons an in-progress PIN pairing and takes the code off the screen.
     *
     * The PIN otherwise only clears on success, a failed attempt, or the lockout — all of which
     * require the sender to keep talking to us. A sender that simply walks away (gives up, or
     * pairs over a different route) leaves the code on screen with nothing left to dismiss it,
     * and the pairing overlay covers every other screen. This is the user's way out.
     *
     * Discards the SRP session too: the next /pair-pin-start mints a new PIN, so a code the user
     * dismissed can never be used to complete a pairing later.
     */
    fun cancelPinPairing() {
        if (legacyPin == null) return
        legacyPin = null
        onShowPin(null)
        Logger.i("PIN pairing cancelled by user — SRP session discarded")
    }

    /** Generates a fresh 4-digit PIN, shows it on the TV, and primes the legacy SRP session. */
    private fun newSrpSession() {
        val pin = "%0${PIN_DIGITS}d".format(java.security.SecureRandom().nextInt(PIN_SPACE))
        onShowPin(pin)
        legacyPin = com.phairplay.airplay.handshake.LegacyPairSetupPin(pin, PairingKeys.get(context).edPublic)
    }

    /**
     * POST /pair-setup-pin — the legacy AirPlay plist SRP exchange. Step 1 ({method,user}) returns
     * {pk,salt}; step 2 ({pk,proof}) verifies the PIN and returns {proof}. On success the controller
     * is allowed past pair-verify (→ streaming). Bounded by the failed-attempt lockout.
     */
    private fun handleLegacyPairSetupPin(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-setup-pin blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            onShowPin(null)
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val plist = PlistCodec.decode(request.bodyBytes)
            if (legacyPin == null) newSrpSession()   // step 1 may arrive without a prior /pair-pin-start
            val result = legacyPin!!.handle(plist)
            if (result.failed) {
                val n = pairingStore?.recordFailedAttempt() ?: 0
                Logger.w("pair-setup-pin attempt failed ($n/$MAX_PAIR_ATTEMPTS)")
                onShowPin(null); legacyPin = null
                return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
            }
            if (result.complete) {
                pairingStore?.resetFailedAttempts()   // legitimate pairing clears the lockout counter
                pairingStore?.setPinTrusted()          // remembered so the code isn't asked again
                pinPaired = true                       // now allow pair-verify → streaming proceeds
                onShowPin(null); legacyPin = null
                Logger.i("PIN pairing complete — pair-verify now permitted")
            }
            RtspResponse(
                200, "OK",
                bodyBytes = PlistCodec.encode(result.reply!!),
                contentType = "application/x-apple-binary-plist",
                protocol = request.responseProtocol()
            )
        } catch (e: Exception) {
            Logger.e("pair-setup-pin failed", e)
            onShowPin(null); legacyPin = null
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /** POST /fp-setup — FairPlay: 16-byte phase 1 → 142-byte reply; 164-byte phase 2 → 32-byte reply. */
    private fun handleFpSetup(request: RtspRequest): RtspResponse = try {
        val fp = fairPlay!!
        val b = request.bodyBytes
        // Diagnostics: byte 4 is the FairPlay version (0x03 mirroring/Safari, 0x02 Apple Music audio);
        // for phase 1, byte 14 is the mode (0..3). Confirms which path a given sender uses.
        val verMode = if (b.size >= 16) " v=0x%02x mode=%d".format(b[4].toInt() and 0xFF, b[14].toInt() and 0xFF)
                      else if (b.size >= 5) " v=0x%02x".format(b[4].toInt() and 0xFF) else ""
        val body = when (b.size) {
            16 -> fp.setup(b)
            164 -> fp.handshake(b)
            else -> throw IllegalArgumentException("unexpected fp-setup size ${b.size}")
        }
        Logger.i("fp-setup phase (${b.size}B in → ${body.size}B out)$verMode OK")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("fp-setup failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * AirPlay 2 mirroring SETUP (binary plist). Two messages arrive on one connection:
     *  - msg 1 carries `ekey`+`eiv`+`timingPort` → FairPlay-decrypt the AES key, hand it
     *    (with the pairing secret) to the receiver, reply with event/timing ports.
     *  - msg 2 carries `streams`[type 110] → start the mirror data server, reply with its port.
     */
    private fun handleMirrorSetup(request: RtspRequest): RtspResponse = try {
        val req = PlistCodec.decode(request.bodyBytes)
        Logger.i("mirror SETUP plist: " + req.entries.joinToString { (k, v) ->
            "$k=" + when (v) {
                is ByteArray -> "${v.size}B"
                is List<*> -> "list[${v.size}]"
                else -> v.toString()
            }
        })
        val response = mutableMapOf<String, Any?>()

        isMirrorSession = true

        // Extract device name + type from mirror SETUP plist (msg 1 carries name/model)
        val plistName = req["name"] as? String
        val plistModel = req["model"] as? String
        if (plistName != null || plistModel != null) {
            val dtype = when {
                plistModel?.startsWith("iPhone") == true -> SenderDeviceType.IPHONE
                plistModel?.startsWith("iPad") == true   -> SenderDeviceType.IPAD
                plistModel?.startsWith("Mac") == true || plistModel?.startsWith("iMac") == true
                    || plistModel?.startsWith("MacBook") == true -> SenderDeviceType.MAC
                else -> SenderDeviceType.UNKNOWN
            }
            val existing = currentSession
            if (existing != null) {
                currentSession = existing.copy(
                    senderName = plistName ?: existing.senderName,
                    senderDeviceType = if (dtype != SenderDeviceType.UNKNOWN) dtype else existing.senderDeviceType
                )
            } else {
                // Mirror-only session (no ANNOUNCE) — build minimal session
                currentSession = SessionDescription(
                    hasVideo = true, hasAudio = false,
                    senderName = plistName ?: DEFAULT_SENDER_NAME,
                    senderDeviceType = dtype
                )
            }
        }

        val ekey = req["ekey"] as? ByteArray
        if (ekey != null) {
            val aesKey = fairPlay!!.decrypt(ekey)
            val ecdhSecret = pairingSession?.sharedSecret ?: error("mirror SETUP before pair-verify")
            val aesIv = (req["eiv"] as? ByteArray) ?: ByteArray(16)
            val senderTimingPort = (req["timingPort"] as? Long)?.toInt() ?: 0
            val remoteAddr = currentRemoteAddress ?: error("mirror SETUP without remote address")
            val (eventPort, timingPort) = onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            response["eventPort"] = eventPort.toLong()
            response["timingPort"] = timingPort.toLong()
            Logger.i("mirror SETUP keys OK — eventPort=$eventPort timingPort=$timingPort (sender timing $senderTimingPort)")
        }

        val streams = req["streams"] as? List<*>
        if (streams != null) {
            val resStreams = streams.mapNotNull { s ->
                val stream = s as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Long)?.toInt()) {
                    110 -> {
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        Logger.i("mirror stream type=110 streamConnectionID=$scid dataPort=$dataPort")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        // Realtime-audio stream fields (codec type ct, samples-per-frame spf, latencies, …).
                        Logger.d("mirror stream type=96 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        // How far behind the sender's own timeline it expects us to play. Ignoring it
                        // made playback run ahead of the phone (audio led its on-screen lyrics).
                        val latencyMin = (stream["latencyMin"] as? Long)?.toInt() ?: DEFAULT_LATENCY_SAMPLES
                        val (dataPort, controlPort) = onMirrorAudioStart(sr, ch, ct, spf, latencyMin)
                        activeStreamTypes.add(96)
                        currentSession?.let { onSenderInfoChanged(it.senderName, it.senderDeviceType) }
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented, but the macOS
                        // Music stream stays FairPlay-encrypted (undecryptable), so playback is not
                        // wired. Stream fields (codec ct, audioFormat, shk/shiv, latencies) logged for ref.
                        Logger.d("buffered audio stream type=103 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val dataPort = onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        Logger.i("buffered audio stream type=103 dataPort=$dataPort")
                        mapOf("type" to 103L, "dataPort" to dataPort.toLong())
                    }
                    else -> {
                        Logger.i("mirror SETUP stream dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; else -> v.toString() }
                        })
                        null
                    }
                }
            }
            response["streams"] = resStreams
        }

        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        Logger.e("mirror SETUP failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        val headers = mutableMapOf(
            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
        )
        request.headers["Apple-Challenge"]?.let { challenge ->
            val response = appleChallengeResponse(challenge)
            if (response != null) {
                headers["Apple-Response"] = response
            } else {
                Logger.e("OPTIONS carried an Apple-Challenge we could not answer — the sender will hang up")
            }
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK", headers = headers)
    }

    /**
     * Answers a legacy RAOP `Apple-Challenge`. Senders that send one treat a missing `Apple-Response`
     * as an unauthenticated receiver and drop the connection right after OPTIONS.
     */
    private fun appleChallengeResponse(challengeB64: String): String? {
        // Every branch here logs. A missing Apple-Response is not a soft failure: macOS Music sends
        // OPTIONS with a challenge, and on a reply without the header it closes the connection
        // immediately and never sends ANNOUNCE. In the log that reads as "RTSP OPTIONS *" followed
        // by "Client disconnected" with no explanation whatsoever, which is exactly how it looked.
        val challenge = runCatching { Base64Util.decode(challengeB64.trim()) }.getOrNull() ?: run {
            Logger.w("Apple-Challenge: not valid base64 ('$challengeB64')")
            return null
        }
        val local = currentLocalAddress ?: run {
            Logger.w("Apple-Challenge: local address unknown — cannot sign")
            return null
        }
        val mac = NetworkUtils.getMacAddress().split(":")
            .mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (mac.size != 6) {
            Logger.w("Apple-Challenge: MAC unusable (${NetworkUtils.getMacAddress()}) — cannot sign")
            return null
        }
        val signed = RaopRsa.signChallenge(challenge, local.address, mac) ?: run {
            Logger.w("Apple-Challenge: RSA signing failed")
            return null
        }
        Logger.i("Apple-Challenge answered (${challenge.size}B challenge → ${signed.size}B signature)")
        // The sender expects the Base64 without padding — the AirPort Express omitted it and some
        // senders compare the string, not the decoded bytes.
        return Base64Util.encode(signed).trimEnd('=')
    }

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        val ua = request.headers["User-Agent"]
        val uaName = extractSenderName(ua)
        val uaType = extractDeviceType(ua)
        currentSession = parsed.copy(
            senderName = pendingDeviceName ?: uaName,
            senderDeviceType = if (pendingDeviceType != SenderDeviceType.UNKNOWN) pendingDeviceType else uaType
        )
        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    private fun extractDeviceType(userAgent: String?): SenderDeviceType {
        if (userAgent.isNullOrBlank()) return SenderDeviceType.UNKNOWN
        val ua = userAgent.lowercase()
        Logger.i("RTSP User-Agent: $userAgent")
        return when {
            "iphone" in ua -> SenderDeviceType.IPHONE
            "ipad" in ua   -> SenderDeviceType.IPAD
            "mac" in ua || "macbook" in ua || "imac" in ua -> SenderDeviceType.MAC
            // Apple Music on Mac sends "iTunes" or "Music" as User-Agent prefix
            "itunes" in ua || "music" in ua -> SenderDeviceType.MAC
            else -> SenderDeviceType.UNKNOWN
        }
    }

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            // Three ports, three underscored names. Every part of this line used to be wrong and it
            // cost us macOS Music entirely:
            //
            //  - `timing-port` with a HYPHEN is not a token Music parses. It looked plausible in a
            //    log and was silently discarded.
            //  - `control_port` was absent, so Music addressed its first sync packet to the port it
            //    had asked for rather than one we bound. The kernel replied ICMP port-unreachable
            //    and Music tore the session down ~40ms after RECORD, before sending any audio. The
            //    symptom was silence, so this was mistaken for a FairPlay key failure for weeks.
            //  - `client_port` echoed OUR port back at the sender instead of the one it requested.
            //
            // The client's own ports come from its Transport header; falling back to ours is only
            // to keep the response well-formed if a sender omits them.
            val req = parseTransport(request.headers["Transport"])
            "RTP/AVP/UDP;unicast;mode=record;" +
                "client_port=${req.clientPort ?: AUDIO_RTP_PORT};" +
                "server_port=$AUDIO_RTP_PORT;" +
                "control_port=${RaopControlHandler.CONTROL_PORT};" +
                "timing_port=${TimingHandler.TIMING_PORT}"
        }

        // Logger.i, not d: Fire OS drops debug for this package, and this line is the first thing
        // worth seeing when a sender hangs up straight after RECORD.
        Logger.i("SETUP #$setupCount — request transport: ${request.headers["Transport"]}")
        Logger.i("SETUP #$setupCount — reply transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        // AirPlay 2 mirroring has no ANNOUNCE/SDP — RECORD just acknowledges the session.
        onPlaybackPaused(false)
        if (isMirrorSession) {
            // RTP-Info: seq=<n>;rtptime=<t> is the sender's start anchor for this stream. Logged so
            // A/V alignment can be tied to the sender's clock rather than to arrival time.
            request.headers["RTP-Info"]?.let { Logger.i("RECORD RTP-Info: $it") }
            stamp("RECORD")
            Logger.i("RECORD (mirror session) — OK")
            return RtspResponse(
                statusCode = 200, statusMessage = "OK",
                headers = mapOf("Audio-Latency" to "0"),
                protocol = request.responseProtocol()
            )
        }
        var session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        // RAOP audio (Apple Music) wraps the AES key with FairPlay (SDP `fpaeskey`). Unwrap it via the
        // fp-setup session into the real 16-byte key so the AudioPlayer can AES-CBC-decrypt the stream.
        val fpKey = session.fpAesKey
        if (fpKey != null && session.aesKey == null) {
            val realKey = runCatching { fairPlay?.decrypt(fpKey) }
                .onFailure { Logger.w("RAOP FairPlay audio-key decrypt failed (${fpKey.size}B): ${it.message}") }
                .getOrNull()
            if (realKey != null) {
                // The phase-1 mode is in here because on v2 it decides whether this key is correct:
                // we answer every mode with the mode-2 reply, so anything else yields a wrong key.
                Logger.i("RAOP FairPlay (v0x%02x mode=%d) audio key decrypted → ${realKey.size}B AES key, iv=${session.aesIv?.size ?: 0}B"
                    .format(fairPlay?.negotiatedVersion ?: 0, fairPlay?.negotiatedMode ?: -1))
                session = session.copy(aesKey = realKey)
                currentSession = session
            }
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly}, encrypted=${session.isAudioEncrypted})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN. A TEARDOWN may target SPECIFIC streams (AirPlay 2 dynamic stream removal —
     * e.g. macOS drops the audio stream when playback stops) or the whole session. If the body lists
     * streams and they're audio-only, we stop just the audio and KEEP the mirror running; otherwise
     * we tear the whole session down. (Previously any TEARDOWN killed the mirror, so stopping audio
     * on the Mac ended screen mirroring entirely.)
     */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        val streamTypes = parseTeardownStreamTypes(request.bodyBytes)
        if (streamTypes != null && streamTypes.isNotEmpty()) {
            // Stream-level teardown: stop ONLY the listed streams and keep the session (keys, NTP,
            // event channel) alive, even when no streams remain. iOS renegotiates by removing a
            // stream and immediately adding a replacement on the same session — it announces
            // supportsDynamicStreamID and sends POST /audioMode just before. Treating an emptied
            // stream list as "session over" tore the session down mid-renegotiation and the sender
            // gave up, which looked like the receiver killing itself the instant audio started.
            // When the sender really is finished it closes the socket, and that path already does
            // the full cleanup.
            if (streamTypes.contains(96)) { onMirrorAudioStop(); activeStreamTypes.remove(96) }
            if (streamTypes.contains(110)) { onMirrorVideoStop(); activeStreamTypes.remove(110) }
            if (streamTypes.contains(103)) { onBufferedAudioStop(); activeStreamTypes.remove(103) }
            Logger.i("TEARDOWN streams=$streamTypes — stopped those, session continues (active=$activeStreamTypes)")
            return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        } else {
            Logger.i("TEARDOWN (session, body=${request.bodyBytes.size}B) — streaming stopping")
        }
        activeStreamTypes.clear()
        onStreamingStopped()
        return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
    }

    /** Parses the `streams` list from a TEARDOWN body, returning the stream `type`s, or null. */
    private fun parseTeardownStreamTypes(body: ByteArray): List<Int>? = runCatching {
        if (body.isEmpty()) return null
        val streams = PlistCodec.decode(body)["streams"] as? List<*> ?: return null
        streams.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
    }.getOrNull()

    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        val query = request.body.trim()
        Logger.i("GET_PARAMETER body='$query'")
        // macOS queries "volume" during setup and aborts if it gets no value back. Report the
        // last value the sender set so its volume slider reflects the receiver.
        return if (query.startsWith("volume")) {
            RtspResponse(
                statusCode = 200, statusMessage = "OK",
                body = "volume: %.6f\r\n".format(currentVolume),
                contentType = "text/parameters",
                protocol = request.responseProtocol()
            )
        } else {
            RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        }
    }

    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        val body = request.body
        val contentType = request.headers["Content-Type"]?.lowercase() ?: ""
        // Text bodies carry "volume: <dB>"; binary bodies carry DMAP now-playing metadata or artwork.
        when {
            body.startsWith("volume") -> {
                body.substringAfter(":").trim().toFloatOrNull()?.let { v ->
                    currentVolume = v
                    onVolume(v)
                    Logger.i("SET_PARAMETER volume=$v")
                }
            }
            body.startsWith("progress:") || body.contains("\nprogress:") -> {
                val raw = body.lineSequence().firstOrNull { it.startsWith("progress:") }
                    ?.substringAfter(":")?.trim() ?: return RtspResponse(200, "OK")
                val parts = raw.split("/")
                if (parts.size == 3) {
                    val start = parts[0].toLongOrNull() ?: return RtspResponse(200, "OK")
                    val curr  = parts[1].toLongOrNull() ?: return RtspResponse(200, "OK")
                    val end   = parts[2].toLongOrNull() ?: return RtspResponse(200, "OK")
                    // RTP clock is 44100 Hz; handle 32-bit wrap-around with unsigned subtraction
                    val pos = ((curr - start) and 0xFFFFFFFFL) / 44100.0
                    val dur = ((end  - start) and 0xFFFFFFFFL) / 44100.0
                    Logger.i("SET_PARAMETER progress: pos=${pos.toInt()}s dur=${dur.toInt()}s")
                    // On a track change the sender can emit the outgoing track's position against
                    // the incoming track's duration (observed: pos=165s dur=125s), which drives the
                    // progress bar past 100%. Drop the impossible pair and wait for the next push.
                    if (dur > 0 && pos > dur) {
                        Logger.d("Ignoring stale progress (pos=${pos.toInt()}s > dur=${dur.toInt()}s)")
                    } else {
                        onPlaybackPosition(pos, dur)
                        onPlaybackAnchor(start, end)
                    }
                }
            }
            contentType.contains("text/parameters") || body.contains("position:") -> {
                val params = body.lines().associate { line ->
                    val i = line.indexOf(':'); if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else "" to ""
                }
                val pos = params["position"]?.toDoubleOrNull() ?: return RtspResponse(200, "OK")
                val dur = params["duration"]?.toDoubleOrNull() ?: 0.0
                Logger.i("SET_PARAMETER position=$pos duration=$dur")
                onPlaybackPosition(pos, dur)
            }
            contentType.startsWith("image/") -> {
                // Album artwork (image/jpeg, image/png). A zero-length body clears it.
                onArtwork(request.bodyBytes)
                Logger.i("SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
            }
            contentType.contains("dmap") || looksLikeDmap(request.bodyBytes) -> {
                val meta = DmapParser.parseNowPlaying(request.bodyBytes)
                onNowPlayingMetadata(meta.title, meta.artist, meta.album, meta.genre, meta.composer, meta.year, meta.durationMs)
                if (meta.title != lastLoggedNpTitle || meta.artist != lastLoggedNpArtist) {
                    lastLoggedNpTitle = meta.title; lastLoggedNpArtist = meta.artist
                    Logger.i("SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}' genre='${meta.genre}' dur=${meta.durationMs?.div(1000)}s")
                }
            }
            else -> Logger.d("SET_PARAMETER unhandled ct='$contentType' body=${body.take(120)}")
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Heuristic: a DMAP body starts with the `mlit` listing-item container tag. */
    private fun looksLikeDmap(body: ByteArray): Boolean =
        body.size >= 8 && String(body, 0, 4, Charsets.US_ASCII) == "mlit"

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown/unhandled RTSP: ${request.method} ${request.uri} (${request.bodyBytes.size}B body)")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented", protocol = request.responseProtocol())
    }

    /**
     * Handles FLUSH — discard buffered media. **Not** a pause signal, despite the spec wording.
     *
     * Device logs settle this: iOS sends FLUSH immediately after RECORD at the start of every
     * stream, again on seek, and again on pause. Treating it as "paused" latched the UI at the
     * first note of playback. Pause is detected from the audio stream going silent instead —
     * this sender genuinely stops sending RTP while paused (see AudioStreamServer.AUDIO_IDLE_MS).
     */
    private fun handleFlush(@Suppress("UNUSED_PARAMETER") request: RtspRequest): RtspResponse {
        Logger.d("FLUSH — buffer flush")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        onPlaybackPaused(true)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        return when (val validation = PhotoHandler.validatePhoto(
            request.bodyBytes,
            request.headers["Content-Type"]
        )) {
            is PhotoValidation.Valid -> {
                onPhotoReceived(request.bodyBytes, validation.imageType)
                Logger.i("Photo received (${validation.imageType.mimeType}, ${request.bodyBytes.size} bytes)")
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    protocol = request.responseProtocol()
                )
            }
            is PhotoValidation.Invalid -> {
                Logger.w("Photo rejected: ${validation.reason}")
                RtspResponse(
                    statusCode = 400,
                    statusMessage = "Bad Request",
                    protocol = request.responseProtocol()
                )
            }
        }
    }

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        onPhotoCleared()
        Logger.i("Photo cleared")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            protocol = request.responseProtocol()
        )
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        // Binary-safe: build the header block as ASCII, then write the raw body bytes.
        // Content-Length must be the BYTE length (not String.length) so binary plists,
        // FairPlay payloads, and encrypted bodies are framed correctly.
        val wire = response.wireBody()
        val head = StringBuilder()
        head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        if (response.protocol.startsWith("RTSP")) {
            head.append("CSeq: $currentCSeq\r\n")
        }
        head.append("Server: AirTunes/220.68\r\n")
        response.contentType?.let { head.append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) ->
            head.append("$key: $value\r\n")
        }
        if (wire.isNotEmpty()) {
            head.append("Content-Length: ${wire.size}\r\n")
        }
        head.append("\r\n")
        outputStream.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (wire.isNotEmpty()) {
            outputStream.write(wire)
        }
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        /** Enough to see the command identifiers without flooding the log with one plist. */
        private const val SUPPORTED_COMMANDS_LOG_CHARS = 4000

        /** Skip the small placeholder message the sender leads with; dump the real list. */
        private const val COMMAND_DUMP_MIN_BYTES = 1000

        /** Enough of an undecodable entry to tell a plist, a protobuf and neither apart. */
        private const val COMMAND_SAMPLE_BYTES = 24
        private const val COMMAND_SAMPLE_CHARS = 300

        /** Leading bytes of a binary plist — "bplist0". */
        private val BPLIST_MAGIC = "bplist0".toByteArray(Charsets.US_ASCII)

        private const val RTSP_PORT = 7000

        // SRP PIN access control. macOS's AirPlay code-entry field is exactly 4 digits, so the PIN
        // must be 4 digits to be enterable. A 4-digit space is low-entropy, so the load-bearing
        // defense is the MAX_PAIR_ATTEMPTS lockout below (uniform random + hard attempt cap, NOT
        // length). The PIN is still uniformly random — no biased truncation.
        private const val PIN_DIGITS = 4
        private const val PIN_SPACE = 10_000        // 10^PIN_DIGITS
        private const val MAX_PAIR_ATTEMPTS = 10
        private const val BIND_MAX_ATTEMPTS = 12      // ~3s total — covers a quick stop→start restart
        private const val BIND_RETRY_MS = 250L
        private const val MAX_MESSAGE_BYTES = 65536
        private const val OCTET_STREAM = "application/octet-stream"
        private const val TIMING_PORT = 6002   // matches TimingHandler's UDP NTP port
        private const val SESSION_ID = "PhairPlaySession"
        private const val AUDIO_RTP_PORT = 6001

    /** The sender's own ports, as named in its SETUP Transport header. */
    data class ClientTransport(
        val clientPort: Int? = null,
        val controlPort: Int? = null,
        val timingPort: Int? = null,
    )

    /**
     * Parses an RTSP Transport header into the sender's three ports.
     *
     * Values may be a single port or a `n-m` range; only the first number is meaningful to us.
     * Anything unparseable becomes null rather than throwing — a malformed Transport is a reason to
     * fall back to defaults, not to fail the SETUP that the whole session depends on.
     */
    internal fun parseTransport(header: String?): ClientTransport {
        if (header.isNullOrBlank()) return ClientTransport()
        var client: Int? = null
        var control: Int? = null
        var timing: Int? = null
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            val port = part.substring(eq + 1).trim().substringBefore('-').toIntOrNull() ?: continue
            when (key) {
                "client_port" -> client = port
                // Senders are inconsistent about the separator here, so accept both spellings
                // rather than losing the port to punctuation.
                "control_port", "control-port" -> control = port
                "timing_port", "timing-port" -> timing = port
            }
        }
        return ClientTransport(client, control, timing)
    }

        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"

        /** Fallback presentation latency (samples @44.1kHz = 250ms) when SETUP omits latencyMin. */
        private const val DEFAULT_LATENCY_SAMPLES = 11025
    }
}

private fun RtspRequest.isPhotoRequest(): Boolean =
    uri.substringBefore("?") == PhotoHandler.PHOTO_PATH

private fun RtspRequest.responseProtocol(): String =
    if (protocol.startsWith("HTTP/")) protocol else "RTSP/1.0"

/** True if the body is an Apple binary plist (AirPlay 2 mirroring SETUP), vs legacy SDP. */
private fun RtspRequest.isPlistBody(): Boolean =
    bodyBytes.size >= 8 && String(bodyBytes, 0, 8, Charsets.US_ASCII) == "bplist00"
