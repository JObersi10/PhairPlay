package com.phairplay.media.shazam

import com.phairplay.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TrackIdentifier — names the track when the sender does not.
 *
 * ARMED ONLY WHEN THE SENDER SENDS NO METADATA. Apple Music, Podcasts and anything else that pushes
 * a now-playing plist name their own track, and for those this never runs: [request] is called
 * only where `NowPlayingInfo.hasMetadata` is false. Raw system audio — a browser tab, a game, a video in
 * Safari — is the case this exists for, and it is the case where the screen currently shows nothing
 * but "Audio from <device>".
 *
 * A SINGLETON, because there is only ever one. Audio has a single owner slot
 * (`AirPlayReceiver.audioOwnerSlot`) no matter how many senders are mirroring, so there is exactly
 * one PCM stream that could ever be identified. Making this an instance would mean threading it
 * through `AirPlayReceiver` to a per-slot `AudioStreamServer` for a thing that cannot have two
 * instances.
 *
 * IT RE-CHECKS ON A TIMER, because it has to. Nameless audio has no track identity, so there is no
 * "track changed" event to hang a re-identification on — a browser tab moving to the next song in a
 * playlist looks exactly like the same song continuing. Keying the answer to the SENDER instead
 * meant one identification per session: the first song's title stayed on screen through every song
 * after it, which is the normal way someone uses a YouTube or TikTok tab.
 *
 * So it re-identifies every [REIDENTIFY_MS]. That period cannot be much shorter: one capture is
 * twelve seconds, so anything under that is continuous fingerprinting, and a private endpoint being
 * asked five times a minute is how a client gets refused. Thirty seconds is twelve of capture and
 * eighteen idle.
 *
 * A MISS DOES NOT CLEAR THE TITLE. Lookups fail for ordinary reasons — a quiet passage, an intro, a
 * spoken section — and blanking a correct title because one twelve-second window happened to be
 * unrecognisable is worse than leaving it. The only thing that clears an identification is the
 * audio actually going quiet for [QUIET_CLEAR_MS], which is the one signal that genuinely means
 * "whatever this was, it is over".
 */
object TrackIdentifier {

    /** Called on a background thread when a track is identified. Never called for a miss. */
    @Volatile
    var onIdentified: ((ShazamClient.Match) -> Unit)? = null

    /**
     * Called when the audio has been quiet long enough that whatever was playing is over, so the
     * name on screen should go rather than sit under silence.
     */
    @Volatile
    var onCleared: (() -> Unit)? = null

    /**
     * Whether the fingerprint may be sent at all — the user's setting.
     *
     * Off by default and checked at [request] rather than at send time, so a disarmed identifier
     * does not even allocate the capture buffer.
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * Seconds between re-identifications, from AppSettings.identifyIntervalSec.
     *
     * Clamped to at least one capture window: a period shorter than the twelve seconds it takes to
     * gather a fingerprint cannot be honoured, and pretending otherwise would just mean the timer
     * is always already due.
     */
    @Volatile
    var intervalSec: Int = 30
        set(value) {
            field = value.coerceAtLeast(PcmCapture.DEFAULT_WINDOW_SECONDS)
        }

    @Volatile
    private var capture: PcmCapture? = null

    /**
     * Set by [request], cleared once a capture exists.
     *
     * The arming is split in two because the two halves of the decision live on different threads
     * and neither knows the other's half: the service knows whether the sender supplied metadata,
     * and only the audio thread knows what sample rate and channel count were actually negotiated.
     * Passing the format down from the service would mean plumbing it out of a per-slot
     * `AudioStreamServer` and back, to arrive at the same place the PCM already comes from.
     */
    @Volatile
    private var wanted = false

    /** Guards against a second lookup being started while one is in flight. */
    private val inFlight = AtomicBoolean(false)

    /** When the last capture was started, so the re-check can be paced. */
    @Volatile private var lastAttemptMs = 0L

    /** The last time the audio was above [SILENCE_LEVEL]. Drives the quiet-clear. */
    @Volatile private var lastLoudMs = 0L

    /** Whether there is currently a name on screen that a quiet stretch should clear. */
    @Volatile private var holdingResult = false

    /**
     * Single-threaded and created once. Identification is rare and bursty — a few hundred
     * milliseconds of FFTs followed by a network wait — so a pool would be idle threads, and doing
     * it on the packet thread would stall audio for the duration.
     */
    /** How long the audio must stay quiet before the name is removed. */
    private const val QUIET_CLEAR_MS = 15_000L

    /** Peak sample below which a packet counts as silence (~ -36 dBFS of a 16-bit range). */
    private const val SILENCE_LEVEL = 512

    /** Every Nth byte is examined; see [isAudible]. */
    private const val SILENCE_STRIDE = 64

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shazam-identify").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /**
     * Asks for the next twelve seconds of audio to be identified.
     *
     * Idempotent per track: calling it while a capture is already running is ignored, because the
     * sender re-pushes its (still empty) metadata several times a second and each push would
     * otherwise throw away the samples gathered so far and start again — which never completes.
     */
    fun request() {
        if (!enabled || wanted || capture != null || inFlight.get()) return
        wanted = true
    }

    /** Called from [offer] once a match has been applied, so the quiet-clear knows to watch. */
    private fun noteResultHeld() {
        holdingResult = true
    }

    /** Stops and discards any capture. Called when metadata arrives, or the session ends. */
    fun cancel() {
        if (capture != null || wanted) Logger.i("Shazam: stopped listening")
        wanted = false
        capture = null
        holdingResult = false
        lastAttemptMs = 0L
        lastLoudMs = 0L
    }

    /**
     * Feeds decoded PCM. Called from the audio packet thread for every decoded packet.
     *
     * The disarmed path is a single volatile read and a return, which is what makes it acceptable
     * to call unconditionally from a thread that is decoding audio in real time.
     */
    fun offer(pcm: ByteArray, sourceSampleRate: Int, sourceChannels: Int) {
        if (!enabled) return
        val now = System.currentTimeMillis()

        // Loudness first, and unconditionally: the quiet-clear has to keep running whether or not a
        // capture is in progress, or a stream that goes silent between re-checks would hold its old
        // title for the whole gap.
        if (isAudible(pcm)) lastLoudMs = now
        if (lastLoudMs == 0L) lastLoudMs = now
        if (holdingResult && now - lastLoudMs > QUIET_CLEAR_MS) {
            Logger.i("Shazam: quiet for ${(now - lastLoudMs) / 1000}s — clearing the name")
            holdingResult = false
            onCleared?.invoke()
        }

        val c = capture ?: run {
            // Re-check on a timer. `wanted` is the immediate path (the setting was just switched on,
            // or a session started); the elapsed check is what keeps it honest afterwards.
            val due = lastAttemptMs != 0L && now - lastAttemptMs >= intervalSec * 1000L
            if (!wanted && !due) return
            if (inFlight.get()) return
            wanted = false
            lastAttemptMs = now
            Logger.i("Shazam: listening for ${PcmCapture.DEFAULT_WINDOW_SECONDS}s " +
                "(${sourceSampleRate}Hz x$sourceChannels" +
                (if (due) ", ${intervalSec}s re-check" else ", sender supplied no metadata") + ")")
            PcmCapture(sourceSampleRate, sourceChannels).also { capture = it }
        }
        c.offer(pcm)
        if (!c.isFull) return
        // Take the samples and drop the capture BEFORE dispatching, so packets arriving while the
        // lookup runs are not accumulated into a buffer nobody will read.
        capture = null
        val samples = c.take()
        if (!inFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                identify(samples)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Whether a packet carries actual sound.
     *
     * Peak rather than RMS, and sampled rather than exhaustive: this runs on the audio thread for
     * every packet, and the question is only "is anything happening", which the loudest sample in a
     * packet answers as well as an average would and far more cheaply. The stride is why a 352-frame
     * stereo packet costs a couple of dozen comparisons instead of 704.
     */
    private fun isAudible(pcm: ByteArray): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            if (v > SILENCE_LEVEL || v < -SILENCE_LEVEL) return true
            i += SILENCE_STRIDE
        }
        return false
    }

    private fun identify(samples: ShortArray) {
        val started = System.currentTimeMillis()
        val signature = ShazamSignature.generate(samples)
        val fingerprinted = System.currentTimeMillis()
        if (signature.peakCount == 0) {
            // Silence, or something with no structure to fingerprint. Sending it would be a wasted
            // round trip that always misses.
            Logger.i("Shazam: no peaks in ${samples.size / ShazamSignature.SAMPLE_RATE}s of audio — not sent")
            return
        }
        Logger.i("Shazam: ${signature.peakCount} peaks in ${fingerprinted - started}ms, looking up")
        val match = ShazamClient.identify(signature)
        Logger.i("Shazam: lookup took ${System.currentTimeMillis() - fingerprinted}ms")
        // A miss deliberately leaves whatever is on screen alone -- see the class comment. Only the
        // quiet-clear removes a name.
        if (match != null) {
            noteResultHeld()
            onIdentified?.invoke(match)
        }
    }
}
