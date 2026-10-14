package com.javimetallab.morselightcrypt.morse

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SignalDecoderTest {

    /** Turns a message into measured segments, as a real light would produce. */
    private fun segments(
        text: String,
        unitMs: Double,
        biasMs: Double = 0.0,
        jitterMs: Double = 0.0,
        seed: Int = 1
    ): List<Segment> {
        val random = Random(seed)
        fun noise() = if (jitterMs == 0.0) 0.0 else random.nextDouble(-jitterMs, jitterMs)
        val out = mutableListOf(Segment(false, 2000.0))
        for (pulse in MorseTiming.schedule(text)) {
            val bias = if (pulse.on) biasMs else -biasMs
            out += Segment(pulse.on, pulse.units * unitMs + bias + noise())
        }
        // A word gap and a bit: the message ends but no new line starts.
        out += Segment(false, unitMs * 8)
        return out
    }

    private fun decode(segments: List<Segment>, hintUnitMs: Double = 150.0) =
        SignalDecoder.decode(segments, hintUnitMs)

    @Test
    fun cleanSignal() {
        assertEquals("HELLO WORLD ", decode(segments("hello world", 150.0)).text)
    }

    @Test
    fun findsTheSpeedOnItsOwn() {
        // Sender at 15 WPM, receiver expecting 6.
        val result = decode(segments("CQ CQ DE EA4", 80.0, jitterMs = 15.0), hintUnitMs = 200.0)
        assertEquals("CQ CQ DE EA4 ", result.text)
        assertEquals(15, result.wpm)
    }

    @Test
    fun slowBulbThatStretchesEveryFlash() {
        // A smart bulb fading in: +90 ms on each flash and -90 ms on each gap.
        val result = decode(segments("THE QUICK BROWN FOX", 300.0, biasMs = 90.0, jitterMs = 40.0))
        assertEquals("THE QUICK BROWN FOX ", result.text)
    }

    @Test
    fun torchThatSwitchesOffLate() {
        val result = decode(segments("SOS 123", 120.0, biasMs = -30.0, jitterMs = 20.0, seed = 7))
        assertEquals("SOS 123 ", result.text)
    }

    @Test
    fun manyRandomRuns() {
        repeat(50) { seed ->
            val result = decode(segments("PARIS PARIS", 150.0, biasMs = 40.0, jitterMs = 35.0, seed = seed))
            assertEquals("seed $seed", "PARIS PARIS ", result.text)
        }
    }

    @Test
    fun glitchesAreIgnored() {
        val clean = segments("TEST", 150.0)
        // A one-frame flicker in the middle of a gap and a dropout inside a dash.
        val noisy = buildList {
            for (s in clean) {
                if (!s.on && s.ms == 450.0) {
                    add(Segment(false, 200.0)); add(Segment(true, 20.0)); add(Segment(false, 230.0))
                } else if (s.on && s.ms == 450.0) {
                    add(Segment(true, 200.0)); add(Segment(false, 25.0)); add(Segment(true, 225.0))
                } else add(s)
            }
        }
        assertEquals("TEST ", decode(noisy).text)
    }

    @Test
    fun onlyDotsUseTheHint() {
        assertEquals("E E E ", decode(segments("E E E", 150.0)).text)
        assertEquals("T T T ", decode(segments("T T T", 150.0)).text)
    }

    @Test
    fun onlyDashesAreRecognisedFromTheGapsInsideALetter() {
        // "MO": gaps of one unit inside the letters reveal 3-unit pulses, even
        // though the hint expects a much faster sender.
        assertEquals("MO ", decode(segments("MO", 150.0), hintUnitMs = 60.0).text)
    }

    @Test
    fun letterStaysOpenWhileTheLightIsOn() {
        val partial = segments("SOS", 150.0).dropLast(1).dropLast(1) // drop final gap and last dot
        val result = decode(partial + Segment(true, 100.0))
        assertEquals("SO", result.text)
        assertEquals("...", result.pending)
    }

    /**
     * Only dots so far ("H" at 4 WPM): the speed is a guess from the hint and
     * says so; once a dash has come, it is measured. With the measured speed of
     * an earlier message as the hint, the dots read right from the first one.
     */
    @Test
    fun speedIsMeasuredOnlyOnceDotsAndDashesAreSeen() {
        val dotsOnly = decode(segments("H", 300.0), hintUnitMs = 150.0)
        assertEquals(false, dotsOnly.measured)
        // Judged at the hint's speed, the closing silence is a whole new line.
        assertEquals("TTTT\n", dotsOnly.text)

        val both = decode(segments("HO", 300.0), hintUnitMs = 150.0)
        assertEquals(true, both.measured)
        assertEquals(300.0, both.unitMs!!, 1.0)

        assertEquals("H ", decode(segments("H", 300.0), hintUnitMs = both.unitMs!!).text)
    }

    /** Repeats come 14 units apart and each lands on its own line. */
    @Test
    fun repeatsGoOnSeparateLines() {
        val unit = 150.0
        val once = segments("HI THERE", unit).drop(1).dropLast(1)
        val repeated = listOf(Segment(false, 2000.0)) + once + Segment(false, unit * 14) + once +
            Segment(false, unit * 14) + once + Segment(false, unit * 8)
        assertEquals("HI THERE\nHI THERE\nHI THERE ", decode(repeated).text)
    }
}
