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
 * ONE ATTEMPT PER ARMING. A miss is not retried on a loop: an unrecognised track stays
 * unrecognised, and hammering a private endpoint with the same twelve seconds is how a client gets
 * refused. [request] again — which happens on the next track change — is what allows another go.
 */
object TrackIdentifier {

    /** Called on a background thread when a track is identified. Never called for a miss. */
    @Volatile
    var onIdentified: ((ShazamClient.Match) -> Unit)? = null

    /**
     * Whether the fingerprint may be sent at all — the user's setting.
     *
     * Off by default and checked at [request] rather than at send time, so a disarmed identifier
     * does not even allocate the capture buffer.
     */
    @Volatile
    var enabled: Boolean = false

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

    /**
     * Single-threaded and created once. Identification is rare and bursty — a few hundred
     * milliseconds of FFTs followed by a network wait — so a pool would be idle threads, and doing
     * it on the packet thread would stall audio for the duration.
     */
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

    /** Stops and discards any capture. Called when metadata arrives, or the session ends. */
    fun cancel() {
        if (capture != null || wanted) Logger.i("Shazam: stopped listening")
        wanted = false
        capture = null
    }

    /**
     * Feeds decoded PCM. Called from the audio packet thread for every decoded packet.
     *
     * The disarmed path is a single volatile read and a return, which is what makes it acceptable
     * to call unconditionally from a thread that is decoding audio in real time.
     */
    fun offer(pcm: ByteArray, sourceSampleRate: Int, sourceChannels: Int) {
        val c = capture ?: run {
            if (!wanted) return
            wanted = false
            Logger.i("Shazam: listening for ${PcmCapture.DEFAULT_WINDOW_SECONDS}s " +
                "(${sourceSampleRate}Hz x$sourceChannels, sender supplied no metadata)")
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
        if (match != null) onIdentified?.invoke(match)
    }
}
