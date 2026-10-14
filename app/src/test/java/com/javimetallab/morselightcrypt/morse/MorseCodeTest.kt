package com.javimetallab.morselightcrypt.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseCodeTest {

    @Test
    fun normalizeUppercasesStripsAccentsAndKeepsEnye() {
        assertEquals("HOLA, ÑANDU PINGUINO!", MorseCode.normalize("¡Hola,   ñandú pingüino!"))
    }

    @Test
    fun normalizeDropsWhatMorseCannotCarry() {
        assertEquals("OK", MorseCode.normalize("  ¿OK 🙂  "))
    }

    @Test
    fun toMorseSplitsLettersAndWords() {
        assertEquals("... --- ... / . -", MorseCode.toMorse("sos et"))
    }

    @Test
    fun decodeLetterKnowsSosAndMarksUnknown() {
        assertEquals("SOS", MorseCode.decodeLetter("...---..."))
        assertEquals("Ñ", MorseCode.decodeLetter("--.--"))
        assertEquals(MorseCode.UNKNOWN.toString(), MorseCode.decodeLetter("........"))
    }

    @Test
    fun parisIsFiftyUnitsWithItsWordGap() {
        val units = MorseTiming.schedule("PARIS").sumOf { it.units } + MorseTiming.WORD_GAP
        assertEquals(50, units)
    }

    @Test
    fun scheduleAlternatesAndStartsAndEndsWithLight() {
        val pulses = MorseTiming.schedule("E E")
        assertEquals(
            listOf(Pulse(true, 1, 0), Pulse(false, 7, 2), Pulse(true, 1, 2)),
            pulses
        )
        val long = MorseTiming.schedule("HELLO WORLD")
        assertTrue(long.first().on && long.last().on)
        assertTrue(long.zipWithNext().all { (a, b) -> a.on != b.on })
    }
}
