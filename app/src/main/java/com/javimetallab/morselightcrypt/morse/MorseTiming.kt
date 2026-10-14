package com.javimetallab.morselightcrypt.morse

/**
 * One stretch of light or darkness, measured in Morse units.
 *
 * [charIndex] points into [MorseCode.normalize] of the message, so the screen
 * can show which letter is going out.
 */
data class Pulse(val on: Boolean, val units: Int, val charIndex: Int)

object MorseTiming {

    const val DOT = 1
    const val DASH = 3
    const val ELEMENT_GAP = 1
    const val LETTER_GAP = 3
    const val WORD_GAP = 7
    /** Between repeats of a message: long enough for the receiver to start a new line. */
    const val MESSAGE_GAP = 14

    /** Length of one unit in ms, by the PARIS standard (50 units per word). */
    fun unitMs(wpm: Int): Long = 1200L / wpm.coerceAtLeast(1)

    /**
     * The light pattern for [text]: alternating on and off pulses that start
     * and end with light, so no trailing darkness is wasted.
     */
    fun schedule(text: String): List<Pulse> {
        val normalized = MorseCode.normalize(text)
        val pulses = mutableListOf<Pulse>()
        var pendingGap = 0

        normalized.forEachIndexed { index, char ->
            if (char == ' ') {
                pendingGap = WORD_GAP
                return@forEachIndexed
            }
            val code = MorseCode.codeOf(char) ?: return@forEachIndexed
            if (pulses.isNotEmpty()) {
                pulses += Pulse(on = false, units = maxOf(pendingGap, LETTER_GAP), charIndex = index)
            }
            code.forEachIndexed { i, symbol ->
                if (i > 0) pulses += Pulse(on = false, units = ELEMENT_GAP, charIndex = index)
                pulses += Pulse(on = true, units = if (symbol == '.') DOT else DASH, charIndex = index)
            }
            pendingGap = 0
        }
        return pulses
    }
}
