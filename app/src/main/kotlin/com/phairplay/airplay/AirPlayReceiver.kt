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
    /** AudioTrack hardware buffer in ms (AppSettings.audioBufferMs). */
    private val audioBufferMs: Int = com.phairplay.settings.AppSettings.DEFAULT_AUDIO_BUFFER_MS,
    /**
     * Visual-only delay owed to the current output — see AudioRoute.BLUETOOTH_COMPENSATION_MS.
     * Not a user setting; derived from where the audio is going.
     */
    private val beatDelayMs: Int = 0,
    /**
     * How many senders may be served at once. Forwarded to [RtspHandler]; 1 reproduces the original
     * one-sender-at-a-time policy exactly. See `docs/MULTI_SCREEN.md`.
     */
    private val maxSessions: Int = 1,
    /** Fired whenever the set of tiles with a live mirror changes, so the UI can lay that many out. */
    private val onMirrorSlotsChanged: (Set<Int>) -> Unit = {},
    /** Decoded size for a given tile, so it can letterbox to its own stream's aspect. */
    private val onMirrorSizeChanged: (slot: Int, width: Int, height: Int) -> Unit = { _, _, _ -> },
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: (slot: Int) -> Surface?,
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
     * True while a video stream is actually negotiated and running.
     *
     * The UI shows the video Surface on true and its own screen on false, so this must come from
     * the receiver rather than be guessed from now-playing metadata — an audio-only session that
     * never reports a track would otherwise sit on a black Surface forever.
     */
    private val onVideoPlayingChanged: (Boolean) -> Unit = {},
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
    /** Bass, mid and treble levels 0..1 — see AudioStreamServer.onBands. */
    private val onBandsChanged: (FloatArray) -> Unit = {},
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
    /**
     * One mirror server per tile.
     *
     * A single field here was the second half of the one-sender-at-a-time limit (the first being
     * RtspHandler's shared connection state): a second sender's SETUP overwrote this reference, and
     * the first sender's server then kept running with nothing pointing at it — still decoding,
     * still holding a decoder instance, impossible to stop. The slot comes from SessionRegistry and
     * is stable for the life of the connection.
     *
     * MAX_SLOTS is the array size, NOT the policy; how many senders are actually admitted is
     * SessionRegistry.capacity. Sizing the array to the hardware maximum means raising the capacity
     * never has to touch this file.
     */
    private val mirrorServers = arrayOfNulls<MirrorStreamServer>(MAX_SLOTS)

    private val _activeMirrorSlots = kotlinx.coroutines.flow.MutableStateFlow<Set<Int>>(emptySet())

    /** Which tiles currently have a mirror on them, so the UI can lay out that many. */
    val activeMirrorSlots: kotlinx.coroutines.flow.StateFlow<Set<Int>> = _activeMirrorSlots

    private fun publishMirrorSlots() {
        val slots = mirrorServers.indices.filter { mirrorServers[it] != null }.toSet()
        _activeMirrorSlots.value = slots
        Logger.i("Mirror tiles now: ${if (slots.isEmpty()) "none" else slots.sorted().joinToString()}")
        runCatching { onMirrorSlotsChanged(slots) }
    }

    /** Whichever mirror is on the primary tile — for the single-session paths that predate slots. */
    private val mirrorServer: MirrorStreamServer? get() = mirrorServers.firstOrNull { it != null }
    /**
     * One audio server per sender. Only the owner's reaches the speakers.
     *
     * Every live sender keeps its own server — its own ports, keys and decoder — because a stopped
     * server loses its ports and the sender bound to them never asks for new ones. That is exactly
     * why ownership decided at SETUP could not be handed back: once the iPhone's stream existed
     * there was no event left for it to win with, so resuming playback on it did nothing at all.
     */
    private val audioServers = arrayOfNulls<AudioStreamServer>(MAX_SLOTS)

    /** The server currently feeding the speakers, if any. */
    private val audioServer: AudioStreamServer?
        get() = audioOwnerSlot?.let { audioServers.getOrNull(it) }

    /** Which senders are producing sound, as opposed to merely being connected. */
    private val audioActive = BooleanArray(MAX_SLOTS)

    /**
     * Moves the speakers to [slot].
     *
     * [deliberate] marks a user action on the sending device — changing its volume. That beats
     * every automatic rule: anything else here is the receiver guessing from who is playing, and a
     * guess must never override someone reaching for the volume on the device they want to hear.
     */
    private fun giveAudioTo(slot: Int, deliberate: Boolean, why: String) {
        if (slot !in audioServers.indices || audioServers[slot] == null) return
        val changed = audioOwnerSlot != slot
        audioOwnerSlot = slot
        // IDEMPOTENT, not early-returning on "already the owner". Servers are created silent and
        // this call is what unmutes them, so skipping the work when the slot already held ownership
        // would leave a RECONNECTING sender's brand-new server muted with nothing left to turn it
        // on. The owner had not changed; the server behind it had.
        audioServers.forEachIndexed { i, server -> server?.outputEnabled = (i == slot) }
        if (changed) {
            Logger.i("Audio → tile $slot (${if (deliberate) "volume changed on that device" else why})")
        }
        // The beat visuals follow the sound; anything else animates one device's music to another's.
        audioServers[slot]?.setBeatDelayMs(beatDelayMs.toLong())
    }

    /**
     * Hands the speakers on when the owner goes quiet and someone else has not.
     *
     * Deliberately does NOT move audio while the owner is still playing. Two devices competing for
     * one output should be settled by the volume rule, not by whichever packet arrived last.
     */
    private fun rebalanceAudio() {
        val owner = audioOwnerSlot
        if (owner != null && audioActive.getOrElse(owner) { false }) return
        val next = audioServers.indices.firstOrNull {
            it != owner && audioServers[it] != null && audioActive[it]
        } ?: return
        giveAudioTo(next, deliberate = false, why = "tile $owner went quiet")
    }

    /**
     * Retargets the beat visuals without touching the audio, for when the output changes mid-stream.
     *
     * No-op when nothing is playing: the next server is built with the current value anyway.
     */
    fun setBeatDelayMs(ms: Int) {
        audioServer?.setBeatDelayMs(ms.toLong())
    }

    /**
     * Holds MIRRORED VIDEO back by the same amount the current output is late, so the picture meets
     * the sound instead of running ahead of it.
     *
     * The beat visuals already got this treatment; mirroring did not, so with a Bluetooth speaker
     * the video ran ~350ms ahead of the audio — the worst direction for lip sync, because the mouth
     * moving before the voice is the one offset people notice immediately.
     *
     * Live, like [setBeatDelayMs]: a speaker connecting mid-mirror has to be followed. Kept in a
     * field as well so the NEXT mirror session starts already compensated rather than snapping into
     * alignment a moment after it opens.
     */
    fun setVideoDelayMs(ms: Int) {
        routeVideoDelayMs = ms.toLong()
        // Every live mirror, not just the first: they all play against the same speakers.
        mirrorServers.forEach { it?.setVideoDelayMs(routeVideoDelayMs) }
    }

    @Volatile private var routeVideoDelayMs: Long = 0L

    /** Which tile currently owns the speakers, or null when nothing does. See [startMirrorAudio]. */
    @Volatile private var audioOwnerSlot: Int? = null

    /** The device the audio is actually being written to, or null when nothing is playing. */
    fun routedAudioDevice(): android.media.AudioDeviceInfo? = audioServer?.routedDevice()

    /**
     * The last gain the sender asked for, kept across audio servers.
     *
     * Senders set volume on connect and between tracks, both of which happen while no server
     * exists. Without this the value was dropped and playback started at the default.
     */
    @Volatile private var lastVolumeGain: Float? = null

    /**
     * Forgets the remembered gain when a session ends.
     *
     * Remembering it across the whole process meant a NEW sender inherited the previous one's
     * volume until it happened to send its own -- the "it grabs from the last session" report. The
     * value only needs to survive the gap between a sender connecting and its audio server being
     * built, which is well inside one session.
     */
    private fun forgetVolume() { lastVolumeGain = null }
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context).apply {
        onCommandRejected = { command, code ->
            // One rejection is enough to stop trying: a sender that answers 501 to playpause
            // answers 501 to all of them, and re-asking costs a 2s HTTP round trip per key press.
            if (!dacpRejectsCommands) {
                Logger.i("DACP rejected '$command' with HTTP $code — switching to MediaRemote")
                dacpRejectsCommands = true
            }
            DACP_TO_MEDIA_REMOTE[command]?.let { sendMediaRemoteCommand(it) }
        }
    }

    /**
     * True once the sender has rejected a DACP command.
     *
     * iOS 26 advertises a DACP identity, resolves cleanly, and then answers 501 to every transport
     * command -- so [DacpClient.isAvailable] alone routed every key press into a dead end.
     */
    @Volatile private var dacpRejectsCommands = false
    @Volatile private var ntpClient: AirPlayNtpClient? = null

    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    /** Deferred end-of-video, pending a possible renegotiation. See [scheduleMirrorVideoStop]. */
    private val pendingVideoStops = arrayOfNulls<kotlinx.coroutines.Job>(MAX_SLOTS)

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
    /** Rate limit for [requestKeyFrame]; a sender cannot encode IDRs faster than this anyway. */
    @Volatile private var lastKeyFrameRequestMs = 0L
    /** MediaRemote commands the current sender said it would accept. Empty until it tells us. */
    @Volatile private var supportedRemoteCommands: Set<Int> = emptySet()
    /**
     * The mirroring stream keys, PER SENDER.
     *
     * These were three shared fields, and with two senders that is a correctness bug rather than an
     * inconvenience: the second sender's SETUP overwrote the first's keys, so anything the first
     * sender negotiated afterwards — pausing and resuming its audio is enough — built a decryptor
     * with the OTHER device's key. What comes out is not "glitchy audio", it is noise being decoded
     * as if it were PCM, and there is nothing in the log to say so because every layer did exactly
     * what it was told.
     */
    private class MirrorKeys(val aes: ByteArray, val ecdh: ByteArray, val iv: ByteArray)

    private val mirrorKeys = arrayOfNulls<MirrorKeys>(MAX_SLOTS)

    /** Decoder throughput each tile has reserved, in pixels per second. See [startMirrorStream]. */
    private val mirrorCosts = LongArray(MAX_SLOTS)

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
                startAudioUdpReceiver()
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
        // NO DACP PAUSE HERE any more. It was sent so the phone would not carry on playing into a
        // closed socket, and it does stop the audio -- but pause is all it does. The phone stays
        // SELECTED on this output, showing the receiver as its active AirPlay destination, just
        // paused. The device log reads as a clean teardown (RTSP closed, media released, mDNS
        // re-advertised) while the iPad still believes it is connected, which is exactly what
        // "back doesn't terminate the connection" looked like from the sofa.
        //
        // DACP has no "deselect this output" command -- it is a transport protocol. What actually
        // makes iOS let go of a route is the receiver becoming UNAVAILABLE, which is why the
        // withdrawal below is now held open for a moment.
        rtspHandler?.disconnectActiveClient()
        kickUntilMs = System.currentTimeMillis() + KICK_WINDOW_MS
        // Closing the RTSP control socket does not touch the UDP media servers — they are separate
        // sockets and keep receiving and playing whatever the sender is still transmitting, which is
        // why Back looked like it did nothing. Tear the media down explicitly.
        releaseMediaComponents()
        onStreamingStopped()
    }

    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        kickUntilMs = 0L
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            controlHandler?.stop()
            runCatching { audioSocket?.close() }
            audioSocket = null
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
        if (dacpClient.isAvailable && !dacpRejectsCommands) {
            dacpClient.sendCommand(command)
            return
        }
        // Reached either because the sender never advertised a DACP identity, or because it did and
        // then rejected everything (see [dacpRejectsCommands]). Either way MediaRemote over the
        // event channel is the remaining path.
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
    /**
     * Asks the sender for a fresh IDR, over the event channel.
     *
     * THIS IS NOT A GUESS, WHICH IS THE ONLY REASON IT IS ENABLED WHILE [sendMediaRemoteCommand]
     * IS NOT. Apple's own receiver SDK (the CarPlay Communication Plugin sources, which use the
     * same screen protocol as mirroring) defines it in AirPlayCommon.h:
     *
     *     ForceKeyFrame: Tells the server to request a key frame from the sender.
     *     Used when the decoder crashes, etc.  No request keys. No response keys.
     *     #define kAirPlayCommand_ForceKeyFrame "forceKeyFrame"
     *
     * and AirPlayReceiverSessionForceKeyFrame() builds exactly `{type: "forceKeyFrame"}` — no
     * params — and POSTs it to /command on the event client. The MediaRemote attempt failed
     * because its two payload keys were invented by mirroring a sender→receiver message and appear
     * in no implementation anywhere; this one has a documented name and an empty body, so there is
     * nothing left to get wrong except the framing, which the channel already does for HomeKit.
     *
     * WHY IT MATTERS: macOS emits roughly ONE IDR per session. Every path that sets
     * `MirrorStreamServer.awaitingKeyframe` — a dropped frame, a rejected frame, a decoder rebuilt
     * against a new Surface — then waits for an IDR that may be many seconds away, and the picture
     * is black or smeared for the whole wait. That is the cold-first-connect bug and the artifact
     * bug, and neither is fixable by anything on our side of the socket: the frames we need do not
     * exist yet. Asking is the only lever.
     *
     * Rate-limited, because [MirrorStreamServer] can set awaitingKeyframe on consecutive frames and
     * a keyframe cannot arrive faster than the sender can encode one. Asking again inside that
     * window would only cost the sender bandwidth we then have to receive.
     *
     * @return false when there is no event channel, or when a request is already outstanding.
     */
    fun requestKeyFrame(why: String): Boolean {
        val cipher = eventCipher
        val output = eventOutput
        if (cipher == null || output == null) {
            Logger.d("Keyframe request ($why) dropped — no event channel")
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastKeyFrameRequestMs < KEYFRAME_REQUEST_INTERVAL_MS) return false
        lastKeyFrameRequestMs = now

        val body = PlistCodec.encode(mapOf("type" to "forceKeyFrame"))
        val host = eventClientSocket?.inetAddress?.hostAddress?.substringBefore('%')
        val head = buildString {
            // HTTP/1.1 to match Apple's own receiver, which sends this through its HTTPClient.
            // A Go sender implementation verified against real Apple hardware uses RTSP/1.0 on
            // this socket instead, so the token is the one part of this that is genuinely
            // uncertain — which is why the reply is logged rather than ignored. A sender that
            // cannot parse the request line answers nothing at all, and that silence is the
            // signal to try the other token.
            append("POST /command HTTP/1.1\r\n")
            append("Host: ${host ?: "localhost"}\r\n")
            append("CSeq: ${eventCseq.getAndIncrement()}\r\n")
            append("Content-Type: application/x-apple-binary-plist\r\n")
            append("Content-Length: ${body.size}\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        scope.launch(Dispatchers.IO) {
            synchronized(eventWriteLock) {
                runCatching { cipher.write(output, head + body) }
                    .onSuccess { Logger.i("Keyframe requested from sender ($why)") }
                    .onFailure { Logger.e("Keyframe request ($why) failed", it) }
            }
        }
        return true
    }

    fun sendMediaRemoteCommand(command: Int): Boolean {
        // OFF because there is no known delivery format — NOT because it is harmful.
        //
        // The body below addresses /command with two plist keys ("sendCommand" and
        // "mrCommandFromReceiver") that were guessed by mirroring the sender's own
        // mrSupportedCommandsFromSender. Neither appears in pyatv, openairplay's receiver,
        // nto.github.io, emanuelecozzi.net or SteeBono's wiki, and no public implementation sends
        // receiver->sender commands at all. So it cannot work, and every press writes bytes the
        // sender will not parse.
        //
        // AN EARLIER REVISION OF THIS COMMENT CLAIMED THESE SENDS WERE TEARING DOWN SESSIONS. That
        // was wrong, and the correction is worth keeping because the reasoning failed in an
        // instructive way. It rested on two timestamp correlations, and an airplayd capture from the
        // sender disproves both:
        //
        //   * "session died 4s after a burst of sends" -- the sender's own log shows the stream
        //     perfectly healthy across that gap (buffer fullness 43.55%, time announces on schedule,
        //     no errors) and then an orderly "RTAE Suspending" / "MediaPlaying stopped" /
        //     "Starting 480-sec inactivity timer". That is playback ending, not a session being
        //     killed. The kCanceledErr entries follow the suspend as cleanup, they do not precede it.
        //   * "socket closed 6ms after a send" -- that send was OUR OWN endSession(), which fires
        //     CMD_PAUSE and then immediately calls disconnectActiveClient(). We closed that socket.
        //     Reverse causation, in a path written three commits earlier.
        //
        // Correlating our own log against itself was never going to separate those; it took the
        // other end's log. Keep that in mind before concluding anything about what a sender "does"
        // in response to us.
        if (!MRP_SEND_ENABLED) {
            Logger.i(
                "MediaRemote ${MediaRemote.name(command)} not sent — no known delivery format, " +
                    "and sending a guessed one makes the sender ignore it — see sendMediaRemoteCommand"
            )
            return false
        }
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

    /**
     * What to advertise to a sender that is connecting now.
     *
     * Full configured size while nothing is mirroring. Once a tile is running, the next sender is
     * offered 1080p at most — the frame budget is shared, and a 2560x1440 stream costs 221M px/s of
     * a 248M budget on this hardware, so a second one at that size cannot be served no matter what
     * we would like. 1080p60 costs about half as much, which is what makes two fit at all.
     *
     * This is a ceiling, never a floor: a receiver configured below 1080p keeps its own smaller
     * size rather than being talked up to one it cannot decode.
     */
    private fun advertisedMirrorSize(): Pair<Int, Int> {
        val busy = mirrorServers.any { it != null }
        if (!busy) return mirrorWidth to mirrorHeight
        return minOf(mirrorWidth, SECOND_SENDER_MAX_WIDTH) to
               minOf(mirrorHeight, SECOND_SENDER_MAX_HEIGHT)
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            displaySizeFor = { advertisedMirrorSize() },
            audioEnabled = audioEnabled,
            videoSurfaceProvider = { videoSurfaceProvider(PRIMARY_SLOT) },
            maxSessions = maxSessions,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { slot, remaining -> onStreamingStopped(slot, remaining) },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onSupportedRemoteCommands = { supportedRemoteCommands = it },
            onMirrorSetupKeys = { slot, aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort ->
                startMirrorKeys(slot, aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            },
            onMirrorStreamStart = { slot, streamConnectionId -> startMirrorStream(slot, streamConnectionId) },
            onMirrorAudioStart = { slot, sampleRate, channels, ct, spf, latency ->
                startMirrorAudio(slot, sampleRate, channels, ct, spf, latency)
            },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { slot -> stopMirrorVideo(slot) },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onVolume = { slot, v ->
                // THE VOLUME KEY IS THE OVERRIDE. Someone reaching for the volume on a device is
                // saying which one they want to hear, and that beats every automatic rule — it is
                // the only unambiguous signal a sender gives us. Claimed even if that device is
                // currently silent: turning it up is exactly what you do before starting it.
                if (audioServers.getOrNull(slot) != null) {
                    giveAudioTo(slot, deliberate = true, why = "volume")
                }
                // 0f is 0 dB, i.e. unity gain — the right software setting when the hardware is
                // doing the attenuation for us.
                val gain = if (onVolumeRequest(v)) 0f else v
                // REMEMBERED, not just forwarded. Senders send volume as soon as they connect,
                // which is BEFORE any audio server exists, so `audioServer?.setVolume` discarded it
                // through the safe call and the first track played at the default gain -- the
                // "changing volume before the first play does nothing" report. The same null made
                // every later session start at default too, because each one builds a fresh server:
                // that is the volume "resetting". Hold the last value and apply it on creation.
                lastVolumeGain = gain
                // The gain belongs to the sender that sent it, not to whoever owns the output.
                audioServers.getOrNull(slot)?.setVolume(gain) ?: audioServer?.setVolume(gain)
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
                // ...except on a stream that has no track at all. macOS system-audio AirPlay (the
                // "AUDIO" output device, not the Music app) still sends progress, but its window is
                // a rolling live buffer with no beginning or end — which surfaced as a track that
                // started at 0:25 and ran to 1:36. No title means no track, and no track means no
                // duration worth showing.
                val live = npTitle.isNullOrBlank() && npArtist.isNullOrBlank()
                val shownDur = if (live) 0.0 else dur
                val changed = displayedSecond(pos) != displayedSecond(npPositionSec) || shownDur != npDurationSec
                npPositionSec = pos; npDurationSec = shownDur
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
    /**
     * One sender has gone. Tears the whole receiver down only when it was the LAST one.
     *
     * This used to release every media component unconditionally, and it runs whenever any control
     * connection closes — so with two senders, disconnecting the Mac stopped the iPhone's mirror
     * and its audio and withdrew mDNS. "I disconnected the Mac and it terminated everything." A
     * session ending is not the receiver ending.
     *
     * The defaults mean the internal callers — endSession and the socket-close path — keep asking
     * for the full teardown they always did.
     */
    private fun onStreamingStopped(slot: Int = PRIMARY_SLOT, remainingSessions: Int = 0) {
        if (remainingSessions > 0) {
            Logger.i("Tile $slot ended; $remainingSessions sender(s) still connected — keeping the rest")
            stopMirrorVideo(slot)
            // Only this sender's audio goes. A survivor's server keeps its ports and can simply be
            // handed the output — it does not have to negotiate again, which it would never do.
            audioServers.getOrNull(slot)?.stop()
            if (slot in audioServers.indices) { audioServers[slot] = null; audioActive[slot] = false }
            if (audioOwnerSlot == slot) {
                audioOwnerSlot = null
                forgetVolume()
                val survivor = audioServers.indices.firstOrNull { audioServers[it] != null }
                if (survivor != null) giveAudioTo(survivor, deliberate = false, why = "tile $slot left")
            }
            return
        }
        if (streamingStopped) return
        streamingStopped = true
        Logger.i("Streaming stopped — releasing media components")
        releaseMediaComponents()
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                // WITHDRAW FIRST, then hold, then re-register.
                //
                // The first version of this delayed and *then* called restart() -- but the
                // withdrawal happens inside restart(), so the receiver stayed fully advertised for
                // the whole four seconds and only went away for the ~650ms the restart itself takes.
                // The device log said "Holding mDNS withdrawn for 3992ms" at 01:50:57 and "Stopping
                // mDNS advertising" at 01:51:01 -- the hold ran before the thing it was supposed to
                // be holding. iOS never saw us leave, so it kept the route and Back still read as a
                // pause. The order is the entire fix.
                val hold = kickUntilMs - System.currentTimeMillis()
                if (hold > 0) {
                    Logger.i("Withdrawing mDNS for ${hold}ms so the sender drops the route")
                    mdnsService?.stop()
                    kotlinx.coroutines.delay(hold)
                    mdnsService?.start(displayName.ifBlank { null })
                } else {
                    mdnsService?.restart(displayName.ifBlank { null })
                }
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    /**
     * While now() is under this, the receiver stays off the network after a session ends.
     *
     * Only set by the user ending a session by hand. A sender that leaves on its own has already
     * let go of the route, and making the receiver vanish for several seconds then would just delay
     * the next connection for no reason.
     */
    @Volatile private var kickUntilMs = 0L

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
        val surface = videoSurfaceProvider(PRIMARY_SLOT) ?: run {
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
        audioPlayer = AudioPlayer(extraDelayMs = audioDelayMs).also { player ->
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
        // The audio socket is NOT opened here -- it is already listening. See startAudioUdpReceiver.
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * WHY IT BINDS AT STARTUP AND NEVER CLOSES BETWEEN SESSIONS: this used to be opened lazily when
     * the AudioPlayer started, which put a coroutine hop and ~110ms between RECORD and a bound
     * socket. macOS Music begins sending audio the instant it has sent RECORD, so for that window
     * every packet drew an ICMP port-unreachable, and Music concluded the receiver was dead and sent
     * TEARDOWN — 8ms before the socket finished binding. The device log showed the bind line AFTER
     * the teardown line, which is what finally gave it away. An advertised port must be listening
     * before it is advertised, not after it is used.
     *
     * Packets arriving with no active player are simply dropped: cheap, and far better than the
     * alternative of the port going away again between sessions.
     */
    private fun startAudioUdpReceiver() {
        if (audioSocket != null) return
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
        slot: Int,
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorKeys[slot.coerceIn(0, MAX_SLOTS - 1)] = MirrorKeys(aesKey, ecdhSecret, aesIv)
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
    private fun startMirrorStream(slot: Int, streamConnectionId: Long): Int {
        // A stream is back: whatever close we were waiting out was a renegotiation, not an ending.
        pendingVideoStops.getOrNull(slot)?.let {
            it.cancel()
            Logger.i("Tile $slot renegotiated — cancelling its pending stop")
        }
        if (slot in pendingVideoStops.indices) pendingVideoStops[slot] = null
        // ADMIT BY THROUGHPUT, WITH THE REAL RESOLUTION IN HAND.
        //
        // The session capacity is a coarse gate applied when the control socket opens, before
        // anyone knows what will be streamed. Here we know: charge this sender for the size it
        // negotiated and refuse it if the decoder cannot carry the total. A 1440p Mac costs 1.78x
        // what a 1080p phone does, so "how many fit" genuinely has no fixed answer — which is why
        // counting streams admitted a third sender that then sat on a frozen frame.
        //
        // Refusing returns port 0. The sender loses video and its session survives, which is the
        // honest outcome: better a device that plainly did not start than one that appears to be
        // mirroring and is not.
        // CHARGE THIS SENDER FOR WHAT IT WAS OFFERED, not for what the receiver is configured to
        // do at its largest.
        //
        // This used costOf(mirrorWidth, mirrorHeight) — the CONFIGURED size — so a second sender
        // that had just been advertised 1080p was still billed for 2560x1440 and refused on the
        // strength of a number nobody had agreed to. The log said both halves in the same second:
        //
        //     GET /info advertising 1920x1080 (reduced from 2560x1440 — another sender ...)
        //     Tile 1 refused: 2560x1440 needs 221M px/s, only 27M left of 248M
        //
        // [advertisedMirrorSize] is what the sender was actually told, and it is evaluated here for
        // the same reason it is evaluated at /info: it depends on how many tiles are already
        // running, and this slot's own server does not exist yet.
        val (tileWidth, tileHeight) = advertisedMirrorSize()
        val cost = com.phairplay.media.DecoderCapacity.costOf(tileWidth, tileHeight)
        val committed = mirrorCosts.filterIndexed { i, _ -> i != slot }.sum()
        val budget = com.phairplay.media.DecoderCapacity.pixelBudgetPerSecond()
        if (committed + cost > budget) {
            Logger.w("Tile $slot refused: ${tileWidth}x$tileHeight needs " +
                     "${cost / 1_000_000}M px/s, only ${(budget - committed) / 1_000_000}M left " +
                     "of ${budget / 1_000_000}M")
            return 0
        }
        mirrorCosts[slot] = cost

        val keys = mirrorKeys.getOrNull(slot)
            ?: run { Logger.e("mirror stream start before keys set (tile $slot)"); return 0 }
        val aesKey = keys.aes
        val ecdhSecret = keys.ecdh
        return MirrorStreamServer(
            aesKey, ecdhSecret, streamConnectionId,
            // Each tile draws into its own Surface. With one shared provider two senders decoded
            // into the same SurfaceView and the second simply painted over the first.
            surfaceProvider = { videoSurfaceProvider(slot) },
            // The size this tile was offered, for the same reason as the cost above. It is only a
            // hint — the decoder trusts the SPS it actually receives — but a hint that disagrees
            // with what the sender was told is a hint worth not giving.
            width = tileWidth, height = tileHeight,
            // A sender that goes quiet without a TEARDOWN (phone screen off, or an app taking over
            // with its own fullscreen player) used to leave the session "live" with its last frame
            // frozen on the TV. Tear it down ourselves.
            onConnectionEnded = { scheduleMirrorVideoStop(slot) },
            onOutputSize = { w, h ->
                // RE-COST THE TILE THE MOMENT THE REAL SIZE IS KNOWN.
                //
                // Admission has to charge SOMETHING before a single frame has arrived, and the only
                // figure available then is what the sender was advertised — a worst case. It is a
                // long way from the truth: an iPhone advertised 2560x1440 actually streams a
                // portrait 666x1440, so it was booked at 221M px/s and really costs ~57M. With the
                // budget at 248M that one over-estimate reserved almost everything and refused the
                // next sender at 1080p, which needs 124M and would have fitted twice over.
                //
                // The decoder reports the size it read from the SPS, which is the first authoritative
                // number anyone has. Replacing the estimate with it hands the difference back.
                val real = com.phairplay.media.DecoderCapacity.costOf(w, h)
                if (mirrorCosts.getOrNull(slot) != real && slot in mirrorCosts.indices) {
                    Logger.i("Tile $slot re-costed: ${w}x$h is ${real / 1_000_000}M px/s " +
                        "(booked ${mirrorCosts[slot] / 1_000_000}M at admission)")
                    mirrorCosts[slot] = real
                }
                runCatching { onMirrorSizeChanged(slot, w, h) }
            },
            requestKeyFrame = { why -> requestKeyFrame(why) },
        )
            .also {
                mirrorServers[slot] = it
                publishMirrorSlots()
                it.setVideoDelayMs(routeVideoDelayMs)
                it.start(scope)
                videoPlaying = true
                emitNowPlaying()
            }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    /**
     * Starts the mirror audio stream — for ONE sender at a time.
     *
     * N video, one audio, deliberately. There is a single AudioTrack and a beat/backdrop pipeline
     * built around one PCM source, and two songs at once is not a feature. Before this check the
     * second sender's SETUP simply overwrote `audioServer`: the first sender's server kept running
     * with nothing pointing at it, both fed the same output, and the result was the "audio is weird
     * now" that outlasted the second sender leaving.
     *
     * The owner is whoever got there first, which with one sender is unchanged. A later sender is
     * refused audio and keeps its video — a silent tile, not a failed session.
     */
    private fun startMirrorAudio(slot: Int, sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int, latencyMinSamples: Int): Pair<Int, Int> {
        // THE NEWEST SENDER TO ASK GETS THE SPEAKERS.
        //
        // The first version gave them to whoever asked first and refused everyone after. That is a
        // defensible rule and it produced a dead end: a refused sender never asks again, so once
        // the iPhone owned audio the Mac was silent for the rest of its session — even after the
        // iPhone was paused, and even after it disconnected entirely. There is no event that would
        // have given it another turn.
        //
        // Last-wins has no dead end. Connecting a device, or restarting its stream, is a deliberate
        // act and it is the one thing a user can do to say "I want to hear this one". Safe now only
        // because the previous server is stopped below rather than abandoned; before that fix this
        // would have stacked two of them on one AudioTrack.
        //
        // Still one audio stream at a time. Mixing N senders is a different feature, and the
        // per-tile override (highlight a tile, audio follows) is the real answer to "no, the other
        // one" — this is the sane default underneath it.
        val previousOwner = audioOwnerSlot
        if (previousOwner != null && previousOwner != slot) {
            Logger.i("Audio moving from tile $previousOwner to tile $slot")
        }
        audioOwnerSlot = slot
        val keys = mirrorKeys.getOrNull(slot)
            ?: run { Logger.e("audio start before keys set (tile $slot)"); return 0 to 0 }
        val aesKey = keys.aes
        val ecdhSecret = keys.ecdh
        val aesIv = keys.iv

        // REPLACE, never stack. This assigned over `audioServer` without stopping what was already
        // there, so a sender renegotiating its audio — which is what pausing and resuming does —
        // left the previous server running and un-referenced, with two of them writing into one
        // AudioTrack. That is audible as the stutter that survives the renegotiation.
        // Replace THIS TILE'S server only. Another sender's stays up — stopping it would take its
        // ports with it, and it would never come back for new ones.
        audioServers.getOrNull(slot)?.let {
            Logger.i("Replacing the audio server for tile $slot")
            it.stop()
            audioServers[slot] = null
        }
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket,
            latencyMinSamples = latencyMinSamples + (audioDelayMs * sampleRate / 1000),
            extraDelayMs = audioDelayMs.toLong(),
            trackBufferMs = audioBufferMs,
            beatDelayMs = beatDelayMs.toLong(),
            onEnergy = { e -> onEnergyChanged(e) },
            onBands = { b -> onBandsChanged(b) },
            // Apple Music never sends RTSP PAUSE, and FLUSH fires at stream start too, so it
            // can't mean "paused". The stream itself is the signal: this sender stops sending
            // RTP entirely while paused and resumes the instant playback does.
            onAudioIdle = { idle ->
                if (slot in audioActive.indices) audioActive[slot] = !idle
                // Only the tile you are actually listening to may drive the card's pause state.
                if (audioOwnerSlot == slot) { npPaused = idle; emitNowPlaying() }
                rebalanceAudio()
            })
            .also {
                audioServers[slot] = it
                // Silent until something gives it the output. If nothing owns the speakers it takes
                // them; otherwise it decodes quietly until a rule hands them over.
                it.outputEnabled = false
                // Apply whatever the sender asked for before this server existed.
                lastVolumeGain?.let { g -> it.setVolume(g) }
                it.start(scope); startPositionTicker(); startAudioSilenceWatchdog(it)
            }
        // AND CLAIM THE OUTPUT UNLESS SOMEONE ELSE IS LIVE ON IT. Missing this is what made every
        // stream silent: servers are created muted, so without a claim here the only thing that
        // could ever unmute one was an incoming volume change. A lone sender got no sound at all.
        val owner = audioOwnerSlot
        if (owner == null || owner == slot || audioServers.getOrNull(owner) == null) {
            giveAudioTo(slot, deliberate = false, why = "nothing else owned the output")
        } else {
            Logger.i("Tile $slot decoding audio silently — tile $owner owns the output")
        }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /**
     * Ends the session when the sender goes completely quiet.
     *
     * Not every sender says goodbye. An iPhone that stops playback often sends no TEARDOWN and
     * leaves the RTSP socket open indefinitely, so the receiver sat "streaming" forever, holding
     * the screen and refusing the next connection. A *paused* sender still sends keepalive
     * datagrams, so total silence is unambiguous: it is gone.
     *
     * The window is deliberately generous — a few seconds of Wi-Fi trouble must not look like a
     * disconnect.
     */
    private fun startAudioSilenceWatchdog(server: AudioStreamServer) {
        scope.launch {
            while (audioServer === server) {
                delay(AUDIO_SILENCE_POLL_MS)
                if (audioServer !== server) return@launch
                if (server.silentForMs < AUDIO_SILENCE_TIMEOUT_MS) continue
                Logger.i("Sender silent for ${server.silentForMs}ms with no TEARDOWN — ending session")
                endSession()
                return@launch
            }
        }
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() {
        audioServers.indices.forEach { audioServers[it]?.stop(); audioServers[it] = null; audioActive[it] = false }
        audioOwnerSlot = null
        forgetVolume()
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
    /**
     * Per tile, because the grace period is per sender.
     *
     * A single shared job meant a second sender arriving cancelled the first one's pending stop —
     * so a sender that had genuinely gone away stayed "live" forever, holding its decoder and its
     * tile, because the timer that would have cleaned it up belonged to someone else.
     */
    private fun scheduleMirrorVideoStop(slot: Int) {
        pendingVideoStops[slot]?.cancel()
        pendingVideoStops[slot] = scope.launch {
            delay(MIRROR_RESUME_GRACE_MS)
            Logger.i("No video stream returned within ${MIRROR_RESUME_GRACE_MS}ms — ending video")
            stopMirrorVideo(slot)
            pendingVideoStops[slot] = null
        }
    }

    private fun stopMirrorVideo(slot: Int) {
        pendingVideoStops.indices.forEach { pendingVideoStops[it]?.cancel(); pendingVideoStops[it] = null }
        // Both a TEARDOWN and the connection ending can land here for one session; the second call
        // has nothing to do and must not re-emit state.
        if (!videoPlaying && mirrorServers[slot] == null) return
        mirrorServers[slot]?.stop()
        mirrorServers[slot] = null
        mirrorCosts[slot] = 0L      // give the throughput back so the next sender can have it
        publishMirrorSlots()
        // Video is only "over" once the LAST tile has gone. Clearing this on the first teardown
        // would drop the overlay while another sender was still mirroring into it.
        if (mirrorServers.all { it == null }) videoPlaying = false
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        // Nothing left playing: drop back to advertising so the overlay actually leaves the screen.
        // emitNowPlaying alone does not do it — with no metadata it emits null, and the service
        // reads "null while CONNECTED" as "a video session is running", so stopping mirroring on
        // the phone left the TV sitting on a frozen last frame. The RTSP session itself stays up,
        // so an iOS renegotiation re-emits CONNECTED and the picture comes straight back.
        // ADVERTISING IS A STATEMENT ABOUT THE WHOLE RECEIVER, not about this tile. Emitting it
        // because one sender stopped told the service nothing was streaming at all, which dropped
        // the overlay — and with it the OTHER sender's tile, still mirroring. Ending mirroring on
        // the iPhone took the Mac's picture with it.
        val stillMirroring = mirrorServers.any { it != null }
        when {
            stillMirroring ->
                Logger.i("Tile $slot stopped; other tiles still mirroring — staying connected")
            !audioPlaying -> {
                Logger.i("Mirror video stopped and nothing else is playing — session idle")
                emitState(ProtocolState.ADVERTISING)
            }
            else -> Logger.i("Mirror video stream stopped (audio playback continues)")
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
            surfaceProvider = { videoSurfaceProvider(PRIMARY_SLOT) },
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
        // Deliberately NOT closing audioSocket here. It belongs to the receiver, not the session --
        // closing it between sessions reopens the very race that made Music unusable. The player it
        // feeds is still released below, so packets arriving between sessions are simply dropped.
        mirrorServers.indices.forEach { mirrorServers[it]?.stop(); mirrorServers[it] = null; mirrorCosts[it] = 0L }
        publishMirrorSlots()
        positionTicker?.cancel(); positionTicker = null
        anchorStartTs = -1L
        audioServers.indices.forEach { audioServers[it]?.stop(); audioServers[it] = null; audioActive[it] = false }
        audioOwnerSlot = null
        forgetVolume()
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
        mirrorKeys.indices.forEach { mirrorKeys[it] = null }
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
        // The receiver is the only thing that KNOWS whether a video stream was negotiated. The
        // service used to infer it from "is there now-playing metadata yet", which is false at
        // CONNECTED for every session — so an audio-only sender that never produced metadata (a
        // stalled Apple Music stream, say) left a black video surface on screen indefinitely.
        onVideoPlayingChanged(videoPlaying)
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
                // FREEZE WHILE PAUSED. A paused sender's empty keepalives never reach the point
                // where lastRtpTs advances, so the numerator is genuinely still -- but
                // playingRtpTimestamp() also subtracts AudioTrack's pending frames, and those
                // DRAIN to zero over about a second once the queue stops feeding. That drain shows
                // up as the position walking a second forward, and getTimestamp() jittering around
                // the drained value walks it back again: the progress bar creeping +1s and back
                // for as long as the track sits paused. Nothing is moving except the measurement,
                // so stop measuring.
                if (npPaused) continue
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
         * Upper bound on simultaneous mirror tiles. Sized to the hardware: the Fire TV's AVC
         * decoder declares five concurrent instances and one is kept back for DLNA / AirPlay URL
         * playback, which build their own decoder through ExoPlayer.
         *
         * This is the ARRAY size, not the policy. How many senders are admitted is
         * SessionRegistry.capacity, which is driven by the user's setting.
         */
        const val MAX_SLOTS = 4

        /**
         * Largest size offered to a sender arriving while another is already mirroring.
         *
         * 1080p because that is what the measured frame budget affords twice over: DecoderCapacity
         * reports 248M px/s on this Fire TV, a 1080p60 stream costs ~124M, and 2560x1440 costs
         * ~221M. Two 1080p tiles fit exactly; one 1440p tile plus anything does not.
         */
        const val SECOND_SENDER_MAX_WIDTH = 1920
        const val SECOND_SENDER_MAX_HEIGHT = 1080

        /**
         * Minimum gap between keyframe requests.
         *
         * [MirrorStreamServer] can set `awaitingKeyframe` on consecutive frames — one dropped frame
         * followed by several rejected ones is ordinary — and an encoder cannot produce IDRs faster
         * than this regardless. Asking again inside the window only costs bandwidth we then have to
         * receive and decrypt.
         */
        const val KEYFRAME_REQUEST_INTERVAL_MS = 1000L

        /**
         * The tile everything single-stream uses: AirPlay URL video, DLNA playback, the audio
         * session. Only screen mirroring can be in more than one place at once.
         */
        const val PRIMARY_SLOT = 0

        /**
         * How long the receiver stays off the network after the user ends a session by hand.
         *
         * Long enough for a Bonjour goodbye plus the sender's own route bookkeeping to settle;
         * short enough that reconnecting deliberately still feels immediate.
         */
        private const val KICK_WINDOW_MS = 4000L


        /**
         * Whether to POST MediaRemote commands on the event channel. See sendMediaRemoteCommand:
         * the delivery format is unknown, so a guessed one cannot be parsed by any sender.
         */
        private const val MRP_SEND_ENABLED = false


        /**
         * DACP command → MediaRemote `Command`, so one TV-remote key press works against either
         * kind of sender. Volume is absent on purpose: AirPlay volume is an RTSP `SET_PARAMETER`,
         * not a transport command, and it already has its own path.
         */
        private val DACP_TO_MEDIA_REMOTE = mapOf(
            DacpClient.CMD_PLAY_PAUSE to MediaRemote.TOGGLE_PLAY_PAUSE,
            DacpClient.CMD_PLAY_RESUME to MediaRemote.PLAY,
            DacpClient.CMD_PAUSE to MediaRemote.PAUSE,
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

        /** How often the silence watchdog looks. Cheap — it reads one volatile long. */
        private const val AUDIO_SILENCE_POLL_MS = 2_000L

        /** Total silence for this long means the sender is gone, not paused or briefly stalled. */
        /**
         * How long an audio stream may go silent before the session is torn down.
         *
         * Was 15s, which killed sessions that were merely PAUSED. The device log caught it exactly:
         * PiP entered at 00:26:13, "Sender silent for 16181ms with no TEARDOWN — ending session" at
         * 00:26:56 — a healthy paused stream dropped while the user was looking at it.
         *
         * A paused sender and a vanished sender look identical from here: both stop sending audio
         * and neither says why. Since the cost of waiting is only a delayed cleanup of an already
         * dead session, while the cost of firing early is disconnecting someone mid-listen, this is
         * long enough that no realistic pause reaches it.
         */
        private const val AUDIO_SILENCE_TIMEOUT_MS = 300_000L

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
