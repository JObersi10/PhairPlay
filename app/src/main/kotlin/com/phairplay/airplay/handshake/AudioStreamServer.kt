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
    /**
     * AudioTrack hardware buffer in ms (AppSettings.audioBufferMs). Charged against the sender's
     * latency budget, so raising it shortens the packet queue by the same amount — see
     * [targetDepthFrames].
     */
    private val trackBufferMs: Int = TARGET_BUFFER_MS,
    /** Additional delay applied to the beat callback only — see AppSettings.beatDelayMs. */
    private val beatDelayMs: Long = 0,
    /** Called ~10x/sec with RMS energy 0..1 for beat-reactive background. */
    val onEnergy: (Float) -> Unit = {},
    /**
     * Called alongside [onEnergy] with three normalised band levels — bass, mid, treble — so the
     * backdrop can react to different parts of the music rather than to one loudness figure.
     *
     * Always a fresh array: it crosses to the main thread, and reusing one buffer would let the UI
     * read a half-written frame.
     */
    val onBands: (FloatArray) -> Unit = {},
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
    private val targetDepthFrames: Int = run {
        // latencyMin is the sender's budget for the WHOLE path, not for this one stage. AudioTrack
        // is written with WRITE_BLOCKING, so its buffer runs essentially full the entire time and
        // its capacity is real, audible delay on top of whatever the queue holds. Priming the queue
        // to the full latencyMin therefore paid the same 250ms twice, and on a Bluetooth output —
        // which adds its own ~150ms — the total landed near a second.
        //
        // Charge the AudioTrack buffer against the budget and prime the queue with the remainder.
        val trackSamples = sampleRate * trackBufferMs / 1000
        val queueSamples = (latencyMinSamples - trackSamples).coerceAtLeast(0)
        (queueSamples / framesPerPacket.coerceAtLeast(1))
            .coerceIn(MIN_QUEUE_FRAMES, AUDIO_QUEUE_CAPACITY / 2)
    }

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
    /** True while the sender is paused — see the payload-size check in [handleRtpPacket]. */
    @Volatile private var audioIdle = false

    /** Consecutive payload-free packets seen; resets on the first real audio frame. */
    private var keepaliveRun = 0

    /**
     * When the last datagram of ANY kind arrived on the data socket.
     *
     * A *paused* sender keeps sending payload-free keepalives, so this is the one signal that
     * separates "user hit pause" from "the sender is gone". Some senders — an iPhone that stops
     * playback without tearing down — leave the RTSP socket open forever, so socket closure alone
     * never ends the session; this is what does.
     */
    @Volatile private var lastPacketAtMs = System.currentTimeMillis()

    /** How long since anything at all arrived, in milliseconds. */
    val silentForMs: Long get() = System.currentTimeMillis() - lastPacketAtMs

    // Beat detection state (playback thread only).
    private var lowPass = 0.0

    // ── Three-band filter bank ───────────────────────────────────────────────────────────────
    // A filter bank rather than an FFT, and worth being precise about why: we need three numbers
    // ten times a second, not a spectrum. Four one-pole filters give real frequency separation for
    // a handful of multiply-adds per sample, where a windowed FFT would cost a transform per block
    // on the same thread that feeds AudioTrack -- the thread whose stalls are audible.
    //
    //   bass   = lp160                 (kick, bassline)
    //   mid    = lp2500 - lp300        (vocals and most instruments)
    //   treble = sample - lp4000       (cymbals, sibilance, air)
    private var lp160 = 0.0
    private var lp300 = 0.0
    private var lp2500 = 0.0
    private var lp4000 = 0.0

    /**
     * Per-band automatic gain, decaying.
     *
     * Absolute band levels are useless to a visual: treble sits an order of magnitude below bass on
     * most material, so a fixed scale leaves the treble orb permanently dead and the bass orb
     * permanently saturated. Each band is normalised against its own recent peak instead, so all
     * three use their full range, and a quiet passage still animates. The peak decays so the bank
     * re-adapts when the material changes.
     */
    private val bandPeak = DoubleArray(3) { BAND_PEAK_FLOOR }
    private val bandLevel = FloatArray(3)
    private val history = DoubleArray(100)
    private var historyIdx = 0
    private var lastOnsetMs = 0L
    private var envelope = 0f
    /** Previous RTP timestamp, for detecting a sender clock re-sync. */
    @Volatile private var lastRtpTs = -1L
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
        // THIS is the pause signal, established by measurement after four wrong guesses.
        //
        // A paused iOS sender does not stop transmitting and does not stop its clock. It keeps
        // sending at the full ~128 packets/sec with the RTP timestamp advancing in real time — the
        // packets are simply empty, 44 bytes of header and no audio. That is why packet arrival,
        // RTP-clock advance, decoded-PCM silence and RTSP FLUSH all failed to detect a pause:
        // every one of them looks identical to playback. Only the payload size gives it away
        // (~5.6 KB/s paused against ~120 KB/s playing).
        if (length <= KEEPALIVE_MAX_BYTES) {
            if (++keepaliveRun >= KEEPALIVE_RUN_TO_PAUSE && !audioIdle) {
                audioIdle = true
                onAudioIdle(true)
            }
            return   // nothing to decode; feeding these to the decoder produced garbage
        }
        keepaliveRun = 0
        if (audioIdle) { audioIdle = false; onAudioIdle(false) }
        // Counted here rather than in the data-socket loop. Retransmitted packets arrive on the
        // CONTROL channel and reach playback through this same function, so a stream being carried
        // by resends looked completely silent to the session watchdog even while it was playing.
        lastPacketAtMs = System.currentTimeMillis()
        val seq = ((src[offset + 2].toInt() and 0xFF) shl 8) or (src[offset + 3].toInt() and 0xFF)
        // RTP timestamp (bytes 4..7) is the sender's own playback clock.
        val rtpTs = ((src[offset + 4].toInt() and 0xFF).toLong() shl 24) or
                    ((src[offset + 5].toInt() and 0xFF).toLong() shl 16) or
                    ((src[offset + 6].toInt() and 0xFF).toLong() shl 8) or
                    (src[offset + 7].toInt() and 0xFF).toLong()
        // A timestamp discontinuity USED TO clear the queue and re-prime here. That was my change and
        // it made playback worse, not better: the reorder buffer already absorbs out-of-order and
        // resent packets, so the only thing the reset added was an audible gap every time it fired --
        // and it fired constantly, because a resend arriving off the control channel legitimately
        // carries an old timestamp. Detection stays, purely as a log line; the buffer is left alone.
        synchronized(reorderLock) {
            val inSequence = nextSeq < 0 || seq == nextSeq
            if (lastRtpTs >= 0 && inSequence) {
                val expected = (lastRtpTs + framesPerPacket) and 0xFFFFFFFFL
                val drift = Math.abs(rtpTs - expected)
                if (drift > RESYNC_JUMP_SAMPLES && drift < 0xF0000000L) {
                    Logger.i("Audio: sender timestamp jumped $drift samples (buffer left intact)")
                }
            }
            if (inSequence) lastRtpTs = rtpTs
        }
        // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted audio payload (copied out of src).
        val payload = src.copyOfRange(offset + RTP_HEADER, offset + length)
        var resend: IntArray?
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
            // Audio priority, so the UI cannot starve playback.
            //
            // Playback already runs on its own thread, which is why it survives most main-thread
            // work -- but a PiP transition resizes the window and saturates the CPU on a stick that
            // is also decoding ALAC, and a default-priority thread loses that fight. The device log
            // shows exactly that: entering PiP takes the queue from 21 to 132 in two seconds with
            // underrun climbing, meaning the writer stopped being scheduled while packets kept
            // arriving. URGENT_AUDIO is the priority the platform's own audio paths use, and it is
            // what stops a redraw from outranking the thread feeding the speakers.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
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
                logHealth()
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
        // Window energy, mono, low-passed to ~130Hz with a one-pole filter. The same pass also runs
        // the three-band bank below, so the PCM is walked once rather than four times.
        val a160 = alphaFor(160.0)
        val a300 = alphaFor(300.0)
        val a2500 = alphaFor(2500.0)
        val a4000 = alphaFor(4000.0)
        var sum = 0.0
        var sumBass = 0.0
        var sumMid = 0.0
        var sumTreble = 0.0
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

            lp160 += a160 * (sample - lp160)
            lp300 += a300 * (sample - lp300)
            lp2500 += a2500 * (sample - lp2500)
            lp4000 += a4000 * (sample - lp4000)
            val mid = lp2500 - lp300
            val treble = sample - lp4000
            sumBass += lp160 * lp160
            sumMid += mid * mid
            sumTreble += treble * treble
            count++
        }
        if (count == 0) return
        val level = Math.sqrt(sum / count) / 32768.0
        updateBands(
            Math.sqrt(sumBass / count) / 32768.0,
            Math.sqrt(sumMid / count) / 32768.0,
            Math.sqrt(sumTreble / count) / 32768.0,
        )

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
            if (lastOnsetMs > 0L) noteOnsetInterval(now - lastOnsetMs)
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

    /**
     * Tempo estimate from the spacing of bass onsets.
     *
     * The onset detector above already fires on the beat, so tempo is the interval between firings —
     * no second analysis pass and no autocorrelation needed.
     *
     * Two things make a naive version useless. Onsets are missed and doubled, so a MEAN interval is
     * dragged badly by a single outlier; the median is not. And a detector cannot tell a beat from
     * its half or double, so raw intervals scatter across octaves — folding each into one octave
     * before comparing is what collapses 60/120/240 onto the same answer.
     *
     * Reported with a confidence, because an estimate is worthless without one: on speech, ambient
     * music or applause the intervals genuinely have no mode, and saying "94 BPM" about them would be
     * a fabrication dressed as a measurement.
     */
    private fun noteOnsetInterval(intervalMs: Long) {
        if (intervalMs < MIN_ONSET_INTERVAL_MS || intervalMs > MAX_ONSET_INTERVAL_MS) return
        onsetIntervals[onsetIdx % onsetIntervals.size] = intervalMs
        onsetIdx++
        val n = minOf(onsetIdx, onsetIntervals.size)
        if (n < MIN_INTERVALS_FOR_BPM) return

        // HARMONIC SCORING, not a median of folded intervals.
        //
        // The median version scored 25-41% confidence on Drake's "Hotline Bling" -- a track with an
        // unmistakable beat -- and never cleared its own threshold. The reason is that folding each
        // interval into one octave only rescues a CLEAN double or half. Real onset detection misses
        // beats, so intervals arrive at 2x, 3x, sometimes 1.5x the true period; folding maps those
        // onto unrelated tempos, which then drag the median and disagree with it. The log's spread
        // of 106, 110, 115, 116 across consecutive estimates is that scatter, not tempo drift.
        //
        // Scoring candidates directly inverts the problem. For each candidate period, an interval
        // counts as agreeing if it lands near ANY small integer multiple of it -- which is exactly
        // what a missed beat produces. A track's true tempo is then the candidate almost every
        // interval agrees with, and the harmonics that used to be noise become evidence for it.
        var bestBpm = 0.0
        var bestScore = 0
        var candidate = BPM_MIN
        while (candidate <= BPM_MAX) {
            val period = 60_000.0 / candidate
            var score = 0
            for (i in 0 until n) {
                val ratio = onsetIntervals[i] / period
                val nearest = Math.round(ratio).toInt()
                if (nearest in 1..MAX_BEAT_MULTIPLE &&
                    Math.abs(ratio - nearest) / nearest <= BPM_TOLERANCE
                ) score++
            }
            // Strictly greater, so the LOWEST tempo wins a tie. Every candidate's double scores at
            // least as well as the candidate itself (twice the period still divides the intervals),
            // so ties broken the other way would report 240 BPM for a 120 BPM track every time.
            if (score > bestScore) { bestScore = score; bestBpm = candidate }
            candidate += BPM_STEP
        }
        val median = bestBpm
        val confidence = bestScore.toFloat() / n

        currentBpm = if (confidence >= BPM_MIN_CONFIDENCE) median.toFloat() else 0f
        val now = System.currentTimeMillis()
        if (now - lastBpmLogMs > BPM_LOG_INTERVAL_MS) {
            lastBpmLogMs = now
            if (currentBpm > 0f) {
                Logger.i("Tempo %.0f BPM (confidence %.0f%%, %d onsets)".format(currentBpm, confidence * 100f, n))
            } else {
                Logger.i("Tempo: no stable beat (best %.0f BPM at %.0f%% — below threshold)".format(median, confidence * 100f))
            }
        }
    }

    private val onsetIntervals = LongArray(24)
    private var onsetIdx = 0
    private var lastBpmLogMs = 0L

    /** Latest tempo estimate, or 0 when no stable beat was found. */
    @Volatile var currentBpm: Float = 0f
        private set

    /**
     * One-pole coefficient for a cutoff of [hz] at the current sample rate.
     *
     * Cheap enough to recompute per block (three exp() calls per ~10ms of audio) and correct across
     * a rate change, which a hard-coded constant is not — the same filter would sit at a different
     * frequency for 44.1k and 48k material.
     */
    private fun alphaFor(hz: Double): Double {
        val sr = sampleRate.coerceAtLeast(8000).toDouble()
        return 1.0 - Math.exp(-2.0 * Math.PI * hz / sr)
    }

    /** Normalises the three raw band RMS figures against their own decaying peaks. */
    private fun updateBands(bass: Double, mid: Double, treble: Double) {
        val raw = RAW_BANDS
        raw[0] = bass; raw[1] = mid; raw[2] = treble
        for (b in 0 until 3) {
            // ASYMMETRIC reference peak: jump straight to a new loudest, fall away very slowly.
            //
            // A symmetric decay makes the AGC fight itself. A transient sets a high reference, the
            // ordinary material that follows normalises against it and reads near zero, the reference
            // then decays until the same material reads high again -- so the level oscillates on a
            // cycle that has nothing to do with the music. The device log showed bass stepping
            // 0.02, 0.51, 0.00, 0.22, 0.17, 0.36, 0.88 across consecutive samples, which is that
            // oscillation, not a beat. Holding the reference for several seconds removes it.
            val peak = bandPeak[b]
            bandPeak[b] = if (raw[b] > peak) raw[b] else {
                (peak * BAND_PEAK_DECAY).coerceAtLeast(BAND_PEAK_FLOOR)
            }
            val ratio = (raw[b] / bandPeak[b]).coerceIn(0.0, 1.0)
            // Gate, then re-expand what is left to the full 0..1 range, so removing the noise floor
            // costs no headroom at the top.
            val norm = ((ratio - BAND_NOISE_FLOOR) / (1.0 - BAND_NOISE_FLOOR)).coerceIn(0.0, 1.0)
            val shaped = Math.pow(norm, BAND_CURVE).toFloat()
            // Envelope follower, fast up and slow down -- how a VU meter behaves, and how a glow
            // should. A raw per-block figure is far too twitchy to drive anything visual: it is
            // measured over ~10ms of audio, so it chatters at a rate the eye reads as noise. Rising
            // quickly keeps the hit on the beat; falling slowly is what makes the decay look smooth.
            val follow = if (shaped > bandLevel[b]) BAND_ATTACK else BAND_RELEASE
            bandLevel[b] += (shaped - bandLevel[b]) * follow
        }
        // Periodic proof that the bank is alive and separating. "Are the orbs actually reacting?" is
        // not answerable by watching them -- a slow orb on a quiet passage looks identical to a dead
        // one. Three numbers a couple of times a second settle it: if they move independently the
        // orbs are following the music, and if one sits at 0.00 that band is the thing to fix.
        val now = System.currentTimeMillis()
        if (now - lastBandLogMs > BAND_LOG_INTERVAL_MS) {
            lastBandLogMs = now
            Logger.i(
                "Bands bass=%.2f mid=%.2f treble=%.2f".format(bandLevel[0], bandLevel[1], bandLevel[2])
            )
        }
        // Rate-limited. This fired once per PCM BLOCK -- around 100 times a second -- so it was
        // posting 100 runnables a second onto the main thread to animate something that redraws at
        // 60fps, and the smoothing constants downstream were tuned for a far slower callback. 30/sec
        // is more than the display can show.
        if (now - lastBandEmitMs < BAND_EMIT_INTERVAL_MS) return
        lastBandEmitMs = now
        val snapshot = floatArrayOf(bandLevel[0], bandLevel[1], bandLevel[2])
        val delay = beatEmitDelayMs()
        if (delay <= 0L) energyHandler.post { onBands(snapshot) }
        else energyHandler.postDelayed({ onBands(snapshot) }, delay)
    }

    private val RAW_BANDS = DoubleArray(3)
    private var lastBandLogMs = 0L
    private var lastBandEmitMs = 0L


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
        // Waiting for "at least N" is not the same as starting at N. Packets arrive far faster than
        // realtime at stream start, so the queue routinely blew past the target before this loop
        // next looked — observed 34 frames against a target of 4. AudioTrack is written with
        // WRITE_BLOCKING, which paces at exactly realtime and therefore never drains a backlog, so
        // every one of those extra frames became permanent delay. Drop the overshoot before the
        // first sample is played: cheap here, impossible to recover later.
        // A prime that timed out with nothing in the queue means the sender opened the stream and
        // sent no audio. Starting playback from empty guarantees an immediate underrun, so say so
        // rather than pretending the buffer is at its target.
        if (frameQueue.isEmpty()) {
            Logger.w("Audio: prime timed out with an empty queue — sender opened the stream but sent nothing")
            return
        }
        val overshoot = frameQueue.size - targetDepthFrames
        if (overshoot > 0) {
            repeat(overshoot) { frameQueue.poll() }
            Logger.i("Audio: dropped $overshoot frame(s) of prime overshoot " +
                "(~${overshoot * framesPerPacket * 1000 / sampleRate}ms that would never drain)")
        }
        Logger.i("Audio: primed with ${frameQueue.size}/$targetDepthFrames frames " +
            "(~${frameQueue.size * framesPerPacket * 1000 / sampleRate}ms presentation latency)")
    }

    /**
     * Drops the oldest frames when the queue is running persistently deep.
     *
     * AudioTrack.write with WRITE_BLOCKING paces at exactly realtime, so nothing ever drains an
     * accumulated backlog: once playback falls ~700ms behind it stays there, and the queue's own
     * overflow eviction becomes the only relief — one audible glitch per dropped frame, forever.
     * Discarding a block in one go costs a single artefact and puts latency back where it belongs.
     */
    /**
     * One line a second saying whether WE are the problem or the sender is.
     *
     * Every audio complaint so far has been diagnosed by inference, and the Mac path in particular
     * has had two contradictory explanations (our buffer vs the sender's timing) with no measurement
     * to settle it. These four numbers separate them:
     *
     *  - `queue` far below target and `underrun` climbing → the sender is not feeding us fast
     *    enough. Nothing on this side can fix that.
     *  - `queue` at the ceiling with `qDrop` climbing → we are not draining fast enough, which is
     *    ours to fix.
     *  - `underrun` flat with both mid-range → the path is healthy and the lag is presentation
     *    latency, i.e. a buffer-size question, not a bug.
     *
     * `getUnderrunCount` is the platform's own count of times the track ran dry, which is the one
     * number that cannot be argued with. Logged at info because Fire OS drops debug for this package.
     */
    private fun logHealth() {
        val now = System.currentTimeMillis()
        if (now - lastHealthLogMs < HEALTH_LOG_INTERVAL_MS) return
        lastHealthLogMs = now
        val underruns = runCatching { audioTrack?.underrunCount ?: 0 }.getOrDefault(0)
        Logger.i(
            "Audio health: queue=${frameQueue.size}/$targetDepthFrames " +
                "underrun=$underruns (+${underruns - lastUnderrunCount}) " +
                "qDrop=$qDropCount resendReq=$resendReqCount resendFill=$resendFillCount",
        )
        lastUnderrunCount = underruns
    }

    /** When the queue first went over the backlog threshold, or 0 while it is healthy. */
    private var backloggedSinceMs = 0L
    /** Underrun count when the backlog timer armed — see [resyncIfBacklogged]. */
    private var underrunsAtBacklogStart = 0
    private var lastHealthLogMs = 0L
    private var lastUnderrunCount = 0

    private fun resyncIfBacklogged() {
        // Only step in for a backlog well clear of normal jitter. At 2x the target this triggered
        // constantly and the "cure" — one artefact per resync — was worse than the latency it was
        // treating.
        // SUSTAINED backlog, not instantaneous. The 4x trigger never fired in practice: the device
        // log shows the queue parking at 58-61 against a target of 18 for minute after minute with
        // qDrop=0, because 4x18 is 72 and it never quite got there. Forty extra frames is ~320ms of
        // permanent added latency -- exactly the "laggy over Mac" complaint, and the resync that was
        // supposed to relieve it sat one frame under its own threshold the entire time.
        //
        // Lowering the multiple alone would bring back what it was raised to fix: a 2x trigger fires
        // on ordinary jitter and each firing is an audible artefact. Requiring the backlog to PERSIST
        // separates the two cases — a jitter spike drains on its own within a second, a real backlog
        // does not drain at all, because WRITE_BLOCKING paces playback at exactly realtime.
        // Two thresholds, not one. The first version armed and disarmed on the same number, so a
        // backlog hovering around it reset the timer on every dip and the trim never happened:
        // the device log shows the queue sitting at 27-37 against a 36 threshold for NINETEEN
        // seconds -- audible lag the whole time -- and only firing once a second spike pushed it to
        // 97 and held it there. Disarming only when the queue is genuinely healthy again means a
        // backlog that hovers still gets trimmed on schedule.
        val depth = frameQueue.size
        if (depth < targetDepthFrames * RESYNC_CLEAR_MULTIPLE) {
            backloggedSinceMs = 0L
            return
        }
        if (depth < targetDepthFrames * RESYNC_TRIGGER_MULTIPLE && backloggedSinceMs == 0L) return
        val now = System.currentTimeMillis()
        if (backloggedSinceMs == 0L) {
            backloggedSinceMs = now
            underrunsAtBacklogStart = runCatching { audioTrack?.underrunCount ?: 0 }.getOrDefault(0)
            return
        }
        if (now - backloggedSinceMs < RESYNC_SUSTAIN_MS) return

        // Do not trim a queue that is OSCILLATING. The device log shows this stream swinging
        // 0 -> 50 -> 0 -> 70 with underruns climbing the whole time and resendFill in the hundreds:
        // heavy packet loss, with retransmits arriving in bursts. That is not a standing backlog,
        // it is the buffer doing its job. Dropping frames off the peak guarantees the next trough
        // underruns, so trimming here actively makes the audio worse -- 300+ frames had been
        // dropped by the end of that log and the underrun count still climbed.
        //
        // A genuine backlog is high AND quiet. If the track ran dry even once while the timer was
        // running, the depth is oscillation, so leave it alone and start the clock over.
        val underruns = runCatching { audioTrack?.underrunCount ?: 0 }.getOrDefault(0)
        if (underruns > underrunsAtBacklogStart) {
            Logger.i("Audio: queue is high but oscillating (underrun +${underruns - underrunsAtBacklogStart}) — not trimming")
            backloggedSinceMs = 0L
            return
        }
        backloggedSinceMs = 0L
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
        val delay = beatEmitDelayMs()
        if (delay <= 0L) { onEnergy(value); return }
        energyHandler.postDelayed({ onEnergy(value) }, delay)
    }

    /**
     * How long to hold a beat measurement before showing it, so the visual lands with the sound.
     *
     * Shared by [emitDelayed] and the band emission: both describe the same PCM, so they must be
     * delayed by the same amount or the orbs would react on a different beat from the pulse.
     */
    private fun beatEmitDelayMs(): Long {
        val track = audioTrack
        val bufferedMs = if (track != null) {
            val queuedFrames = frameQueue.size * framesPerPacket
            val trackFrames = runCatching { track.bufferSizeInFrames }.getOrDefault(0)
            ((queuedFrames + trackFrames).toLong() * 1000L / sampleRate.coerceAtLeast(1))
        } else 0L
        return bufferedMs + extraDelayMs + beatDelayMs + outputLatencyMs()
    }

    /**
     * How far behind real time this device's audio output actually is, measured rather than assumed.
     *
     * AudioTrack.getTimestamp() reports the frame the hardware is playing and when it played it, so
     * the gap between frames written and frames heard is the true output latency. Covers the mixer
     * and HAL. It does NOT cover a Bluetooth link's own delay, which Android exposes no API for —
     * that is what the user's audio trim is for.
     */
    /**
     * The RTP timestamp currently reaching the speakers, or -1 before the first packet.
     *
     * This is the receiver's true playback clock. The newest arrival is ahead of what is audible by
     * everything still queued plus whatever AudioTrack is holding, so both are subtracted. Deriving
     * the progress bar from this instead of extrapolating wall-clock time from a sender push means
     * position cannot drift: it is measured against the same samples the user is hearing, and it
     * stops on its own during a pause because the queue stops advancing.
     */
    fun playingRtpTimestamp(): Long {
        val newest = lastRtpTs
        if (newest < 0) return -1L
        val queuedFrames = frameQueue.size.toLong() * framesPerPacket
        val trackFrames = outputLatencyMs() * sampleRate / 1000L
        return (newest - queuedFrames - trackFrames) and 0xFFFFFFFFL
    }

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
        // dB -> AMPLITUDE, not a straight line through the dB range.
        //
        // The sender's scale is decibels: its slider maps linearly onto -30..0 dB. AudioTrack.setVolume
        // takes a linear amplitude multiplier. Treating (db+30)/30 as that multiplier silently
        // equates the two, which puts -15 dB -- the middle of the sender's slider -- at 0.5 amplitude
        // when it should be 0.178. Every position except the two ends was wrong, and wrong in the
        // direction that makes everything sound too loud until the very bottom of the travel. That is
        // the "volume doesn't tally" report: the ends matched, so it looked close, and nothing in
        // between did.
        volumeGain = when {
            airplayVolume <= -144f -> 0f                       // the sender's mute sentinel
            airplayVolume >= 0f -> 1f
            else -> Math.pow(10.0, (airplayVolume / 20f).toDouble()).toFloat().coerceIn(0f, 1f)
        }
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
        val targetBytes = bytesPerSec * trackBufferMs / 1000
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

        /** How far past the target the queue must run before a resync is worth its artefact. */
        /**
         * Backlog multiple that starts the clock.
         *
         * Was 4 (never reached), then 2 -- and 2 was still too high. The device log shows the queue
         * parking at 26-34 against a target of 18 for fifteen seconds during PiP: above the clear
         * point so it never disarmed, below the 36 trigger so it never armed either. Ten extra
         * frames is ~80ms of latency that simply lived there. 1.5x arms on that.
         */
        private const val RESYNC_TRIGGER_MULTIPLE = 1.5f

        /**
         * Backlog multiple the queue must fall back to before the timer disarms.
         *
         * Below the trigger, so the arm and disarm points differ — see [resyncIfBacklogged].
         */
        private const val RESYNC_CLEAR_MULTIPLE = 1.15f

        /** How long a backlog must persist before it counts as real rather than jitter. */
        private const val RESYNC_SUSTAIN_MS = 3_000L

        // Don't ask for an absurd resend range (a huge gap = a real stall, not a few lost packets).
        private const val MAX_RESEND_RANGE = 128

        // Jitter buffer depth between the receive and playback threads (~1 s at 92 frames/s).
        // Deep enough to actually hold the requested delay. At spf=352/44.1kHz each frame is ~8ms,
        // so 96 frames capped the achievable latency at ~380ms — a 2000ms trim silently did nothing
        // because targetDepthFrames is clamped to half the capacity.
        private const val AUDIO_QUEUE_CAPACITY = 1024

        /**
         * Floor on the jitter buffer, in frames (~90ms at 352 samples/frame).
         *
         * Wi-Fi delivers audio in bursts, and a queue shallower than this cannot absorb one — it
         * either starves or trips the backlog resync. The old floor of 4 frames was 32ms, which is
         * less than a single burst.
         */
        private const val MIN_QUEUE_FRAMES = 11

        /** AudioTrack buffer target. The sender's advertised latencyMin is 250ms; stay under it. */
        /**
         * AudioTrack buffer size.
         *
         * Deliberately modest. This is a hardware buffer whose only job is to survive a scheduling
         * hiccup between writes; network jitter is the queue's job. At 300ms it swallowed the whole
         * of the sender's latency budget, which left the queue at its 4-frame floor — 32ms — and
         * the backlog resync fired on every gust of Wi-Fi jitter, dropping 4-35 frames at a time,
         * continuously. Each of those is an audible glitch.
         */
        private const val TARGET_BUFFER_MS = 100

        private const val PRIME_TIMEOUT_MS = 700L

        /** One health line a second — frequent enough to see a glitch, quiet enough to read. */
        private const val HEALTH_LOG_INTERVAL_MS = 1_000L

        /** Silence on the audio stream that means "paused" rather than "a packet was late". */
        // Backstop only: a sender that goes completely silent (disconnect, sleep) rather than
        // sending keepalives. The real pause signal is payload size — see handleRtpPacket.
        private const val AUDIO_IDLE_MS = 400

        /**
         * Largest packet still considered "no audio". A paused sender's keepalives measured
         * exactly 44 bytes; the smallest real ALAC frame observed was ~600. 64 sits clear of both.
         */
        private const val KEEPALIVE_MAX_BYTES = 64

        /** Keepalives needed before declaring a pause — ~100ms at 128 packets/sec. */
        private const val KEEPALIVE_RUN_TO_PAUSE = 12
        /** Timestamp discontinuity that means the sender restarted its clock (~0.5s at 44.1kHz). */
        private const val RESYNC_JUMP_SAMPLES = 22_050L

        /** One-pole low-pass coefficient for ~130Hz at 44.1kHz — keeps bass, drops the rest. */
        private const val LP_ALPHA = 0.018
        /** How far above the running mean a window must sit to count as a beat. */
        /**
         * Band AGC: how fast a band's reference peak falls when nothing louder arrives. PER CALL,
         * and updateBands runs once per PCM block — roughly 100 times a second.
         *
         * This has now been wrong in BOTH directions, which is worth recording because the two
         * failures look nothing alike on screen. At 0.985 the reference fell by 0.22 every second,
         * collapsed onto the current level, and every band pinned near 1.00 — orbs permanently at
         * full size with no headroom to pulse. Overcorrecting to 0.99985 held the reference for the
         * better part of a minute, so one loud transient early in a track left everything after it
         * normalising against that peak: the log then showed bass living at 0.10–0.30 and the orbs
         * barely grew at all.
         *
         * 0.9995 is ~0.95 per second — the reference tracks the last few seconds of music, which is
         * the timescale a listener judges "loud" against.
         */
        private const val BAND_PEAK_DECAY = 0.9995
        /** Floor for the reference peak, so silence normalises to 0 instead of dividing by ~0. */
        private const val BAND_PEAK_FLOOR = 1e-4

        /**
         * Shaping exponent, just under 1.
         *
         * 1.5 was chosen to fight the pinned-at-1.0 symptom, but it EXPANDS the top of the range by
         * crushing the bottom — and once the AGC bug above was fixed, the bottom was where the music
         * actually lived. A measured 0.34 came out as 0.20, so the curve was removing most of the
         * movement the filter bank had just found. Slightly below 1 lifts the quiet end instead,
         * which is the correction that was wanted all along; the AGC and the noise gate handle range.
         */
        private const val BAND_CURVE = 0.9

        /**
         * Fraction of the reference peak treated as silence. Room tone and codec noise otherwise
         * keep a band a few percent off zero forever, which the expansion curve then lifts back into
         * visible glow on a track that has stopped.
         */
        private const val BAND_NOISE_FLOOR = 0.06
        private const val BAND_LOG_INTERVAL_MS = 2000L
        private const val BAND_EMIT_INTERVAL_MS = 33L

        /** Envelope follower, per call at ~100 calls/sec. Fast attack, slow release. */
        private const val BAND_ATTACK = 0.30f
        private const val BAND_RELEASE = 0.045f

        // ── Tempo estimation ────────────────────────────────────────────────────────────────────
        /** 180 BPM and 60 BPM as intervals — anything outside is a missed or doubled onset. */
        private const val MIN_ONSET_INTERVAL_MS = 200L
        private const val MAX_ONSET_INTERVAL_MS = 2000L
        private const val MIN_INTERVALS_FOR_BPM = 8
        private const val BPM_MIN = 60.0
        private const val BPM_MAX = 180.0
        /** How close an interval must be to the median to count as agreeing with it. */
        private const val BPM_TOLERANCE = 0.08
        private const val BPM_MIN_CONFIDENCE = 0.5f
        /** Candidate resolution. 0.5 BPM is finer than anyone can hear a visual lag against. */
        private const val BPM_STEP = 0.5
        /** How many beats a single missed-onset gap may span and still count as evidence. */
        private const val MAX_BEAT_MULTIPLE = 4
        private const val BPM_LOG_INTERVAL_MS = 5000L

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
