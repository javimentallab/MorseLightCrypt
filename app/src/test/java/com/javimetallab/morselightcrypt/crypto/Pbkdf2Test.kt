package com.javimetallab.morselightcrypt.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2Test {

    private fun reference(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, length * 8))
            .encoded

    @Test
    fun matchesTheJdk() {
        val passwords = listOf("a", "password", "x".repeat(64), "y".repeat(65), "long ".repeat(40))
        // The JDK refuses an empty salt, so none here; ours never gets one.
        val salts = listOf(byteArrayOf(1, 2, 3, 4), ByteArray(60) { it.toByte() })
        for (password in passwords) for (salt in salts) for (iterations in listOf(1, 2, 1000)) {
            for (length in listOf(16, 32, 64, 80)) {
                assertArrayEquals(
                    "$password / ${salt.size} / $iterations / $length",
                    reference(password, salt, iterations, length),
                    Pbkdf2.derive(password.toByteArray(), salt, iterations, length)
                )
            }
        }
    }

    /** RFC 7914 §11, the published PBKDF2-HMAC-SHA256 vector. */
    @Test
    fun rfcVector() {
        val out = Pbkdf2.derive("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            out.joinToString("") { "%02x".format(it) }
        )
    }

    /**
     * Same message, password and salt through tools/secret_codec.py (Python's
     * own PBKDF2 and AES), with the real 600 000 rounds. A computer and the app
     * must agree on every character, accents and Ñ included.
     */
    @Test
    fun sameCiphertextAsThePythonTool() {
        val vector = "AEBAG BFNAC BTEKM 7IOPY KNTKW UWA"
        val salt = byteArrayOf(1, 2, 3, 4)
        assertEquals(
            vector,
            SecretCodec.encryptWithSalt("HOLA, ÑU 42", "contraseña ñ€", salt, SecretCodec.DEFAULT_ITERATIONS)
        )
        // The same password typed with combining accents opens it too.
        val decomposed = Normalizer.normalize("contraseña ñ€", Normalizer.Form.NFD)
        assertEquals("HOLA, ÑU 42", SecretCodec.decrypt(vector, decomposed))
    }
}
