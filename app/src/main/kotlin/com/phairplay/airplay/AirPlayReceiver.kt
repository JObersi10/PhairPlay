package com.phairplay.airplay

import android.content.Context
import android.view.Surface
import com.phairplay.airplay.handshake.AirPlayNtpClient
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.airplay.handshake.BufferedAudioServer
import com.phairplay.airplay.handshake.EventCipher
import com.phairplay.airplay.handshake.MediaRemote
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.airplay.handshake.MirrorStreamServer
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [PhairPlayService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [PhairPlayService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,
    /** Whether a previously PIN-paired sender may skip the code (AppSettings.rememberPinPairing). */
    private val rememberPinPairing: Boolean = true,
    /** User A/V-sync trim added on top of the sender's requested latency (AppSettings.audioDelayMs). */
    private val audioDelayMs: Int = 0,
    /** Extra delay for the beat animation only (AppSettings.beatDelayMs). */
    private val beatDelayMs: Int = 0,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * A sender just opened the control socket. Used to bring the Activity up early so its Surface
     * exists before the first video packet — see `RtspHandler.onSenderApproaching`.
     */
    private val onSenderApproaching: () -> Unit = {},
    /**
     * Offered every sender volume change (dB, −30…0). Return true if the level was applied to the
     * output device, in which case the software gain stays at unity so the two don't compound —
     * a 50% slider must not become 25% by being attenuated twice.
     */
    private val onVolumeRequest: (Float) -> Boolean = { false },
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([PhairPlayService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    private val onEnergyChanged: (Float) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {}
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = com.phairplay.airplay.handshake.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var positionTicker: kotlinx.coroutines.Job? = null

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var controlHandler: RaopControlHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    /** Deferred end-of-video, pending a possible renegotiation. See [scheduleMirrorVideoStop]. */
    @Volatile private var pendingVideoStop: kotlinx.coroutines.Job? = null

    /**
     * The event channel's encryption state and output stream, held so the TV remote can *send* on
     * it. The channel is receiver→sender: we issue the requests and the sender answers, which is
     * why the outbound half is the interesting one here (pyatv, on the sender side, notes it has to
     * swap its read/write keys for exactly this reason).
     */
    @Volatile private var eventCipher: EventCipher? = null
    @Volatile private var eventOutput: java.io.OutputStream? = null
    /** Serialises remote-command writes against the reply the read loop is decrypting. */
    private val eventWriteLock = Any()
    private val eventCseq = java.util.concurrent.atomic.AtomicInteger(1)
    /** MediaRemote commands the current sender said it would accept. Empty until it tells us. */
    @Volatile private var supportedRemoteCommands: Set<Int> = emptySet()
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var streamingStopped = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npSenderDeviceType = SenderDeviceType.UNKNOWN
    @Volatile private var npPaused = false
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npGenre: String? = null
    @Volatile private var npComposer: String? = null
    @Volatile private var npYear: Int? = null
    @Volatile private var npArtwork: ByteArray? = null
    /**
     * RTP timestamp of the current track's first sample, from the sender's progress push. The
     * receiver's own playback clock is in the same units, so position is the difference between
     * them — see [startPositionTicker].
     */
    @Volatile private var anchorStartTs = -1L

    @Volatile private var npPositionSec: Double = 0.0
    @Volatile private var npDurationSec: Double = 0.0
    @Volatile private var npDurationFromDmap: Double = 0.0

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startTimingHandler()
                startControlHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [PhairPlayService] stops or is destroyed.
     */
    /**
     * Ends the current sender's session, leaving the receiver advertising and ready for the next
     * connection. Used by Back during a stream — a full receiver restart re-advertised over mDNS
     * fast enough for the sender to reconnect on its own, so the stream never appeared to stop.
     */
    /** Abandons an in-progress PIN pairing and clears the code from the screen. */
    fun cancelPinPairing() {
        rtspHandler?.cancelPinPairing()
    }

    fun endSession() {
        Logger.i("Ending AirPlay session on user request")
        rtspHandler?.disconnectActiveClient()
        // Closing the RTSP control socket does not touch the UDP media servers — they are separate
        // sockets and keep receiving and playing whatever the sender is still transmitting, which is
        // why Back looked like it did nothing. Tear the media down explicitly.
        releaseMediaComponents()
        onStreamingStopped()
    }

    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            controlHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            releaseMediaComponents()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) {
        if (dacpClient.isAvailable) {
            dacpClient.sendCommand(command)
            return
        }
        // AirPlay 2 senders never advertise a DACP identity, so there is no legacy address to call.
        // Their control path is MediaRemote over the event channel.
        val mrp = DACP_TO_MEDIA_REMOTE[command]
        if (mrp == null) {
            Logger.i("Remote '$command' ignored — no DACP sender and no MediaRemote equivalent")
            return
        }
        sendMediaRemoteCommand(mrp)
    }

    /** True once *some* reverse-control path exists — legacy DACP or MediaRemote. */
    fun isRemoteControlAvailable(): Boolean =
        dacpClient.isAvailable || (eventCipher != null && supportedRemoteCommands.isNotEmpty())

    /**
     * Sends one MediaRemote command to the sender over the event channel.
     *
     * The message itself is settled: a `ProtocolMessage{type: SEND_COMMAND_MESSAGE}` carrying a
     * `SendCommandMessage{command}` (see [MediaRemote]). What is *not* settled is the plist wrapper
     * the AirPlay layer expects around it. The sender→receiver direction uses
     * `{type: updateMRSupportedCommands, params: {mrSupportedCommandsFromSender: […]}}`, so the
     * mirror of that naming is the best-supported reading, and it is what we send. The sender's
     * status line comes back through the read loop above and is logged, so a wrong guess shows up
     * as a concrete error rather than silence.
     *
     * @return false when there is no event channel or the sender did not advertise this command.
     */
    fun sendMediaRemoteCommand(command: Int): Boolean {
        val cipher = eventCipher
        val output = eventOutput
        if (cipher == null || output == null) {
            Logger.i("MediaRemote ${MediaRemote.name(command)} dropped — no event channel")
            return false
        }
        if (supportedRemoteCommands.isNotEmpty() && command !in supportedRemoteCommands) {
            Logger.i("MediaRemote ${MediaRemote.name(command)} dropped — sender does not support it")
            return false
        }
        val body = PlistCodec.encode(
            mapOf(
                "type" to "sendCommand",
                "params" to mapOf("mrCommandFromReceiver" to MediaRemote.encodeSendCommand(command)),
            )
        )
        val host = eventClientSocket?.inetAddress?.hostAddress?.substringBefore('%')
        val head = buildString {
            // HTTP/1.1, not RTSP/1.0. The first attempt used RTSP/1.0 to match the rest of our
            // session and drew no reply of any kind, not even an error — and a frame the far end
            // cannot parse as a request is the best explanation for total silence. `/command` is
            // an HTTP-style POST rather than an RTSP method, so the protocol token is the cheapest
            // candidate to flip. `Host` goes with it: an HTTP/1.1 request without one is malformed,
            // which would produce exactly the same silence for a second reason.
            append("POST /command HTTP/1.1\r\n")
            append("Host: ${host ?: "localhost"}\r\n")
            append("CSeq: ${eventCseq.getAndIncrement()}\r\n")
            append("Content-Type: application/x-apple-binary-plist\r\n")
            append("Content-Length: ${body.size}\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        // Off the main thread: this is called straight from onKeyDown, and a socket write there is
        // a NetworkOnMainThreadException — which is thrown *before* a single byte leaves, so the
        // command silently did nothing while looking like it had been attempted.
        scope.launch(Dispatchers.IO) {
            synchronized(eventWriteLock) {
                runCatching { cipher.write(output, head + body) }
                    .onSuccess { Logger.i("MediaRemote ${MediaRemote.name(command)} sent (${body.size}B plist)") }
                    .onFailure { Logger.e("MediaRemote ${MediaRemote.name(command)} failed", it) }
            }
        }
        return true
    }

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    /**
     * Binds the RAOP control port.
     *
     * Started alongside the timing handler and kept up for the receiver's whole life, NOT per
     * session: macOS Music sends its first sync packet within milliseconds of RECORD, and a port
     * that is bound lazily is still closed when that packet lands. An ICMP port-unreachable there
     * makes Music abandon the session immediately.
     */
    private fun startControlHandler() {
        controlHandler = RaopControlHandler().also { it.start(scope) }
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            onStateChange = { state -> emitState(state) },
            onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { onStreamingStopped() },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onSupportedRemoteCommands = { supportedRemoteCommands = it },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf, latency -> startMirrorAudio(sampleRate, channels, ct, spf, latency) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onVolume = { v ->
                // 0f is 0 dB, i.e. unity gain — the right software setting when the hardware is
                // doing the attenuation for us.
                audioServer?.setVolume(if (onVolumeRequest(v)) 0f else v)
            },
            onNowPlayingMetadata = { title, artist, album, genre, composer, year, durationMs ->
                val changed = title != npTitle || artist != npArtist || album != npAlbum
                npTitle = title; npArtist = artist; npAlbum = album
                npGenre = genre; npComposer = composer; npYear = year
                if (durationMs != null && durationMs > 0) npDurationFromDmap = durationMs / 1000.0
                if (changed) emitNowPlaying()
            },
            onArtwork = { bytes ->
                npArtwork = bytes.takeIf { it.isNotEmpty() }
                emitNowPlaying()
            },
            onPlaybackPosition = { pos, dur ->
                // The sender is authoritative, so take its value and show it. There used to be a
                // 2-second dead zone here, which is precisely wrong while seeking: the TV remote
                // sends the sender fast-forwarding, our local clock keeps advancing at 1x from a
                // now-stale anchor, and the correcting push was being swallowed for being close to
                // the wrong number we were already showing.
                val changed = displayedSecond(pos) != displayedSecond(npPositionSec) || dur != npDurationSec
                npPositionSec = pos; npDurationSec = dur
                if (changed) emitNowPlaying()
            },
            onPlaybackAnchor = { startTs, _ -> anchorStartTs = startTs },
            onVideoPlay = { url, start -> startUrlVideo(url, start) },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            rememberPinPairing = rememberPinPairing,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) },
            // FLUSH pauses, RECORD resumes — the two RTSP verbs the spec defines for exactly this.
            // The session-start FLUSH is harmless because RECORD follows it immediately.
            onPlaybackPaused = { paused -> npPaused = paused; emitNowPlaying() },
            onSenderInfoChanged = { name, type ->
                if (name.isNotBlank()) npSenderName = name
                npSenderDeviceType = type
                emitNowPlaying()
            },
            onSenderApproaching = { onSenderApproaching() }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        streamingStopped = false
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")

        scope.launch {
            try {
                if (session.hasVideo) startVideoDecoder(session)
                if (session.hasAudio) startAudioPlayer(session)
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                npSenderName = session.senderName.ifBlank { npSenderName }
                npSenderDeviceType = session.senderDeviceType
                videoPlaying = session.hasVideo
                audioPlaying = session.hasAudio
                emitNowPlaying()
                // Notify PhairPlayService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends (TEARDOWN received or socket closed).
     *
     * Releases media components and re-advertises so the device reappears
     * in sender pickers immediately.
     */
    private fun onStreamingStopped() {
        if (streamingStopped) return
        streamingStopped = true
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(AUDIO_RTP_PORT)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when audioSocket.close() is called — expected
                if (audioSocket != null) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    /**
     * Pre-encryption behaviour, kept only for senders that never ran pair-verify: read cleartext
     * RTSP and answer 200. Modern senders encrypt, so this path should not normally be taken.
     */
    private fun drainPlaintextEventChannel(input: java.io.InputStream, output: java.io.OutputStream) {
        val reader = RtspRequestReader(EVENT_MAX_BYTES, EVENT_MAX_BYTES)
        while (true) {
            val req = reader.read(input) ?: return
            Logger.i("Event channel (plaintext) ${req.method} ${req.uri}")
            val proto = if (req.protocol.startsWith("HTTP")) "HTTP/1.1" else "RTSP/1.0"
            val cseq = req.headers["CSeq"] ?: "0"
            output.write("$proto 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        val event = ServerSocket(0)
        eventSocket = event
        // Accept and *answer* on the event connection.
        //
        // This used to just drain the socket. macOS tolerates that — it only needs the advertised
        // event port to be connectable — but iOS sends requests here after RECORD and waits for
        // replies before it will send the SETUP carrying `streams`. Silence meant the iPhone sat
        // for ever on "connecting" with a black screen while a Mac mirrored to the same build
        // perfectly. We don't act on the contents; answering 200 is what unblocks the sender.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: sender connected from ${s.inetAddress.hostAddress}")
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    // The channel is encrypted from the first byte, keyed off the pair-verify
                    // secret. Reading it as plaintext (what we did before) yields ciphertext that
                    // never parses, so the replies went nowhere. If we have no secret — a sender
                    // that skipped pair-verify — fall back to the old plaintext behaviour rather
                    // than dropping a session that used to work.
                    val cipher = runCatching { EventCipher(ecdhSecret) }.getOrNull()
                    if (cipher == null) {
                        Logger.w("Event channel: no cipher — falling back to plaintext framing")
                        drainPlaintextEventChannel(input, output)
                        return@use
                    }
                    eventCipher = cipher
                    eventOutput = output
                    while (isActive) {
                        val frame = runCatching { cipher.read(input) }.getOrElse { e ->
                            Logger.w("Event channel decrypt failed (${e.message}) — closing")
                            null
                        } ?: break
                        val req = RtspRequestReader(EVENT_MAX_BYTES, EVENT_MAX_BYTES)
                            .read(frame.inputStream())
                        if (req == null) {
                            // Not a request — most often the sender's *answer* to a command we sent.
                            // Surfacing the status line is the only feedback we get on whether the
                            // command was understood, so log it rather than the byte count alone.
                            val head = frame.toString(Charsets.US_ASCII).substringBefore("\r\n")
                            if (head.startsWith("RTSP/") || head.startsWith("HTTP/")) {
                                Logger.i("Event channel reply: $head")
                            } else {
                                Logger.i("Event channel: ${frame.size}B frame that is not an RTSP request")
                            }
                            continue
                        }
                        Logger.i("Event channel ${req.method} ${req.uri}")
                        val proto = if (req.protocol.startsWith("HTTP")) "HTTP/1.1" else "RTSP/1.0"
                        val cseq = req.headers["CSeq"] ?: "0"
                        val reply = "$proto 200 OK\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n"
                        synchronized(eventWriteLock) {
                            runCatching { cipher.write(output, reply.toByteArray(Charsets.US_ASCII)) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
                eventCipher = null
                eventOutput = null
                supportedRemoteCommands = emptySet()
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged("AirPlay")
        emitState(ProtocolState.CONNECTED)
        // NOTE: do not wait for the video Surface here. It cannot exist yet — the Surface belongs to
        // a SurfaceView the Activity only makes visible in response to the CONNECTED state emitted
        // on the line above, so a wait at this point always runs its full timeout and then adds that
        // delay to the sender's SETUP round trip. MirrorStreamServer already handles a late Surface.
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    private fun startMirrorStream(streamConnectionId: Long): Int {
        // A stream is back: whatever close we were waiting out was a renegotiation, not an ending.
        pendingVideoStop?.let { it.cancel(); Logger.i("Video stream renegotiated — cancelling pending stop") }
        pendingVideoStop = null
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        return MirrorStreamServer(
            aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight,
            // A sender that goes quiet without a TEARDOWN (phone screen off, or an app taking over
            // with its own fullscreen player) used to leave the session "live" with its last frame
            // frozen on the TV. Tear it down ourselves.
            onConnectionEnded = { scheduleMirrorVideoStop() },
        )
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int, latencyMinSamples: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket,
            latencyMinSamples = latencyMinSamples + (audioDelayMs * sampleRate / 1000),
            extraDelayMs = audioDelayMs.toLong(),
            beatDelayMs = beatDelayMs.toLong(),
            onEnergy = { e -> onEnergyChanged(e) },
            // Apple Music never sends RTSP PAUSE, and FLUSH fires at stream start too, so it
            // can't mean "paused". The stream itself is the signal: this sender stops sending
            // RTP entirely while paused and resumes the instant playback does.
            onAudioIdle = { idle -> npPaused = idle; emitNowPlaying() })
            .also { audioServer = it; it.start(scope); startPositionTicker() }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    /**
     * The sender closed the video connection. Wait before believing it is over.
     *
     * A close is not the same as an ending. Locking the phone closes the connection while the
     * session stays alive on the sender — it still says "connected" — and an app switching to its
     * own player closes one stream and negotiates another a beat later. Acting immediately turned
     * both into a quit, which is worse than the frozen frame it replaced. Any new mirror stream
     * within the grace window cancels this.
     */
    private fun scheduleMirrorVideoStop() {
        pendingVideoStop?.cancel()
        pendingVideoStop = scope.launch {
            delay(MIRROR_RESUME_GRACE_MS)
            Logger.i("No video stream returned within ${MIRROR_RESUME_GRACE_MS}ms — ending video")
            stopMirrorVideo()
        }
    }

    private fun stopMirrorVideo() {
        pendingVideoStop?.cancel()
        pendingVideoStop = null
        // Both a TEARDOWN and the connection ending can land here for one session; the second call
        // has nothing to do and must not re-emit state.
        if (!videoPlaying && mirrorServer == null) return
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        // Nothing left playing: drop back to advertising so the overlay actually leaves the screen.
        // emitNowPlaying alone does not do it — with no metadata it emits null, and the service
        // reads "null while CONNECTED" as "a video session is running", so stopping mirroring on
        // the phone left the TV sitting on a frozen last frame. The RTSP session itself stays up,
        // so an iOS renegotiation re-emits CONNECTED and the picture comes straight back.
        if (!audioPlaying) {
            Logger.i("Mirror video stopped and nothing else is playing — session idle")
            emitState(ProtocolState.ADVERTISING)
        } else {
            Logger.i("Mirror video stream stopped (audio playback continues)")
        }
    }

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     */
    private fun startUrlVideo(url: String, startFraction: Double) {
        onSenderNameChanged("AirPlay")
        // Marks this as a video session, so emitNowPlaying() won't put the audio-only card over the
        // picture if the sender also sends track metadata for what it handed us.
        videoPlaying = true
        emitState(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            context = context,
            surfaceProvider = videoSurfaceProvider,
            onEnded = { stopUrlVideo() }
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction)
        Logger.i("AirPlay URL video started: $url (start=$startFraction)")
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    private fun stopUrlVideo() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        videoPlaying = false
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    private fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /** Clears the video NAL callback, closes the audio socket, and releases media components. */
    private fun releaseMediaComponents() {
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
        mirrorServer?.stop()
        mirrorServer = null
        positionTicker?.cancel(); positionTicker = null
        anchorStartTs = -1L
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventSocket = null
        // Clear the FairPlay/ECDH keys on FULL teardown only. This method runs on a genuine session
        // end (last-stream / session TEARDOWN, or control-connection close) — NOT on a per-stream
        // teardown, which goes through stopMirrorAudio/stopMirrorVideo and leaves the keys intact so
        // macOS can re-add a dynamic stream on the same live session without re-sending keys (that
        // dynamic-readd path is why the keys must survive a stream stop). Clearing here prevents a
        // brand-new control connection from reusing a previous session's stale keys.
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
        // Session fully torn down — clear now-playing so the UI leaves the audio card.
        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
    }

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(
                senderName = npSenderName,
                senderDeviceType = npSenderDeviceType,
                title = npTitle, artist = npArtist, album = npAlbum,
                genre = npGenre, composer = npComposer, year = npYear,
                artwork = npArtwork,
                positionSec = npPositionSec,
                durationSec = if (npDurationSec > 0) npDurationSec else npDurationFromDmap,
                paused = npPaused
            ) else null
        )
    }

    /**
     * Keeps [npPositionSec] locked to the audio actually being played.
     *
     * Senders push progress every few seconds at best, and only in whole RTP frames against a
     * track-relative anchor, so the UI used to dead-reckon from wall-clock time between pushes and
     * accumulate a couple of seconds of error. Reading the receiver's own audio clock instead makes
     * position exact and self-correcting: it advances at precisely the rate samples leave the
     * speaker, and holds still during a pause without needing to be told.
     */
    /**
     * The whole second the UI renders for a position. Emitting on a change of *this* rather than on
     * a raw delta keeps the counter moving every second without redrawing four times a second for
     * digits nobody sees.
     */
    private fun displayedSecond(seconds: Double): Long = seconds.toLong()

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                val start = anchorStartTs
                val playing = audioServer?.playingRtpTimestamp() ?: -1L
                if (start < 0 || playing < 0) continue
                val elapsed = ((playing - start) and 0xFFFFFFFFL) / 44100.0
                // A track change lands the anchor and the clock on different timelines for a moment;
                // an impossible position is that, not a seek, so wait for the next progress push.
                if (elapsed < 0 || (npDurationSec > 0 && elapsed > npDurationSec + 1)) continue
                // Emit whenever the second the user can actually see changes. The old test was
                // "moved more than 0.25s", which at normal speed is exactly one tick's worth of
                // progress — so it sat on its own threshold and dropped updates unpredictably,
                // making the counter stutter instead of counting.
                val changed = displayedSecond(elapsed) != displayedSecond(npPositionSec)
                npPositionSec = elapsed
                if (changed) emitNowPlaying()
            }
        }
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    private fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null
        npGenre = null; npComposer = null; npYear = null
        npArtwork = null; npDurationFromDmap = 0.0; npPaused = false
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        // Re-arm the one-shot teardown guard whenever a new session begins. onStreamingStarted()
        // also clears it, but that only runs for legacy SDP/RECORD sessions — AirPlay 2 mirroring
        // reaches CONNECTED via startMirrorKeys() and never touched the flag. The result was that
        // the second and every later mirror session hit `if (streamingStopped) return` in
        // onStreamingStopped(), never emitted ADVERTISING, and left the UI stuck on a black
        // StreamingScreen. Resetting here covers every path that can start a session.
        if (state == ProtocolState.CONNECTED) streamingStopped = false
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {

        /**
         * DACP command → MediaRemote `Command`, so one TV-remote key press works against either
         * kind of sender. Volume is absent on purpose: AirPlay volume is an RTSP `SET_PARAMETER`,
         * not a transport command, and it already has its own path.
         */
        private val DACP_TO_MEDIA_REMOTE = mapOf(
            DacpClient.CMD_PLAY_PAUSE to MediaRemote.TOGGLE_PLAY_PAUSE,
            DacpClient.CMD_PLAY_RESUME to MediaRemote.PLAY,
            DacpClient.CMD_NEXT to MediaRemote.NEXT_TRACK,
            DacpClient.CMD_PREV to MediaRemote.PREVIOUS_TRACK,
            DacpClient.CMD_FF to MediaRemote.BEGIN_FAST_FORWARD,
            DacpClient.CMD_FF_STOP to MediaRemote.END_FAST_FORWARD,
            DacpClient.CMD_REW to MediaRemote.BEGIN_REWIND,
            DacpClient.CMD_REW_STOP to MediaRemote.END_REWIND,
        )

        /**
         * How long a closed video connection is given to come back before the session is ended.
         * Long enough to cover locking the phone briefly or an app handing over to its own player;
         * short enough that a real disconnect does not leave the TV waiting.
         */
        private const val MIRROR_RESUME_GRACE_MS = 8_000L

        /** Event-channel requests are tiny plists; this is a sanity bound, not a real limit. */
        private const val EVENT_MAX_BYTES = 256 * 1024

        /** How often position is re-read from the audio clock. Fast enough to look continuous. */
        private const val POSITION_TICK_MS = 250L

        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
