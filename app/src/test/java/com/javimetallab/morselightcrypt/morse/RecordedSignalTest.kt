package com.javimetallab.morselightcrypt.morse

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stretches measured on a real phone (Galaxy Z Flip4) on 14-oct-2026: a Hue
 * bulb at 10 % flashing "HOLA" at 4 WPM. Clean in the middle, with what went
 * wrong around it: a false 3 s "light" before the message, while the meter
 * had nothing to follow, and dozens of 33-67 ms flickers after it, which once
 * passed for dots and turned the real dots into dashes.
 */
class RecordedSignalTest {

    private val recorded = listOf(
        Segment(true, 3266.0),
        Segment(false, 500.0),
        Segment(true, 200.0),
        Segment(false, 666.0),
        Segment(true, 200.0),
        Segment(false, 4865.0),
        Segment(true, 333.0),
        Segment(false, 300.0),
        Segment(true, 300.0),
        Segment(false, 267.0),
        Segment(true, 333.0),
        Segment(false, 300.0),
        Segment(true, 300.0),
        Segment(false, 900.0),
        Segment(true, 900.0),
        Segment(false, 300.0),
        Segment(true, 900.0),
        Segment(false, 300.0),
        Segment(true, 900.0),
        Segment(false, 866.0),
        Segment(true, 333.0),
        Segment(false, 300.0),
        Segment(true, 900.0),
        Segment(false, 267.0),
        Segment(true, 367.0),
        Segment(false, 233.0),
        Segment(true, 333.0),
        Segment(false, 866.0),
        Segment(true, 333.0),
        Segment(false, 300.0),
        Segment(true, 900.0),
        Segment(false, 7098.0),
        Segment(true, 67.0),
        Segment(false, 67.0),
        Segment(true, 33.0),
        Segment(false, 67.0),
        Segment(true, 67.0),
        Segment(false, 67.0),
        Segment(true, 67.0),
        Segment(false, 133.0),
        Segment(true, 33.0),
        Segment(false, 100.0),
        Segment(true, 33.0),
        Segment(false, 100.0),
        Segment(true, 33.0),
        Segment(false, 100.0),
        Segment(true, 33.0),
        Segment(false, 133.0),
        Segment(true, 33.0),
        Segment(false, 133.0),
        Segment(true, 33.0),
        Segment(false, 133.0),
        Segment(true, 33.0),
        Segment(false, 167.0),
        Segment(true, 33.0),
        Segment(false, 133.0),
        Segment(true, 67.0),
        Segment(false, 167.0),
        Segment(true, 33.0),
        Segment(false, 167.0),
        Segment(true, 33.0),
        Segment(false, 200.0),
        Segment(true, 67.0),
        Segment(false, 200.0),
        Segment(true, 33.0),
        Segment(false, 233.0),
        Segment(true, 67.0),
        Segment(false, 233.0),
        Segment(true, 33.0),
        Segment(false, 300.0),
        Segment(true, 67.0),
        Segment(false, 300.0),
        Segment(true, 67.0),
        Segment(false, 333.0),
        Segment(true, 67.0),
        Segment(false, 367.0),
        Segment(true, 167.0),
        Segment(false, 333.0)
    )

    @Test
    fun flickerAfterTheMessageIsNotTakenForDots() {
        val decoded = SignalDecoder.decode(recorded + Segment(false, 3000.0), hintUnitMs = 150.0)
        val text = decoded.text
        // The false light before the message still makes one stray letter;
        // the meter fix removes it at the source. HOLA itself must come through.
        assertTrue("got [$text]", text.contains("HOLA"))
    }

    /**
     * Same phone, held by hand, 14-oct-2026: turned slowly before the message,
     * which read as a 6 s "flash" without counting as motion, then "HOLA" at 10 %.
     * That one long flash once made the flicker filter take the real dots and
     * dashes for flicker and drop them all.
     */
    private val handheld = listOf(
        Segment(false, 433.0),
        Segment(true, 6265.0),
        Segment(false, 533.0),
        Segment(true, 300.0),
        Segment(false, 300.0),
        Segment(true, 367.0),
        Segment(false, 233.0),
        Segment(true, 300.0),
        Segment(false, 300.0),
        Segment(true, 333.0),
        Segment(false, 866.0),
        Segment(true, 900.0),
        Segment(false, 300.0),
        Segment(true, 900.0),
        Segment(false, 300.0),
        Segment(true, 933.0),
        Segment(false, 866.0),
        Segment(true, 300.0),
        Segment(false, 300.0),
        Segment(true, 900.0),
        Segment(false, 300.0),
        Segment(true, 300.0),
        Segment(false, 300.0),
        Segment(true, 300.0),
        Segment(false, 900.0),
        Segment(true, 367.0),
        Segment(false, 233.0),
        Segment(true, 933.0)
    )

    @Test
    fun aLongFalseFlashBeforeTheMessageIsIgnored() {
        val text = SignalDecoder.decode(handheld + Segment(false, 3000.0), hintUnitMs = 150.0).text
        assertTrue("got [$text]", text.trim().endsWith("HOLA"))
    }
}
