package com.javimetallab.morselightcrypt.morse

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A stretch of light ([on]) or darkness as the camera measured it. */
data class Segment(val on: Boolean, val ms: Double)

data class Decoded(
    /** Text of every finished letter. */
    val text: String,
    /** Morse of every finished letter, letters split by " " and words by "/ ". */
    val morse: String,
    /** Elements of the letter still coming in. */
    val pending: String,
    /** Estimated length of one unit, or null before any light was seen. */
    val unitMs: Double?,
    /** [unitMs] was measured from dots and dashes, not guessed from the hint. */
    val measured: Boolean = false
) {
    val wpm: Int? get() = unitMs?.let { (1200.0 / it).roundToInt() }

    companion object {
        val EMPTY = Decoded("", "", "", null)
    }
}

/**
 * Turns measured light and darkness into text.
 *
 * It re-decodes the whole recording each time, so that a later, better
 * estimate of the speed also fixes letters read earlier.
 *
 * Real lights are not square: a smart bulb fades in and a torch takes a few ms
 * to switch, so every pulse of light comes out longer (or shorter) by a fixed
 * amount and every gap shorter by the same amount. With both dots and dashes in
 * hand that offset can be measured, since dash − dot = 2 units whatever the
 * offset, and is then put back on the gaps before they are classified.
 */
object SignalDecoder {

    /**
     * @param segments recorded stretches, oldest first. The last one may still
     *   be running: its [Segment.ms] is the time elapsed so far.
     * @param hintUnitMs unit of the speed the sender is expected to use. Only
     *   needed while every pulse looks alike, when dots cannot be told from dashes.
     * @param glitchMs stretches shorter than this are noise and are absorbed.
     */
    fun decode(
        segments: List<Segment>,
        hintUnitMs: Double,
        glitchMs: Double = defaultGlitchMs(hintUnitMs)
    ): Decoded {
        var clean = filterGlitches(segments, glitchMs).dropWhile { !it.on }
        // Flicker: a run of flashes far shorter than the rest. Real dots are a
        // third of a dash and add up to plenty of time, so they never qualify.
        flickerLimit(clean.dropLast(1).filter { it.on }.map { it.ms })?.let { limit ->
            clean = filterGlitches(segments, maxOf(glitchMs, limit)).dropWhile { !it.on }
        }
        // A "flash" many times longer than the typical one is not Morse: the
        // scene brightened, say while the phone was turned slowly. It is
        // treated as darkness, so it cannot pass for a dash or skew the speed.
        clean = dropOverlong(clean).dropWhile { !it.on }
        if (clean.isEmpty()) return Decoded.EMPTY

        // The last stretch may be incomplete; it must not skew the estimate.
        val finished = clean.dropLast(1)
        val ons = finished.filter { it.on }.map { it.ms }
        val innerGaps = finished.filter { !it.on }.map { it.ms }
        val timing = estimate(ons, innerGaps, hintUnitMs)
            ?: estimate(clean.filter { it.on }.map { it.ms }, innerGaps, hintUnitMs)
            ?: return Decoded.EMPTY

        val text = StringBuilder()
        val morse = StringBuilder()
        val letter = StringBuilder()

        fun endLetter() {
            if (letter.isEmpty()) return
            text.append(MorseCode.decodeLetter(letter.toString()))
            morse.append(letter).append(' ')
            letter.clear()
        }

        for (segment in clean) {
            if (segment.on) {
                letter.append(if (segment.ms < timing.dashFromMs) '.' else '-')
                continue
            }
            val units = (segment.ms + timing.offsetMs) / timing.unitMs
            when {
                units < 2.0 -> Unit
                units < 5.0 -> endLetter()
                // Words are 7 units apart, repeats of a message 14: each repeat
                // gets a line, so they can be compared at a glance.
                units < 10.5 -> {
                    endLetter()
                    if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') {
                        text.append(' ')
                        morse.append("/ ")
                    }
                }
                else -> {
                    endLetter()
                    if (text.isNotEmpty() && text.last() != '\n') {
                        if (text.last() == ' ') text.setLength(text.length - 1)
                        text.append('\n')
                        morse.append("// ")
                    }
                }
            }
        }

        // A light still on may yet grow into a dash, so its letter stays open.
        return Decoded(
            text = text.toString(),
            morse = morse.toString().trimEnd(),
            pending = letter.toString(),
            unitMs = timing.unitMs,
            measured = timing.measured
        )
    }

    /** About a third of a dot, but never so much that a dot of one frame vanishes. */
    fun defaultGlitchMs(hintUnitMs: Double): Double = minOf(40.0, hintUnitMs * 0.3)

    private class Timing(
        val unitMs: Double,
        val offsetMs: Double,
        val dashFromMs: Double,
        val measured: Boolean = true
    )

    private fun estimate(ons: List<Double>, gaps: List<Double>, hintUnitMs: Double): Timing? {
        if (ons.isEmpty()) return null
        val shortest = ons.min()
        val longest = ons.max()

        if (longest / shortest >= 1.8) {
            // Two clusters, dots and dashes: 2-means on a log scale.
            var split = sqrt(shortest * longest)
            var dots = ons
            var dashes = ons
            repeat(10) {
                dots = ons.filter { it < split }
                dashes = ons.filter { it >= split }
                if (dots.isEmpty() || dashes.isEmpty()) return@repeat
                split = sqrt(dots.average() * dashes.average())
            }
            if (dots.isNotEmpty() && dashes.isNotEmpty()) {
                // Robust middles: neither one stray reading nor a swarm of
                // short flickers may drag the speed along.
                val dot = dots.weightedMedian()
                val dash = dashes.weightedMedian()
                val unit = (dash - dot) / 2
                val offset = dot - unit
                // An offset of more than a unit means the estimate is noise.
                return if (unit > 0 && offset in -0.5 * unit..unit) {
                    Timing(unit, offset, split)
                } else {
                    val plain = (dots.sum() + dashes.sum() / 3) / ons.size
                    Timing(plain, 0.0, split)
                }
            }
        }

        // One cluster: all dots or all dashes. A gap inside a letter lasts one
        // unit, so a gap well below the pulse length means these are dashes.
        val mean = ons.average()
        val allDashes = when {
            gaps.isNotEmpty() && gaps.min() < mean / 2 -> true
            else -> mean >= 2 * hintUnitMs
        }
        val unit = if (allDashes) mean / 3 else mean
        return Timing(unit, 0.0, 2 * unit, measured = false)
    }

    /**
     * Duration under which flashes are flicker, or null if there is none.
     *
     * Sorted, flicker shows as a jump: a group of short flashes, then the real
     * ones at least twice as long. The group counts as flicker only if its
     * longest is under a quarter of the typical real flash and it adds up to
     * under a quarter of their time. Dots fail both: a dot is a third of a dash,
     * and a message's dots add up to a good share of its light.
     */
    private fun flickerLimit(ons: List<Double>): Double? {
        val sorted = ons.sorted()
        for (i in 1 until sorted.size) {
            if (sorted[i] < sorted[i - 1] * 2) continue
            val below = sorted.subList(0, i)
            val above = sorted.subList(i, sorted.size)
            if (above.size < 3) return null
            val typical = above[above.size / 2]
            if (below.last() * 4 < typical && below.sum() < above.sum() / 4) {
                return (below.last() + sorted[i]) / 2
            }
        }
        return null
    }

    /** Longer than this many typical flashes, a flash is not Morse. A dash is 3. */
    private const val OVERLONG = 6.0

    /**
     * Turns flashes over [OVERLONG] times the typical one into darkness. Typical
     * here is the upper quartile by count: one false flash of several seconds
     * can weigh as much time as a whole word, but it is still just one flash,
     * and leftover flicker sits in the lower half where it cannot pull it down.
     */
    private fun dropOverlong(segments: List<Segment>): List<Segment> {
        val ons = segments.dropLast(1).filter { it.on }.map { it.ms }.sorted()
        if (ons.size < 3) return segments
        val limit = ons[ons.size * 3 / 4] * OVERLONG
        if (segments.none { it.on && it.ms > limit }) return segments
        val out = ArrayList<Segment>(segments.size)
        for (segment in segments) {
            val s = if (segment.on && segment.ms > limit) segment.copy(on = false) else segment
            val last = out.lastOrNull()
            if (last != null && last.on == s.on) out[out.lastIndex] = last.copy(ms = last.ms + s.ms) else out += s
        }
        return out
    }

    /**
     * The duration that splits the total time in half. Each pulse counts by how
     * long it lasts, so a swarm of 50 ms flickers weighs little next to a few
     * real dots, and a single stray long reading cannot move it far either.
     */
    private fun List<Double>.weightedMedian(): Double {
        val sorted = sorted()
        val half = sorted.sum() / 2
        var running = 0.0
        for (value in sorted) {
            running += value
            if (running >= half) return value
        }
        return sorted.last()
    }

    /** Folds stretches shorter than [glitchMs] into their neighbours. */
    internal fun filterGlitches(segments: List<Segment>, glitchMs: Double): List<Segment> {
        val out = ArrayList<Segment>(segments.size)
        for ((index, segment) in segments.withIndex()) {
            val isLast = index == segments.lastIndex
            val last = out.lastOrNull()
            when {
                last == null -> out += segment
                last.on == segment.on -> out[out.lastIndex] = last.copy(ms = last.ms + segment.ms)
                // A short stretch that is still running may be the start of a
                // real one: leave it out for now instead of merging it.
                segment.ms < glitchMs && isLast -> Unit
                segment.ms < glitchMs -> out[out.lastIndex] = last.copy(ms = last.ms + segment.ms)
                else -> out += segment
            }
        }
        return out
    }
}
