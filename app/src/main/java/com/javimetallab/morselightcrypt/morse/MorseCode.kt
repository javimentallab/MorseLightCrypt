package com.javimetallab.morselightcrypt.morse

import java.text.Normalizer

/** The ITU Morse alphabet, plus Ñ, which Spanish operators use. */
object MorseCode {

    private val table: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'Ñ' to "--.--",
        'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...",
        'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.",
        '!' to "-.-.--", '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-",
        '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '"' to ".-..-.",
        '$' to "...-..-", '@' to ".--.-."
    )

    /** SOS is sent as one run of nine elements, not as three letters. */
    private val reverse: Map<String, String> =
        table.entries.associate { (char, code) -> code to char.toString() } +
            ("...---..." to "SOS")

    /** Shown for a received sequence that is not in the alphabet. */
    const val UNKNOWN = '*'

    /**
     * Upper-cases [text], strips accents (Á → A) except from Ñ, turns any run of
     * whitespace into one space and drops what Morse cannot carry, such as ¿ or
     * emoji. Indexes into the result are what the sender reports as progress.
     */
    fun normalize(text: String): String {
        val out = StringBuilder()
        for (raw in text.uppercase()) {
            val char = when {
                raw == 'Ñ' -> raw
                raw.isWhitespace() -> ' '
                else -> Normalizer.normalize(raw.toString(), Normalizer.Form.NFD)
                    .firstOrNull() ?: continue
            }
            if (char == ' ') {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            } else if (char in table) {
                out.append(char)
            }
        }
        return out.toString().trimEnd()
    }

    fun codeOf(char: Char): String? = table[char]

    fun decodeLetter(code: String): String = reverse[code] ?: UNKNOWN.toString()

    /** Letters separated by spaces and words by " / ", e.g. "... --- ... / .". */
    fun toMorse(text: String): String =
        normalize(text).split(' ').filter { it.isNotEmpty() }.joinToString(" / ") { word ->
            word.mapNotNull { table[it] }.joinToString(" ")
        }
}
