package com.javimetallab.morselightcrypt.receive

import com.javimetallab.morselightcrypt.crypto.SecretCodec
import com.javimetallab.morselightcrypt.morse.MorseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End to end without a phone: a light flashes a message, a simulated camera
 * samples it at 30 fps with sensor noise, and the engine must read it back.
 */
class ReceiverEngineTest {

    private fun receive(
        text: String,
        unitMs: Long,
        hintUnitMs: Double = unitMs.toDouble(),
        onLagMs: Long = 0,
        fps: Int = 30,
        dark: Float = 30f,
        bright: Float = 200f,
        seed: Int = 3
    ): String {
        val random = Random(seed)
        // Light intervals [start, end) in ms, shifted by the bulb's switch-on lag.
        val intervals = mutableListOf<LongRange>()
        var t = 1000L
        for (pulse in MorseTiming.schedule(text)) {
            val length = pulse.units * unitMs
            if (pulse.on) intervals += (t + onLagMs) until (t + length)
            t += length
        }
        val end = t + 4000

        val engine = ReceiverEngine()
        val frameNs = 1_000_000_000L / fps
        var last = ""
        var ns = random.nextLong(frameNs)
        while (ns / 1_000_000 < end) {
            val ms = ns / 1_000_000
            val lit = intervals.any { ms in it }
            val level = (if (lit) bright else dark) + random.nextFloat() * 12f - 6f
            last = engine.onFrame(level, ns, hintUnitMs).decoded.text
            ns += frameNs
        }
        return last.trim()
    }

    @Test
    fun torchAtEightWpm() {
        assertEquals("HELLO WORLD", receive("hello world", unitMs = 150))
    }

    @Test
    fun torchAtTwelveWpmExpectingEight() {
        assertEquals("SOS SOS", receive("sos sos", unitMs = 100, hintUnitMs = 150.0))
    }

    @Test
    fun slowSmartBulb() {
        // 4 WPM with a bulb that takes 120 ms to light up.
        assertEquals("MESA DERECHA", receive("mesa derecha", unitMs = 300, onLagMs = 120))
    }

    @Test
    fun dimDistantLight() {
        assertEquals("CQ DE EA4", receive("CQ DE EA4", unitMs = 150, dark = 40f, bright = 90f))
    }

    @Test
    fun encryptedMessageSurvivesTheTrip() {
        val cipher = SecretCodec.encrypt("Nos vemos a las 9", "luz de la mesa", iterations = 1000)
        val received = receive(cipher, unitMs = 120)
        assertEquals(cipher, received)
        assertEquals("NOS VEMOS A LAS 9", SecretCodec.decrypt(received, "luz de la mesa", iterations = 1000))
    }

    @Test
    fun noLightNoText() {
        val engine = ReceiverEngine()
        val random = Random(1)
        var ns = 0L
        repeat(300) {
            val state = engine.onFrame(50f + random.nextFloat() * 8f, ns, 150.0)
            assertTrue(state.decoded.text.isEmpty())
            ns += 33_333_333
        }
    }

    /**
     * "HOLA", then the phone is jolted for a second (wild levels, marked
     * unsteady by the meter), then "MUNDO". The jolt must not add letters and
     * must not lose the word read before it.
     */
    @Test
    fun movingInTheMiddleKeepsWhatWasRead() {
        val unitMs = 150L
        val intervals = mutableListOf<LongRange>()
        var t = 1000L
        fun flash(text: String) {
            for (pulse in MorseTiming.schedule(text)) {
                val length = pulse.units * unitMs
                if (pulse.on) intervals += t until (t + length)
                t += length
            }
        }
        flash("HOLA")
        t += 1500
        val joltFrom = t
        t += 1000
        val joltTo = t
        t += 1500
        flash("MUNDO")
        val end = t + 4000

        val engine = ReceiverEngine()
        val random = Random(11)
        var ns = 0L
        var last = ""
        while (ns / 1_000_000 < end) {
            val ms = ns / 1_000_000
            val moving = ms in joltFrom until joltTo
            val level = when {
                moving -> random.nextFloat() * 255f
                intervals.any { ms in it } -> 200f
                else -> 30f
            } + random.nextFloat() * 8f - 4f
            last = engine.onFrame(level, ns, unitMs.toDouble(), steady = !moving).decoded.text
            ns += 33_333_333
        }
        assertEquals("HOLA MUNDO", last.trim())
    }
}
