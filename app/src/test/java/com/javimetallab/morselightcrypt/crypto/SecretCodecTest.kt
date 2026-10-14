package com.javimetallab.morselightcrypt.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretCodecTest {

    // Real messages use DEFAULT_ITERATIONS; the format is the same either way.
    private val fast = 1000

    @Test
    fun roundTrip() {
        val cipher = SecretCodec.encrypt("Hola, ¿qué tal? Ñu 42", "clave", fast)
        assertEquals("HOLA, QUE TAL? ÑU 42", SecretCodec.decrypt(cipher, "clave", fast))
    }

    @Test
    fun outputIsShortGroupedAndMorseFriendly() {
        val cipher = SecretCodec.encrypt("HOLA MUNDO", "clave", fast)
        val digits = cipher.replace(" ", "")
        assertEquals(26, digits.length)
        assertEquals(SecretCodec.encryptedLength("HOLA MUNDO"), digits.length)
        assertTrue(cipher.split(' ').dropLast(1).all { it.length == 5 })
        assertTrue(digits.all { it in 'A'..'Z' || it in '2'..'7' })
    }

    @Test
    fun freshSaltEveryTime() {
        assertNotEquals(
            SecretCodec.encrypt("HOLA", "clave", fast),
            SecretCodec.encrypt("HOLA", "clave", fast)
        )
    }

    @Test(expected = SecretCodec.DecryptException::class)
    fun wrongPasswordFails() {
        val cipher = SecretCodec.encrypt("HOLA", "clave", fast)
        SecretCodec.decrypt(cipher, "otra", fast)
    }

    @Test
    fun oneMisreadLetterFails() {
        val cipher = SecretCodec.encrypt("ATACAR AL AMANECER", "clave", fast)
        // The last digit ends in padding bits, so only its first bits count:
        // E and T may share them. Every other digit carries 5 real bits.
        for (i in 0 until cipher.lastIndex) {
            if (cipher[i] == ' ') continue
            val wrong = if (cipher[i] == 'E') 'T' else 'E'
            val damaged = cipher.substring(0, i) + wrong + cipher.substring(i + 1)
            val failed = runCatching { SecretCodec.decrypt(damaged, "clave", fast) }.isFailure
            assertTrue("letter $i", failed)
        }
    }

    @Test
    fun spacesAndSlashesFromTheReceiverAreIgnored() {
        val cipher = SecretCodec.encrypt("OK", "clave", fast)
        val asReceived = cipher.replace(" ", " / ") + " "
        assertEquals("OK", SecretCodec.decrypt(asReceived, "clave", fast))
    }

    @Test
    fun base32RoundTrip() {
        for (size in 0..20) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            val back = SecretCodec.fromBase32(SecretCodec.toBase32(bytes))!!
            assertEquals(bytes.toList(), back.toList())
        }
    }

    @Test
    fun packRoundTrip() {
        for (text in listOf("A", "AB", "ABC", "ABCD", "HOLA MUNDO", "Ñ0.,?'!/()&:;=+-_\"$@")) {
            assertEquals(text, SecretCodec.unpack(SecretCodec.pack(text)))
        }
    }

    private fun misread(cipher: String, at: Int): String {
        val wrong = if (cipher[at] == 'E') 'T' else 'E'
        return cipher.substring(0, at) + wrong + cipher.substring(at + 1)
    }

    /** Two equal lines are two results: nothing says they are one message. */
    @Test
    fun everyLineIsDecryptedAndKept() {
        val cipher = SecretCodec.encrypt("HOLA", "clave", fast)
        val result = SecretCodec.decryptLines("E\n" + cipher + "\n" + cipher + "\n", "clave", fast)
        assertEquals(listOf(null, "HOLA", "HOLA"), result.lines.map { it.text })
        assertEquals("E", result.lines[0].received)
        assertEquals(null, result.majority)
        assertTrue(result.anyOpened)
    }

    /** Different messages on different lines each open on their own. */
    @Test
    fun differentMessagesOnDifferentLines() {
        val first = SecretCodec.encrypt("NOS VEMOS", "clave", fast)
        val second = SecretCodec.encrypt("A LAS 9", "clave", fast)
        val result = SecretCodec.decryptLines(first + "\n" + misread(second, 2) + "\n" + second, "clave", fast)
        assertEquals(listOf("NOS VEMOS", null, "A LAS 9"), result.lines.map { it.text })
    }

    /**
     * Three repeats, each with a different letter wrong: none opens, and the
     * vote is offered as an extra, labelled guess.
     */
    @Test
    fun everyLineDamagedOffersAMajorityGuess() {
        val cipher = SecretCodec.encrypt("NOS VEMOS A LAS 9", "clave", fast)
        val received = listOf(misread(cipher, 1), misread(cipher, 8), misread(cipher, 14)).joinToString("\n")
        val result = SecretCodec.decryptLines(received, "clave", fast)
        assertEquals(listOf(null, null, null), result.lines.map { it.text })
        assertEquals("NOS VEMOS A LAS 9", result.majority)
    }

    @Test
    fun wrongPasswordOpensNothing() {
        val cipher = SecretCodec.encrypt("HOLA", "clave", fast)
        val result = SecretCodec.decryptLines(cipher + "\n" + cipher + "\n" + cipher, "otra", fast)
        assertEquals(false, result.anyOpened)
    }
}
