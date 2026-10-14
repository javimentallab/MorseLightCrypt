package com.javimetallab.morselightcrypt.receive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LightMeterTest {

    /**
     * Half the square is a saturated monitor, the rest dark noise, and a dim
     * lamp covers 2 % of it, going from 40 to 70 when lit. Measuring the
     * brightest pixels would read 255 every frame; the meter must follow the lamp.
     */
    @Test
    fun followsADimLampNextToABrightScreen() {
        val n = 3600
        val lamp = 0 until 72
        val screen = 1800 until n
        val random = Random(5)
        val meter = LightMeter()

        fun frame(lit: Boolean) = IntArray(n) { i ->
            when (i) {
                in lamp -> (if (lit) 70 else 40) + random.nextInt(-3, 4)
                in screen -> 255
                else -> 20 + random.nextInt(-4, 5)
            }
        }

        // Warm up with a few blinks so the lamp's pixels stand out.
        repeat(6) {
            repeat(5) { meter.update(frame(true)) }
            repeat(5) { meter.update(frame(false)) }
        }
        val on = (1..5).map { meter.update(frame(true)).level }.average()
        val off = (1..5).map { meter.update(frame(false)).level }.average()
        assertTrue("on=$on off=$off", on > 60 && off < 50 && on - off > 20)
    }

    @Test
    fun steadySceneGivesASteadyLevel() {
        val random = Random(9)
        val meter = LightMeter()
        val levels = (1..200).map {
            meter.update(IntArray(2000) { i -> if (i < 500) 255 else 30 + random.nextInt(-4, 5) }).level
        }.drop(30)
        assertTrue("spread ${levels.max() - levels.min()}", levels.max() - levels.min() < 20)
    }

    /**
     * A bright lamp lights the room: 60 % of the blocks brighten a lot, and
     * the camera's tone mapping darkens 12 % a little. Lopsided: not motion,
     * so the first flashes of a message are not thrown away.
     */
    @Test
    fun roomLightingUpWithToneMappingIsNotMotion() {
        val meter = LightMeter()
        val dark = IntArray(1000) { 60 }
        val lit = IntArray(1000) { i ->
            when {
                i < 600 -> 200
                i < 720 -> 20
                else -> 60
            }
        }
        repeat(5) {
            assertTrue(meter.update(dark).steady)
            assertTrue(meter.update(lit).steady)
        }
    }

    /** A lamp lighting the whole square brightens every pixel at once: not motion. */
    @Test
    fun aLampLightingTheRoomIsNotMotion() {
        val meter = LightMeter()
        val dark = IntArray(2000) { 20 + it % 7 }
        val lit = IntArray(2000) { 150 + it % 7 }
        repeat(10) {
            assertTrue(meter.update(dark).steady)
            assertTrue(meter.update(lit).steady)
        }
    }

    /**
     * Stripes sliding sideways, as edges do when the phone turns: some pixels
     * brighten while others darken. Unsteady, and only steady again after the
     * picture has been still for a while.
     */
    @Test
    fun slidingEdgesAreMotion() {
        val meter = LightMeter()
        fun stripes(shift: Int) = IntArray(2000) { i -> if ((i + shift) / 10 % 2 == 0) 200 else 30 }

        repeat(5) { assertTrue(meter.update(stripes(0)).steady) }
        assertFalse(meter.update(stripes(5)).steady)
        assertFalse(meter.update(stripes(10)).steady)
        val afterwards = (1..30).map { meter.update(stripes(10)).steady }
        assertFalse(afterwards.first())
        assertTrue(afterwards.last())
    }

    /**
     * A hand tremor: a few edge blocks flicker both ways (4 % each frame) while
     * a lamp blinks. Tolerated: the reading stays steady, flagged shaky, and
     * still follows the lamp.
     */
    @Test
    fun aShakyHandIsToleratedAndTheLampStillRead() {
        val n = 900
        val lamp = 0 until 6
        val edges = 100 until 172
        val meter = LightMeter()
        var frame = 0
        fun blocks(lit: Boolean) = IntArray(n) { i ->
            when (i) {
                in lamp -> if (lit) 220 else 30
                // Half the edge blocks brighten while the other half darken, alternating.
                in edges -> if ((i + frame) % 2 == 0) 120 else 60
                else -> 40
            }
        }.also { frame++ }

        val readings = (1..20).flatMap { listOf(true, true, true, false, false, false) }
            .map { lit -> lit to meter.update(blocks(lit)) }
            .drop(12)
        assertTrue(readings.all { it.second.steady })
        assertTrue(readings.all { it.second.shaky })
        val on = readings.filter { it.first }.map { it.second.level }.average()
        val off = readings.filter { !it.first }.map { it.second.level }.average()
        assertTrue("on=$on off=$off", on - off > 100)
    }
}
