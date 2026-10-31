package com.javimetallab.morselightcrypt.receive

import com.javimetallab.morselightcrypt.morse.MorseTiming
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMeterTest {

    private val rate = 44_100

    /** Morse for [text] as audio: [hz] beeps over white noise of [noise] amplitude. */
    private fun beeps(
        text: String, wpm: Int, hz: Int, amplitude: Double = 0.5, noise: Double = 0.02,
        seed: Int = 1, tailSeconds: Double = 0.5
    ): FloatArray {
        val unit = MorseTiming.unitMs(wpm)
        val pulses = MorseTiming.schedule(text)
        val lead = rate / 2 // half a second of noise first
        val total = lead + (tailSeconds * rate).toInt() + pulses.sumOf { it.units * unit * rate / 1000 }.toInt()
        val random = Random(seed)
        val out = FloatArray(total) { ((random.nextDouble() * 2 - 1) * noise).toFloat() }
        var at = lead
        for (p in pulses) {
            val n = (p.units * unit * rate / 1000).toInt()
            if (p.on) for (i in 0 until n) out[at + i] += (amplitude * sin(2 * PI * hz * (at + i) / rate)).toFloat()
            at += n
        }
        return out
    }

    /** Runs audio through the meter and the receiver the app uses by ear. */
    private fun receive(audio: FloatArray, hintWpm: Int): Pair<String, Int?> {
        val meter = ToneMeter(rate)
        val engine = ReceiverEngine(historySize = 400, tracker = ToneMeter.tracker())
        var state = ReceiveState()
        var tone: Int? = null
        var samples = 0L
        val chunk = meter.hopSize
        var i = 0
        while (i < audio.size) {
            val n = minOf(chunk, audio.size - i)
            val piece = audio.copyOfRange(i, i + n)
            for (r in meter.feed(piece, n)) {
                state = engine.onFrame(r.level, (samples + n) * 1_000_000_000L / rate, MorseTiming.unitMs(hintWpm).toDouble())
                tone = r.toneHz
            }
            samples += n
            i += n
        }
        return state.decoded.text.trim() to tone
    }

    @Test
    fun readsBeepsAtTheSendersPitch() {
        val (text, tone) = receive(beeps("SOS PARIS", wpm = 12, hz = 650), hintWpm = 8)
        assertEquals("SOS PARIS", text)
        assertNotNull(tone)
        assertTrue("followed $tone Hz", abs(tone!! - 650) <= ToneMeter.STEP_HZ)
    }

    @Test
    fun readsTheAppsOwnPitchFast() {
        val (text, _) = receive(beeps("HOLA MUNDO", wpm = 20, hz = 700), hintWpm = 20)
        assertEquals("HOLA MUNDO", text)
    }

    @Test
    fun readsAQuietToneThroughNoise() {
        // Tone 20 dB above the noise floor of each filter, far below full scale.
        val (text, _) = receive(beeps("CQ CQ", wpm = 10, hz = 800, amplitude = 0.02, noise = 0.01), hintWpm = 10)
        assertEquals("CQ CQ", text)
    }

    @Test
    fun ignoresAHumAtAnotherPitch() {
        val audio = beeps("TEST", wpm = 10, hz = 600)
        // A steady, softer hum at 1200 Hz all along, like a fan or a fridge.
        for (i in audio.indices) audio[i] += (0.1 * sin(2 * PI * 1200 * i / rate)).toFloat()
        val (text, tone) = receive(audio, hintWpm = 10)
        assertEquals("TEST", text)
        assertTrue("followed $tone Hz", abs(tone!! - 600) <= ToneMeter.STEP_HZ)
    }

    /**
     * A room's echo: four parallel feedback combs (Schroeder), each decaying
     * 60 dB in [rt60] seconds. The beep then fades out instead of stopping.
     */
    private fun reverb(dry: FloatArray, rt60: Double): FloatArray {
        val out = dry.copyOf()
        for (ms in intArrayOf(29, 37, 41, 47)) {
            val d = rate * ms / 1000
            val g = Math.pow(10.0, -3.0 * ms / 1000 / rt60).toFloat()
            val y = FloatArray(dry.size)
            for (i in dry.indices) y[i] = dry[i] + if (i >= d) g * y[i - d] else 0f
            for (i in out.indices) out[i] += 0.25f * y[i]
        }
        return out
    }

    @Test
    fun readsThroughARoomsEcho() {
        // Like the PC test of 31-oct: HOLA three times at 8 wpm, a gap of 14 units between.
        val dry = beeps("HOLA", wpm = 8, hz = 700)
        val unit = MorseTiming.unitMs(8)
        val gap = FloatArray((14 * unit * rate / 1000).toInt())
        val audio = reverb(dry + gap + dry + gap + dry, rt60 = 0.6)
        val (text, _) = receive(audio, hintWpm = 8)
        assertEquals("HOLA HOLA HOLA", text.split(Regex("\\s+")).joinToString(" "))
    }

    @Test
    fun keepsTheGroupsOfACiphertextOnOneLine() {
        // Like the PC test of 31-oct: an encrypted message twice at 15 wpm, through echo.
        // Groups are words (7 units apart); only the repeat (14 units) starts a new line,
        // otherwise Decrypt would try each group on its own.
        val cipher = "PG4BA QVX22 CDTAK ZND62 WVA"
        val dry = beeps(cipher, wpm = 15, hz = 700)
        val gap = FloatArray((14 * MorseTiming.unitMs(15) * rate / 1000).toInt())
        val audio = reverb(dry + gap + dry, rt60 = 0.6)
        val (text, _) = receive(audio, hintWpm = 15)
        assertEquals("$cipher\n$cipher", text)
    }

    @Test
    fun noLettersOutOfTheNoiseAfterTheMessage() {
        val (text, _) = receive(beeps("OK", wpm = 10, hz = 700, tailSeconds = 15.0), hintWpm = 10)
        assertEquals("OK", text)
    }

    @Test
    fun silenceFollowsNoTone() {
        val meter = ToneMeter(rate)
        val readings = meter.feed(FloatArray(rate))
        assertTrue(readings.all { it.toneHz == null && it.level == 0f })
    }
}
