package com.phairplay.airplay.handshake

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AudioStreamServer — receives and plays the AirPlay mirroring/realtime audio stream (type 96).
 *
 * macOS sends AES-128-CBC-encrypted AAC-ELD audio as RTP/UDP. We decrypt each packet (whole
 * 16-byte blocks; the trailing partial block is cleartext — the RAOP scheme), decode AAC-ELD via
 * MediaCodec, and play the PCM through AudioTrack.
 *
 * Architecture — the receiver and the player run on SEPARATE threads, decoupled by a bounded
 * queue (same pattern as [MirrorStreamServer] for video):
 *
 *   • Receive thread: socket.receive → dedup by RTP sequence → enqueue. Never blocks on playback,
 *     so the UDP socket is always drained promptly. (A blocking AudioTrack.write on the receive
 *     thread stalls the socket drain, which destabilises the whole mirror session.)
 *   • Playback thread: dequeue → decrypt → decode → AudioTrack.write(BLOCKING). Blocking here only
 *     paces playback to the audio clock and drops no PCM; it cannot stall the network.
 *
 * Reference: RPiPlay lib/raop_rtp.c + lib/raop_buffer.c (audio key = SHA-512(aesKey‖ecdh)[:16],
 * IV = SETUP eiv, AES-128-CBC per packet).
 */
class AudioStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    aesIv: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
    private val codecType: Int = CT_AAC_ELD,
    private val framesPerPacket: Int = DEFAULT_ALAC_FRAMES,
    /**
     * Presentation latency the sender asks for, in samples (its SETUP `latencyMin`; 11025 = 250ms
     * at 44.1kHz). AirPlay senders stream ahead of their own playback position and expect the
     * receiver to hold each frame back by this much. Playing on arrival instead made our audio run
     * ahead of the sender's timeline — audible as the music leading the lyrics on the phone.
     */
    private val latencyMinSamples: Int = 11025,
    /** User A/V trim, also applied to the beat pulse so the visual matches what is heard. */
    private val extraDelayMs: Long = 0,
    /** Called ~10x/sec with RMS energy 0..1 for beat-reactive background. */
    val onEnergy: (Float) -> Unit = {},
    /**
     * True when audio packets have stopped arriving, false when they resume.
     *
     * This is how a pause is actually detected. Apple Music never sends RTSP PAUSE, so the
     * protocol-level paused flag stays false forever and the progress bar kept counting through a
     * paused track. The stream itself is unambiguous: paused senders stop transmitting.
     */
    val onAudioIdle: (Boolean) -> Unit = {},
) {
    private val key = SecretKeySpec(MirrorCrypto.audioKey(aesKey, ecdhSecret), "AES")
    private val iv = IvParameterSpec(aesIv.copyOf(16))

    // Playback gain (0..1), set from the sender's AirPlay volume. Applied to the AudioTrack and
    // re-applied if the track is recreated. Starts at full.
    @Volatile private var volumeGain = 1f

    // Reused across packets: decryptPacket runs only on the playback thread, so one Cipher
    // instance is safe and avoids a Cipher.getInstance allocation on every packet (~92/s).
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")

    // Bind to the IPv6 wildcard (dual-stack) — macOS sends the audio RTP over the session's
    // IPv6 link-local address; a default DatagramSocket binds IPv4-only and never receives it.
    private val socket = ipv6Socket()
    private val controlSocket = ipv6Socket()   // realtime-audio control channel (drained)

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var alac: AlacDecoder? = null      // software ALAC decoder (ct=2 system-audio path)
    private var audioTrack: AudioTrack? = null
    private var firstPcm = true

    // Decoded-audio jitter buffer: raw (post-dedup) RTP payloads handed from the receive thread to
    // the playback thread. Bounded so a stalled player can't grow latency unboundedly — if it fills
    // we drop the oldest frame (a brief glitch is better than ever-growing audio lag).
    private val frameQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    /**
     * How many frames deep to hold the queue, so playback sits [latencyMinSamples] behind arrival —
     * the delay the sender expects. Capped at half the queue so there is still headroom to absorb
     * jitter before the overflow eviction kicks in.
     */
    private val targetDepthFrames: Int =
        (latencyMinSamples / framesPerPacket.coerceAtLeast(1)).coerceIn(4, AUDIO_QUEUE_CAPACITY / 2)

    // RTP duplicate suppression. macOS sends each realtime-audio packet 2–3× for redundancy
    // (same 16-bit sequence number). Decoding every copy feeds the AAC decoder duplicate frames
    // and pushes 2–3× real-time data into AudioTrack — the buffer overflows, chunks get dropped,
    // and playback both glitches and lags video. We process each sequence number exactly once,
    // remembering a sliding window of recent seqs (well under the 65536 wrap and ~11 s deep).
    private val seenSeqs = java.util.ArrayDeque<Int>()
    private val seenSeqSet = HashSet<Int>()

    // ─── Reorder buffer + packet-loss retransmit ─────────────────────────────
    // macOS's `redundantAudio` (each packet sent 2–3×) covers most loss, but a burst that drops
    // all copies leaves a gap. We hold packets in a small seq-keyed reorder buffer and, on a gap,
    // ask the sender to resend the missing range (RAOP control type 0x55) — the resent packet comes
    // back on the control socket (type 0x56) and fills the hole. Common case (in-order) releases
    // immediately with ZERO added latency; only an actual gap briefly holds, bounded by
    // MAX_REORDER_HOLD, so A/V sync is preserved. Touched by both the data-receive and control
    // threads (resend replies), so all access is under [reorderLock].
    private val reorderLock = Any()
    private val sendLock = Any()                       // serialises control-socket resend sends
    private val reorder = HashMap<Int, ByteArray>()   // seq → decrypted-pending RTP payload
    private var nextSeq = -1                            // next seq to release in order (-1 = uninit)
    private var maxSeq = -1                             // highest seq seen (for gap detection)
    private var resendCtr = 0                           // sequence counter for our resend requests
    @Volatile private var senderCtrlAddr: java.net.SocketAddress? = null
    @Volatile private var dupCount = 0
    @Volatile private var qDropCount = 0
    @Volatile private var resendReqCount = 0
    @Volatile private var resendFillCount = 0
    /** True while no audio packets are arriving — i.e. the sender is paused. */
    @Volatile private var audioIdle = false

    // Beat detection state (playback thread only).
    private var lowPass = 0.0
    private val history = DoubleArray(100)
    private var historyIdx = 0
    private var lastOnsetMs = 0L
    private var envelope = 0f
    private val energyHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** UDP port macOS sends the audio RTP stream to (returned in the SETUP response). */
    val dataPort: Int get() = socket.localPort

    /** UDP control port (returned in the SETUP response; macOS won't send audio without it). */
    val controlPort: Int get() = controlSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        StreamStats.audioActive = true
        scope.launch(Dispatchers.IO) { runPlayback() }   // decode + play (may block on AudioTrack)
        scope.launch(Dispatchers.IO) { runReceive() }    // drain socket fast (never blocks on audio)
        scope.launch(Dispatchers.IO) { runControl() }    // capture sender addr + handle resend replies
    }

    /**
     * Control channel: the sender posts periodic timing/sync packets here (RTP type 0x54, marker →
     * 0xD4), and — after we ask — resent audio packets (RTP type 0x56 → 0xD6). We learn the sender's
     * control address from whatever arrives (resend requests go back to it) and splice any resent
     * audio packet back into the reorder buffer.
     */
    private fun runControl() {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var ctrlCount = 0
        try {
            while (running) {
                pkt.length = buf.size     // reset capacity before each receive (see runReceive)
                controlSocket.receive(pkt)
                senderCtrlAddr = pkt.socketAddress   // where to send resend requests
                if (ctrlCount < 6) {
                    Logger.d("Audio CTRL[$ctrlCount] ${pkt.length}B: ${hex(pkt.data, minOf(20, pkt.length))}")
                    ctrlCount++
                }
                // RTP payload type is bits 0–6 of byte 1 (byte 1 = marker<<7 | type).
                val payloadType = pkt.data[1].toInt() and 0x7F
                if (payloadType == RTP_TYPE_RESEND_REPLY && pkt.length > RESEND_REPLY_HEADER + RTP_HEADER) {
                    // bytes [4..] are the original audio RTP packet — feed it through the normal path.
                    resendFillCount++
                    handleRtpPacket(pkt.data, RESEND_REPLY_HEADER, pkt.length - RESEND_REPLY_HEADER)
                }
            }
        } catch (_: Exception) { /* closed */ }
    }

    fun stop() {
        running = false
        StreamStats.audioActive = false
        runCatching { socket.close() }
        runCatching { controlSocket.close() }
        frameQueue.clear()
        synchronized(reorderLock) { reorder.clear() }
        // NOTE: codec + audioTrack are deliberately NOT released here. They are owned and released
        // exclusively by the playback thread (see runPlayback's finally). Releasing MediaCodec from
        // this thread races decodeFrame on the playback thread and crashes the whole process with a
        // native SIGABRT ("pthread_mutex_destroy called on a destroyed mutex" inside libstagefright).
        // Flipping `running` makes the playback loop exit within one poll timeout and clean up safely.
    }

    /** Receive thread: pull RTP packets off the data socket and feed them to the reorder buffer. */
    private fun runReceive() {
        // Bounded wait so a paused sender surfaces as an idle stream instead of blocking forever.
        runCatching { socket.soTimeout = AUDIO_IDLE_MS }
        try {
            Logger.i("AudioStreamServer listening on UDP $dataPort (ct=$codecType ${sampleRate}Hz x$channels)")
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var rtpCount = 0
            var recv = 0
            while (running) {
                packet.length = buf.size      // reset capacity — receive() shrinks length to the last datagram
                try {
                    socket.receive(packet)
                } catch (e: java.net.SocketTimeoutException) {
                    if (!audioIdle) { audioIdle = true; onAudioIdle(true) }
                    continue
                }
                if (audioIdle) { audioIdle = false; onAudioIdle(false) }
                recv++
                if (rtpCount < 6) {
                    Logger.d("Audio RTP[$rtpCount] ${packet.length}B hdr: ${hex(packet.data, minOf(20, packet.length))}")
                    rtpCount++
                }
                handleRtpPacket(packet.data, 0, packet.length)
                StreamStats.audioQueue = frameQueue.size
                // Every 500 packets is roughly every 4s — enough to flush a whole day of real
                // events out of the diagnostic ring buffer. Sample 10x less often, at debug level.
                if (recv % 5000 == 0) {
                    StreamStats.audioDupPct = dupCount * 100 / (recv + dupCount)
                    Logger.d("Audio stats: recv=$recv dup=$dupCount (${StreamStats.audioDupPct}% dup) " +
                        "qDrop=$qDropCount resendReq=$resendReqCount resendFill=$resendFillCount queue=${frameQueue.size}")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio stream error", e)
        }
    }

    /**
     * Parses one RTP audio packet (from the data socket or a resend reply) and routes it through the
     * reorder buffer. [src] may be a reused receive buffer, so the payload is copied out before any
     * cross-thread handoff. Thread-safe: the reorder buffer + dedup are accessed under [reorderLock].
     */
    private fun handleRtpPacket(src: ByteArray, offset: Int, length: Int) {
        if (length <= RTP_HEADER) return
        val seq = ((src[offset + 2].toInt() and 0xFF) shl 8) or (src[offset + 3].toInt() and 0xFF)
        // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted audio payload (copied out of src).
        val payload = src.copyOfRange(offset + RTP_HEADER, offset + length)
        var resend: IntArray? = null
        synchronized(reorderLock) {
            if (isDuplicateSeq(seq)) { dupCount++; return }
            resend = enqueueInOrder(seq, payload)
        }
        // Send the resend request OUTSIDE the reorder lock — never hold it across socket I/O.
        resend?.let { requestResend(it[0], it[1]) }
    }

    /**
     * Inserts [seq]/[payload] into the reorder buffer and releases all now-contiguous packets to the
     * player in order. Returns the [startSeq, count] of a missing range to resend (or null). Under [reorderLock].
     */
    private fun enqueueInOrder(seq: Int, payload: ByteArray): IntArray? {
        if (nextSeq < 0) { nextSeq = seq; maxSeq = seq }      // first packet anchors the stream
        // Ignore packets older than what we've already released (a late resend we gave up on).
        if (seqDiff(seq, nextSeq) < 0) return null
        reorder[seq] = payload
        // New forward gap → ask the sender to resend the packets between the old high-water mark and here.
        val resend = if (maxSeq >= 0 && seqDiff(seq, maxSeq) > 1)
            intArrayOf((maxSeq + 1) and 0xFFFF, seqDiff(seq, maxSeq) - 1) else null
        if (seqDiff(seq, maxSeq) > 0) maxSeq = seq
        releaseContiguous()
        // If a hole stays unfilled and the buffer runs too far ahead, skip past it so playback never
        // stalls (a brief glitch beats indefinite silence).
        if (reorder.isNotEmpty() && seqDiff(maxSeq, nextSeq) > MAX_REORDER_HOLD) {
            while (seqDiff(maxSeq, nextSeq) > MAX_REORDER_HOLD && !reorder.containsKey(nextSeq)) {
                nextSeq = (nextSeq + 1) and 0xFFFF
            }
            releaseContiguous()
        }
        return resend
    }

    /** Hands every packet contiguous from [nextSeq] to the player, advancing [nextSeq]. Under [reorderLock]. */
    private fun releaseContiguous() {
        while (true) {
            val p = reorder.remove(nextSeq) ?: break
            if (!frameQueue.offer(p)) { frameQueue.poll(); frameQueue.offer(p); qDropCount++ }
            nextSeq = (nextSeq + 1) and 0xFFFF
        }
    }

    /**
     * Sends a RAOP resend request (control type 0x55) for [count] packets starting at [startSeq].
     * Guarded by [sendLock] (separate from [reorderLock]) so two receive threads don't send + bump
     * [resendCtr] concurrently. Runs outside the reorder lock — never blocks the decode path on I/O.
     */
    private fun requestResend(startSeq: Int, count: Int) {
        if (count <= 0 || count > MAX_RESEND_RANGE) return
        synchronized(sendLock) {
            val addr = senderCtrlAddr ?: return    // unknown until the sender's first control packet
            val req = ByteArray(8)
            req[0] = 0x80.toByte()
            req[1] = (RTP_TYPE_RESEND_REQUEST or 0x80).toByte()   // marker bit set, per RAOP
            req[2] = (resendCtr ushr 8).toByte(); req[3] = resendCtr.toByte()
            req[4] = (startSeq ushr 8).toByte();   req[5] = startSeq.toByte()
            req[6] = (count ushr 8).toByte();      req[7] = count.toByte()
            resendCtr = (resendCtr + 1) and 0xFFFF
            resendReqCount++
            runCatching { controlSocket.send(DatagramPacket(req, req.size, addr)) }
        }
    }

    /** Signed 16-bit sequence distance a − b in (−32768, 32767], so wraparound compares correctly. */
    private fun seqDiff(a: Int, b: Int): Int = (((a - b) and 0xFFFF) xor 0x8000) - 0x8000

    /**
     * Playback thread: decrypt + decode queued frames and write PCM to AudioTrack. This thread is
     * the SOLE owner of [codec] and [audioTrack] — it creates them here and releases them in the
     * finally block, so no other thread ever touches the codec concurrently (see [stop]).
     */
    private fun runPlayback() {
        try {
            initDecoder()
            initAudioTrack()
            awaitPrimedQueue()
            while (running) {
                val payload = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val decrypted = decryptPacket(payload)
                    if (alac != null) playAlacFrame(decrypted) else decodeFrame(decrypted)
                } catch (e: Exception) {
                    if (running) Logger.e("Audio: frame decode error", e)
                }
                resyncIfBacklogged()
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio playback error", e)
        } finally {
            // Release on the same thread that used the codec — never cross-thread (avoids SIGABRT).
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { alac?.close() }
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            codec = null
            alac = null
            audioTrack = null
            Logger.i("AudioStreamServer stopped")
        }
    }

    /** Decode one decrypted ALAC frame to PCM and write it to AudioTrack (blocking, paces playback). */
    private fun playAlacFrame(frame: ByteArray) {
        val pcm = alac?.decode(frame) ?: return
        if (firstPcm) { Logger.i("Audio: first decoded ALAC PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
        audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        emitEnergy(pcm)
    }

    /** True if this RTP sequence was already processed (a redundant retransmission). */
    private fun isDuplicateSeq(seq: Int): Boolean {
        if (!seenSeqSet.add(seq)) return true          // add() returns false when already present
        seenSeqs.addLast(seq)
        if (seenSeqs.size > SEQ_WINDOW) seenSeqSet.remove(seenSeqs.removeFirst())
        return false
    }

    /** AES-128-CBC decrypt the whole-block portion; the trailing < 16 bytes stay cleartext. */
    private fun decryptPacket(payload: ByteArray): ByteArray {
        val encryptedLen = (payload.size / 16) * 16
        if (encryptedLen == 0) return payload
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)   // fresh IV per packet (RAOP)
        val out = payload.copyOf()
        cbcCipher.doFinal(payload, 0, encryptedLen, out, 0)
        return out
    }

    private fun decodeFrame(aac: ByteArray) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            mc.getInputBuffer(inIdx)!!.apply { clear(); put(aac) }
            mc.queueInputBuffer(inIdx, 0, aac.size, 0, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIdx = mc.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            val outBuf: ByteBuffer = mc.getOutputBuffer(outIdx)!!
            val pcm = ByteArray(info.size)
            outBuf.position(info.offset); outBuf.get(pcm)
            if (firstPcm) { Logger.i("Audio: first decoded PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
            // Blocking write paces playback to the audio clock and drops no PCM. Safe here because
            // this runs on the dedicated playback thread, not the socket-receive thread.
            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            emitEnergy(pcm)
            mc.releaseOutputBuffer(outIdx, false)
            outIdx = mc.dequeueOutputBuffer(info, 0)
        }
    }

    private var lastEnergyMs = 0L
    /**
     * Bass-onset beat detection.
     *
     * Plain RMS loudness pulses on everything — vocals, cymbals, a loud pad — so the backdrop
     * shimmered constantly instead of moving with the beat. This instead mono-downmixes, low-passes
     * to keep only bass, measures energy in short windows, and fires when a window jumps well above
     * the recent running mean. The result is a punch that decays, which is what reads as a beat.
     */
    private fun emitEnergy(pcm: ByteArray) {
        // Window energy, mono, low-passed to ~130Hz with a one-pole filter.
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            var sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            i += 2
            if (channels >= 2 && i + 1 < pcm.size) {
                sample = (sample + ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()) / 2.0
                i += 2
            }
            lowPass += LP_ALPHA * (sample - lowPass)
            sum += lowPass * lowPass
            count++
        }
        if (count == 0) return
        val level = Math.sqrt(sum / count) / 32768.0

        // Running mean/variance over roughly the last second of windows.
        history[historyIdx % history.size] = level
        historyIdx++
        val n = minOf(historyIdx, history.size)
        var mean = 0.0
        for (k in 0 until n) mean += history[k]
        mean /= n
        var variance = 0.0
        for (k in 0 until n) { val d = history[k] - mean; variance += d * d }
        val stddev = Math.sqrt(variance / n)

        val now = System.currentTimeMillis()
        val isOnset = n >= 8 && level > mean + ONSET_SIGMA * stddev && now - lastOnsetMs > REFRACTORY_MS
        if (isOnset) {
            lastOnsetMs = now
            envelope = 1f
        } else {
            // Punch, then fall away over ~250ms.
            val dt = (now - lastEnergyMs).coerceAtLeast(0L)
            envelope = (envelope - dt / DECAY_MS).coerceAtLeast(0f)
        }
        // Rate-limit emissions so a beat doesn't spam the UI thread.
        if (now - lastEnergyMs < 40 && !isOnset) return
        lastEnergyMs = now
        // Hold the pulse until the audio it describes is actually audible. Energy is measured as
        // PCM enters the queue, but that sample is heard a whole presentation latency later, so
        // emitting immediately made the backdrop flash ahead of the beat.
        emitDelayed(envelope)
    }


    private fun initDecoder() {
        if (codecType == CT_ALAC) {
            // macOS sends ALAC (lossless) for system-audio AirPlay regardless of our advertised
            // formats, and this TV has no hardware ALAC codec — so we decode in software via the
            // bundled Apple ALAC decoder (libalac.so). frameLength comes from the SETUP spf.
            // System.loadLibrary throws UnsatisfiedLinkError — an Error, not an Exception, so the
            // playback loop's `catch (e: Exception)` let it kill the whole app when the packaged
            // libalac.so was unreadable. Silence beats a crash: the session stays up, and the log
            // says why there is no audio.
            alac = runCatching { AlacDecoder(sampleRate, channels, framesPerPacket) }
                .onFailure { Logger.e("ALAC decoder unavailable — audio will be silent", it) }
                .getOrNull()
            if (alac != null) {
                Logger.i("Audio decoder: ALAC ${sampleRate}Hz x$channels spf=$framesPerPacket (ct=2)")
            }
            return
        }
        // ct=8 AAC-ELD (mirroring, spf 480) vs ct=4 AAC-LC (audio-only / Apple Music, spf 1024).
        val isAacLc = codecType == CT_AAC_LC
        val profile = if (isAacLc) MediaCodecInfo.CodecProfileLevel.AACObjectLC
                      else MediaCodecInfo.CodecProfileLevel.AACObjectELD
        val asc = if (isAacLc) buildAacLcAsc(sampleRate, channels) else buildAacEldAsc(sampleRate, channels)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        Logger.i("Audio decoder: ${if (isAacLc) "AAC-LC" else "AAC-ELD"} ${sampleRate}Hz x$channels (ct=$codecType)")
    }

    /**
     * Waits for a few frames to accumulate before decoding the first one.
     *
     * Starting on packet zero meant playback began with an empty pipeline, so the very first
     * scheduling delay was already an underrun — the "choppy for the first second" symptom. Bounded
     * by [PRIME_TIMEOUT_MS] so a sender that opens the stream and sends nothing can't stall here.
     */
    private fun awaitPrimedQueue() {
        val deadline = System.currentTimeMillis() + PRIME_TIMEOUT_MS
        while (running && frameQueue.size < targetDepthFrames && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        Logger.i("Audio: primed with ${frameQueue.size}/$targetDepthFrames frames " +
            "(~${targetDepthFrames * framesPerPacket * 1000 / sampleRate}ms presentation latency)")
    }

    /**
     * Drops the oldest frames when the queue is running persistently deep.
     *
     * AudioTrack.write with WRITE_BLOCKING paces at exactly realtime, so nothing ever drains an
     * accumulated backlog: once playback falls ~700ms behind it stays there, and the queue's own
     * overflow eviction becomes the only relief — one audible glitch per dropped frame, forever.
     * Discarding a block in one go costs a single artefact and puts latency back where it belongs.
     */
    private fun resyncIfBacklogged() {
        if (frameQueue.size < targetDepthFrames * 2) return
        var dropped = 0
        while (frameQueue.size > targetDepthFrames && frameQueue.poll() != null) dropped++
        if (dropped > 0) {
            qDropCount += dropped
            Logger.i("Audio: backlog resync — dropped $dropped frames, queue=${frameQueue.size}")
        }
    }

    /**
     * Emits [value] once the AudioTrack has actually played the audio it was measured from.
     *
     * Uses the track's own reported latency where available — buffered frames plus whatever the
     * output path adds — so the delay tracks reality instead of a constant. Bluetooth's link delay
     * is not exposed by Android and is not included; the user's audio trim covers that.
     */
    private fun emitDelayed(value: Float) {
        val track = audioTrack
        val bufferedMs = if (track != null) {
            val queuedFrames = frameQueue.size * framesPerPacket
            val trackFrames = runCatching { track.bufferSizeInFrames }.getOrDefault(0)
            ((queuedFrames + trackFrames).toLong() * 1000L / sampleRate.coerceAtLeast(1))
        } else 0L
        val delay = bufferedMs + extraDelayMs + outputLatencyMs()
        if (delay <= 0L) { onEnergy(value); return }
        energyHandler.postDelayed({ onEnergy(value) }, delay)
    }

    /**
     * How far behind real time this device's audio output actually is, measured rather than assumed.
     *
     * AudioTrack.getTimestamp() reports the frame the hardware is playing and when it played it, so
     * the gap between frames written and frames heard is the true output latency. Covers the mixer
     * and HAL. It does NOT cover a Bluetooth link's own delay, which Android exposes no API for —
     * that is what the user's audio trim is for.
     */
    private fun outputLatencyMs(): Long {
        val track = audioTrack ?: return 0L
        return runCatching {
            val ts = android.media.AudioTimestamp()
            if (!track.getTimestamp(ts)) return 0L
            val written = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val pending = written - ts.framePosition
            if (pending <= 0) 0L else pending * 1000L / sampleRate.coerceAtLeast(1)
        }.getOrDefault(0L)
    }

    /** Sets playback volume from the sender's AirPlay volume (−30 dB … 0 dB, or ≤ −144 = mute). */
    fun setVolume(airplayVolume: Float) {
        volumeGain = if (airplayVolume <= -144f) 0f else ((airplayVolume + 30f) / 30f).coerceIn(0f, 1f)
        runCatching { audioTrack?.setVolume(volumeGain) }
    }

    private fun initAudioTrack() {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bytesPerSec = sampleRate * channels * 2
        // A floor-sized buffer left no slack for scheduling hiccups: pressing Home destroys the
        // Surface and busies the main thread, the writer misses its window, and the upstream queue
        // hits its 96-frame ceiling and evicts (observed queue=89, qDrop=10 — audible glitches).
        // The sender advertises latencyMin=11025 (250ms), so it expects far more buffering than the
        // ~40ms floor. Take the larger of the floor and TARGET_BUFFER_MS.
        val targetBytes = bytesPerSec * TARGET_BUFFER_MS / 1000
        val bufferBytes = maxOf(minBuf, targetBytes)
        Logger.i("AudioTrack: minBuf=${minBuf}B (~${minBuf * 1000 / bytesPerSec}ms), " +
            "buffer=${bufferBytes}B (~${bufferBytes * 1000 / bytesPerSec}ms latency)")
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(volumeGain); it.play() }
    }

    companion object {
        const val CT_ALAC = 2      // SETUP ct for ALAC (system-audio AirPlay; decoded in software)
        const val CT_AAC_LC = 4    // SETUP ct for AAC-LC (audio-only / Apple Music)
        const val CT_AAC_ELD = 8   // SETUP ct for AAC-ELD (screen-mirroring realtime audio)

        // ALAC frameLength macOS uses for realtime system-audio AirPlay (SETUP spf). Used to size
        // the ALAC magic cookie + decode buffers when the sender omits spf.
        private const val DEFAULT_ALAC_FRAMES = 352

        private const val RTP_HEADER = 12

        // RAOP control-channel RTP payload types for packet-loss recovery.
        private const val RTP_TYPE_RESEND_REQUEST = 0x55   // we → sender: "resend these seqs"
        private const val RTP_TYPE_RESEND_REPLY = 0x56     // sender → us: a resent audio packet
        private const val RESEND_REPLY_HEADER = 4          // 4-byte resend header before the embedded RTP

        // Max packets to hold while waiting for a gap to fill before skipping it (bounds the worst-case
        // added latency to ~this many frames; in-order traffic adds zero). ~32 ≈ 0.25–0.35 s.
        private const val MAX_REORDER_HOLD = 32

        // Don't ask for an absurd resend range (a huge gap = a real stall, not a few lost packets).
        private const val MAX_RESEND_RANGE = 128

        // Jitter buffer depth between the receive and playback threads (~1 s at 92 frames/s).
        // Deep enough to actually hold the requested delay. At spf=352/44.1kHz each frame is ~8ms,
        // so 96 frames capped the achievable latency at ~380ms — a 2000ms trim silently did nothing
        // because targetDepthFrames is clamped to half the capacity.
        private const val AUDIO_QUEUE_CAPACITY = 1024

        /** AudioTrack buffer target. The sender's advertised latencyMin is 250ms; stay under it. */
        private const val TARGET_BUFFER_MS = 300

        private const val PRIME_TIMEOUT_MS = 700L

        /** Silence on the audio stream that means "paused" rather than "a packet was late". */
        private const val AUDIO_IDLE_MS = 700

        /** One-pole low-pass coefficient for ~130Hz at 44.1kHz — keeps bass, drops the rest. */
        private const val LP_ALPHA = 0.018
        /** How far above the running mean a window must sit to count as a beat. */
        private const val ONSET_SIGMA = 1.5
        private const val REFRACTORY_MS = 120L
        private const val DECAY_MS = 250f

        // Sliding window of recently-played RTP sequence numbers for duplicate suppression.
        // ~11 s at 92 packets/s — far longer than any retransmit gap, far shorter than the
        // 65536-packet (~12 min) sequence-number wrap, so no false positives from wraparound.
        private const val SEQ_WINDOW = 1024

        private fun hex(b: ByteArray, len: Int): String =
            (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }

        /** A UDP socket bound to the IPv6 wildcard (dual-stack), OS-assigned port. */
        private fun ipv6Socket(): DatagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0))
        }

        @Suppress("unused")
        private val AUDIO_MANAGER_HINT = AudioManager.STREAM_MUSIC

        /**
         * Builds the AAC-ELD AudioSpecificConfig (csd-0) for the negotiated [sampleRate] and
         * [channels], instead of hardcoding 44.1 kHz/stereo. Layout: AOT escape(5)=31 + ext(6)=7
         * (AOT 39 = ELD), samplingFrequencyIndex(4), channelConfiguration(4), then the fixed
         * ELDSpecificConfig tail (frameLengthFlag=1 for 480 samples; resilience/SBR flags 0;
         * ELDEXT_TERM). For 44.1 kHz stereo this yields the canonical bytes F8 E8 50 00.
         */
        /** ISO 14496-3 sampling-frequency index for an AAC AudioSpecificConfig. */
        private fun freqIndexFor(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; 7350 -> 12
            else -> 4   // default to 44.1 kHz
        }

        /**
         * Builds the AAC-LC AudioSpecificConfig (csd-0): AOT(5)=2 (LC), samplingFrequencyIndex(4),
         * channelConfiguration(4), GASpecificConfig flags(3)=0. For 44.1 kHz stereo → bytes 12 10.
         */
        fun buildAacLcAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = freqIndexFor(sampleRate)
            var bits = 0
            var n = 0
            fun put(value: Int, width: Int) { bits = (bits shl width) or (value and ((1 shl width) - 1)); n += width }
            put(2, 5); put(freqIndex, 4); put(channels, 4); put(0, 3)   // 16 bits total
            bits = bits shl (16 - n)
            return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
        }

        fun buildAacEldAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = freqIndexFor(sampleRate)
            var bits = 0L
            var n = 0
            fun put(value: Int, width: Int) {
                bits = (bits shl width) or (value.toLong() and ((1L shl width) - 1))
                n += width
            }
            put(31, 5); put(7, 6)                 // AOT escape → 39 (ELD)
            put(freqIndex, 4); put(channels, 4)
            put(1, 1); put(0, 4); put(0, 4)       // frameLengthFlag=1, resilience/SBR=0, ELDEXT_TERM=0
            bits = bits shl (32 - n)              // left-align into 4 bytes
            return byteArrayOf(
                (bits ushr 24).toByte(),
                (bits ushr 16).toByte(),
                (bits ushr 8).toByte(),
                bits.toByte()
            )
        }
    }
}
