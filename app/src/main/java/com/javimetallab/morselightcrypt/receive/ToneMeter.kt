package com.javimetallab.morselightcrypt.receive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Finds a Morse tone in microphone audio and says how loud it is, a few
 * milliseconds at a time.
 *
 * The sender's pitch is not known (radios and apps use anything from 400 to
 * 1000 Hz), so a bank of Goertzel filters listens across [MIN_HZ]..[MAX_HZ].
 * The filter whose peaks have been loudest lately is followed, as long as they
 * stand out from the rest of the spectrum; only its level is handed on, so
 * noise at other pitches (voices, a fan) mostly stays out.
 *
 * Levels come out on the 0..255 scale the light meter uses, at
 * [LEVEL_PER_DB] per decibel, so the same [LightTracker] and decoder work on
 * them unchanged.
 */
class ToneMeter(private val sampleRate: Int) {

    data class Reading(
        /** Loudness of the followed tone, 0..255. */
        val level: Float,
        /** Pitch being followed, or null before anything stood out. */
        val toneHz: Int?
    )

    /** Samples per analysis window: 20 ms, a fraction of a dot even at 20 wpm. */
    val windowSize = sampleRate / 50
    /** A reading every 10 ms; windows overlap by half. */
    val hopSize = sampleRate / 100

    private val frequencies = (MIN_HZ..MAX_HZ step STEP_HZ).toList()
    private val coefficients = DoubleArray(frequencies.size) { 2 * cos(2 * PI * frequencies[it] / sampleRate) }
    /** Hann window: keeps a loud tone from leaking into the filters next to it. */
    private val hann = FloatArray(windowSize) { (0.5 - 0.5 * cos(2 * PI * it / (windowSize - 1))).toFloat() }
    /** Power of a full-scale sine through the window, for dBFS. */
    private val fullScale: Double = run {
        val sum = hann.sumOf { it.toDouble() }
        (sum / 2) * (sum / 2)
    }

    /** Last [windowSize] samples, circular; [window] is them in order, rebuilt per reading. */
    private val ring = FloatArray(windowSize)
    private var head = 0
    private val window = FloatArray(windowSize)
    private var filled = 0
    private var sinceReading = 0

    /** Recent peak of each filter in dB, slowly forgotten. */
    private val held = DoubleArray(frequencies.size) { FLOOR_DB }
    private var followed = -1

    /**
     * Feeds samples in -1..1; returns one reading per [hopSize] samples that
     * completed a window.
     */
    fun feed(samples: FloatArray, count: Int = samples.size): List<Reading> {
        val out = ArrayList<Reading>(count / hopSize + 1)
        for (i in 0 until count) {
            ring[head] = samples[i]
            head = (head + 1) % windowSize
            if (filled < windowSize) filled++
            if (filled == windowSize && ++sinceReading >= hopSize) {
                sinceReading = 0
                // Oldest sample first: it sits where the next one will be written.
                System.arraycopy(ring, head, window, 0, windowSize - head)
                System.arraycopy(ring, 0, window, windowSize - head, head)
                out += measure()
            }
        }
        return out
    }

    fun reset() {
        head = 0
        filled = 0
        sinceReading = 0
        held.fill(FLOOR_DB)
        followed = -1
    }

    private fun measure(): Reading {
        val db = DoubleArray(frequencies.size) { powerDb(it) }
        for (i in db.indices) {
            held[i] = if (db[i] > held[i]) db[i] else held[i] - HOLD_DECAY_DB
        }
        // Plain noise is flat: a tone is a peak that stands well above the rest.
        val heldFloor = median(held)
        if (followed >= 0 && held[followed] - heldFloor < STAND_OUT_DB) followed = -1
        val loudest = held.indices.maxBy { held[it] }
        // Switch pitch only for a clearly louder one, so two near-equal filters
        // do not take turns and chop the tone.
        if (held[loudest] - heldFloor >= STAND_OUT_DB &&
            (followed < 0 || held[loudest] > held[followed] + SWITCH_MARGIN_DB)
        ) {
            followed = loudest
        }
        if (followed < 0) return Reading(0f, null)
        // The tone may sit between two filters: take the best of the neighbours.
        val level = (followed - 1..followed + 1)
            .filter { it in db.indices }
            .maxOf { db[it] }
        // Between beeps the filter only hears noise, which in one filter jumps
        // by 10-20 dB from window to window: that would read as flicker. Unless
        // the tone is clearly above the noise right now, it is silence.
        // A beep is one narrow peak; a click, a knock or a hiss is wide. Well
        // outside the window's main lobe a beep is far quieter than at its pitch.
        val sides = listOf(followed - SIDE_STEPS, followed + SIDE_STEPS).filter { it in db.indices }
        val narrow = sides.all { level - db[it] >= NARROW_DB }
        val sounding = narrow && level - median(db) >= GATE_DB
        return Reading(if (sounding) toLevel(level) else 0f, frequencies[followed])
    }

    private fun median(values: DoubleArray): Double = values.sortedArray()[values.size / 2]

    /** Goertzel power of filter [k] over the current window, in dBFS. */
    private fun powerDb(k: Int): Double {
        val c = coefficients[k]
        var s1 = 0.0
        var s2 = 0.0
        for (n in 0 until windowSize) {
            val s = window[n] * hann[n] + c * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - c * s1 * s2
        return 10 * log10(power / fullScale + 1e-12)
    }

    private fun toLevel(db: Double): Float =
        ((db - FLOOR_DB) * LEVEL_PER_DB).coerceIn(0.0, 255.0).toFloat()

    companion object {
        const val MIN_HZ = 300
        const val MAX_HZ = 1500
        const val STEP_HZ = 20
        /** Quieter than this counts as silence: 0 on the level scale. */
        const val FLOOR_DB = -90.0
        const val LEVEL_PER_DB = 255.0 / 90.0
        /** Peaks fade 10 dB a second (a reading every 10 ms); a new beep is followed at once anyway. */
        const val HOLD_DECAY_DB = 0.1
        const val SWITCH_MARGIN_DB = 6.0
        /** A pitch is followed only while its recent peak stands this far above the others. */
        const val STAND_OUT_DB = 15.0
        /** Right now the tone must be this far above the noise to count as sounding. */
        const val GATE_DB = 10.0
        /** Filters this far from the pitch (140 Hz, past the 20 ms window's main lobe)... */
        const val SIDE_STEPS = 7
        /** ...must be this much quieter for the sound to count as a beep. */
        const val NARROW_DB = 15.0

        /** The level a contrast of [db] decibels makes, for the tracker's threshold. */
        fun levelOf(db: Double): Float = (db * LEVEL_PER_DB).roundToInt().toFloat()

        /**
         * On/off for the readings: a reading every 10 ms, so the edges drift
         * per reading three times slower than per camera frame; a beep must
         * stand 10 dB above the quiet. Judged from the top (6 dB below the
         * beep's level to turn on, 9 dB to turn off), because a room's echo
         * keeps the beep ringing well above the quiet after it stopped.
         */
        fun tracker() = LightTracker(
            minContrast = levelOf(10.0),
            drift = 0.003f,
            onDrop = levelOf(6.0),
            offDrop = levelOf(9.0)
        )
    }
}
