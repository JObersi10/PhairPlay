package com.phairplay.airplay

import com.phairplay.airplay.handshake.SenderClockModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * These tests exist because the failure they guard against is invisible in single-room playback.
 * A clock model that tracks offset but not rate looks perfect on one device and silently drifts
 * two devices apart at the difference of their crystal skews — the exact thing multi-room needs.
 *
 * The skew used throughout is 40ppm, which is what this receiver actually measured against an
 * iPhone (~1.2ms per 30s), not a made-up figure.
 */
class SenderClockModelTest {

    private val second = 1_000_000_000L

    @Test
    fun `reports no confidence until there are enough samples over enough time`() {
        val model = SenderClockModel()
        assertFalse(model.confident)

        // Plenty of samples, but crammed into a millisecond: a perfect line with a meaningless slope.
        repeat(20) { model.addSample(it * 50_000L, it * 50_000L) }
        assertFalse("a fit over 1ms of span must not claim confidence", model.confident)
        assertEquals("must stay offset-only until the fit is meaningful", 1.0, model.rate, 1e-12)
    }

    @Test
    fun `recovers a 40ppm skew from clean samples`() {
        val model = SenderClockModel()
        val skew = 40e-6
        val trueOffset = 123_456_789L

        for (i in 0 until 30) {
            val local = i.toLong() * second
            model.addSample(local, trueOffset + (local * (1 + skew)).toLong())
        }

        assertTrue(model.confident)
        assertEquals("skew should come back as ~40ppm", 40.0, model.skewPpm, 1.0)
    }

    @Test
    fun `converts sender time to local time and back consistently`() {
        val model = SenderClockModel()
        val skew = 40e-6
        val offset = 5_000_000_000L
        for (i in 0 until 30) {
            val local = i.toLong() * second
            model.addSample(local, offset + (local * (1 + skew)).toLong())
        }

        val futureLocal = 60L * second
        val sender = model.senderNanosAt(futureLocal)
        val roundTrip = model.localNanosAt(sender)

        // Sub-microsecond round-trip: this conversion runs on every scheduled audio packet.
        assertTrue(
            "round-trip drifted by ${abs(roundTrip - futureLocal)}ns",
            abs(roundTrip - futureLocal) < 1_000,
        )
    }

    @Test
    fun `beats an offset-only estimate when extrapolating ten minutes ahead`() {
        // The whole point of the class, stated as a measurement.
        val skew = 40e-6
        val offset = 1_000_000L
        val model = SenderClockModel()

        // Observe for 30 seconds...
        for (i in 0 until 30) {
            val local = i.toLong() * second
            model.addSample(local, offset + (local * (1 + skew)).toLong())
        }
        // ...then predict ten minutes out.
        val local = 600L * second
        val trueSender = offset + (local * (1 + skew)).toLong()

        val fittedError = abs(model.senderNanosAt(local) - trueSender)
        // What an offset-only model would have done: freeze the last offset and add it.
        val lastLocal = 29L * second
        val frozenOffset = (offset + (lastLocal * (1 + skew)).toLong()) - lastLocal
        val offsetOnlyError = abs((local + frozenOffset) - trueSender)

        assertTrue(
            "offset-only should be visibly wrong here, was ${offsetOnlyError / 1_000_000.0}ms",
            offsetOnlyError > 20_000_000,      // >20ms — audible against a second speaker
        )
        assertTrue(
            "rate-tracking should be far better: ${fittedError / 1_000_000.0}ms vs " +
                "${offsetOnlyError / 1_000_000.0}ms",
            fittedError < offsetOnlyError / 10,
        )
    }

    @Test
    fun `stays close to the truth despite network jitter on the samples`() {
        val model = SenderClockModel()
        val random = Random(42)
        val skew = 40e-6
        val offset = 7_000_000L

        // ±200us of jitter, comparable to the best-case RTT spread measured on this network.
        for (i in 0 until 60) {
            val local = i.toLong() * second
            val jitter = random.nextLong(-200_000, 200_000)
            model.addSample(local, offset + (local * (1 + skew)).toLong() + jitter)
        }

        assertTrue(model.confident)
        // Least squares over 60 samples should average the jitter out to within a few ppm.
        assertEquals(40.0, model.skewPpm, 5.0)
    }

    @Test
    fun `clamps an absurd slope rather than trusting it`() {
        // A pathological sample set should degrade, not produce a clock that runs at double speed.
        val model = SenderClockModel()
        for (i in 0 until 20) {
            val local = i.toLong() * second
            model.addSample(local, local * 2)
        }
        assertTrue("rate must stay within physically plausible bounds, was ${model.rate}",
            abs(model.skewPpm) <= 500.0)
    }

    @Test
    fun `oldest samples fall out of the window`() {
        val model = SenderClockModel(capacity = 10)
        // Feed a wrong skew first, then the right one; the early samples must stop counting.
        for (i in 0 until 10) {
            val local = i.toLong() * second
            model.addSample(local, (local * (1 + 300e-6)).toLong())
        }
        for (i in 10 until 30) {
            val local = i.toLong() * second
            model.addSample(local, (local * (1 + 40e-6)).toLong())
        }
        assertEquals("stale samples should no longer dominate", 40.0, model.skewPpm, 5.0)
    }

    @Test
    fun `reset returns the model to its unsynchronised state`() {
        val model = SenderClockModel()
        for (i in 0 until 30) model.addSample(i.toLong() * second, i.toLong() * second + 1000)
        model.reset()
        assertFalse(model.confident)
        assertEquals(1.0, model.rate, 1e-12)
        assertEquals(0L, model.offsetNanos)
    }
}
