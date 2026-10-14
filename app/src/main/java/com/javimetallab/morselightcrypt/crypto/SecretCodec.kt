package com.javimetallab.morselightcrypt.crypto

import com.javimetallab.morselightcrypt.morse.MorseCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a message with a master password into text that Morse can carry.
 *
 * Every character sent by light costs seconds, so the format is as short as
 * it can be while staying sound for personal use:
 *
 *     salt (4 bytes) ‖ AES-256-CTR ciphertext ‖ HMAC-SHA256 tag, cut to 4 bytes
 *
 * written in base 32 using letters and digits only, in groups of five. The
 * fixed cost is 13 characters on top of the message.
 *
 * - The keys are derived from password and salt with PBKDF2-HMAC-SHA256 ([Pbkdf2]), so a
 *   fresh random salt gives fresh keys for every message. That is what lets the
 *   counter start at zero. Two messages under one password only share a
 *   keystream if their salts collide, which becomes likely after ~65 000
 *   messages, far beyond what anyone flashes by hand.
 * - The plaintext is first packed at 6 bits per character, since a message is
 *   limited to the Morse alphabet anyway, which saves a quarter of the length.
 * - A 32-bit tag stops a forged message with odds of one in four billion per
 *   attempt, and each attempt has to be flashed. Its everyday job is to catch a
 *   letter misread by the camera, or the wrong password.
 *
 * 🛑 DOMAIN and the layout are frozen once messages exist: changing either
 * makes every message sent before undecryptable.
 */
object SecretCodec {

    private const val DOMAIN = "morselightcrypt/v1"
    private const val SALT_BYTES = 4
    private const val TAG_BYTES = 4
    const val DEFAULT_ITERATIONS = 600_000

    /** Base 32 digits. Letters first; Morse has no lowercase, so no ambiguity. */
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 6-bit plaintext alphabet. Index 0 is padding and never a character. */
    private const val PACKED = "\u0000 ABCDEFGHIJKLMNÑOPQRSTUVWXYZ0123456789.,?'!/()&:;=+-_\"$@"

    class DecryptException(message: String) : Exception(message)

    fun encrypt(
        message: String,
        password: String,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom()
    ): String {
        val normalized = MorseCode.normalize(message)
        require(normalized.isNotEmpty()) { "empty message" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return encryptWithSalt(normalized, password, salt, iterations)
    }

    /** [encrypt] with a chosen salt, for test vectors shared with other implementations. */
    internal fun encryptWithSalt(normalized: String, password: String, salt: ByteArray, iterations: Int): String {
        val keys = deriveKeys(password, salt, iterations)
        val ciphertext = ctr(keys.enc, pack(normalized))
        val tag = tag(keys.mac, salt, ciphertext)
        return group(toBase32(salt + ciphertext + tag))
    }

    /** @throws DecryptException if the text is damaged or the password is wrong. */
    fun decrypt(received: String, password: String, iterations: Int = DEFAULT_ITERATIONS): String =
        decryptDigits(digitsOf(received), password, iterations, HashMap())

    /** One received line and what it decrypted to, or null if it would not open. */
    data class Line(val received: String, val text: String?)

    /**
     * @param lines every non-blank received line, in order, opened or not.
     * @param majority only when some line failed: a letter-by-letter majority
     *   vote over the lines of the most common length, if that opens. A guess
     *   that the lines are repeats of one message, so it never replaces them.
     */
    data class Result(val lines: List<Line>, val majority: String?) {
        val anyOpened: Boolean get() = majority != null || lines.any { it.text != null }
    }

    /**
     * Decrypts every received line on its own: a sender looping one message
     * puts one repeat per line, but nothing says two lines are the same
     * message, so nothing is merged or dropped. Lines that will not open are
     * kept and reported as such.
     *
     * Lines with the same salt share the slow key stretching.
     */
    fun decryptLines(received: String, password: String, iterations: Int = DEFAULT_ITERATIONS): Result {
        val keyCache = HashMap<String, Keys>()
        fun open(digits: String): String? = try {
            decryptDigits(digits, password, iterations, keyCache)
        } catch (_: DecryptException) {
            null
        }

        val lines = received.lines().filter { it.isNotBlank() }.map { Line(it.trim(), open(digitsOf(it))) }
        val majority = if (lines.all { it.text != null }) null
        else majority(lines.map { digitsOf(it.received) })?.let(::open)
        return Result(lines, majority)
    }

    private fun digitsOf(text: String): String =
        text.uppercase().filter { !it.isWhitespace() && it != '/' }

    /** Per position, the digit most repeats agree on; null with under 3 alike. */
    internal fun majority(lines: List<String>): String? {
        val length = lines.groupingBy { it.length }.eachCount().maxByOrNull { it.value }
            ?.takeIf { it.value >= 3 }?.key ?: return null
        val same = lines.filter { it.length == length }
        return String(CharArray(length) { i ->
            same.groupingBy { it[i] }.eachCount().maxBy { it.value }.key
        })
    }

    private fun decryptDigits(
        digits: String,
        password: String,
        iterations: Int,
        keyCache: MutableMap<String, Keys>
    ): String {
        val bytes = fromBase32(digits) ?: throw DecryptException("not base 32")
        if (bytes.size <= SALT_BYTES + TAG_BYTES) throw DecryptException("too short")

        val salt = bytes.copyOfRange(0, SALT_BYTES)
        val ciphertext = bytes.copyOfRange(SALT_BYTES, bytes.size - TAG_BYTES)
        val tag = bytes.copyOfRange(bytes.size - TAG_BYTES, bytes.size)

        val keys = keyCache.getOrPut(salt.joinToString(",")) { deriveKeys(password, salt, iterations) }
        if (!MessageDigest.isEqual(tag, tag(keys.mac, salt, ciphertext))) {
            throw DecryptException("tag mismatch")
        }
        return unpack(ctr(keys.enc, ciphertext)) ?: throw DecryptException("bad plaintext")
    }

    /** Characters a message of [message]'s length becomes, groups not counted. */
    fun encryptedLength(message: String): Int {
        val chars = MorseCode.normalize(message).length
        val bytes = SALT_BYTES + (chars * 6 + 7) / 8 + TAG_BYTES
        return (bytes * 8 + 4) / 5
    }

    private class Keys(val enc: ByteArray, val mac: ByteArray)

    private fun deriveKeys(password: String, salt: ByteArray, iterations: Int): Keys {
        val out = Pbkdf2.derive(passwordBytes(password), DOMAIN.toByteArray() + salt, iterations, 64)
        return Keys(out.copyOfRange(0, 32), out.copyOfRange(32, 64))
    }

    /**
     * NFC first: "ñ" can be typed as one character or as n + combining tilde,
     * depending on the keyboard, and both must give the same key. Then UTF-8,
     * spelled out so that every implementation agrees.
     */
    internal fun passwordBytes(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    private fun ctr(key: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(input)
        }

    private fun tag(key: ByteArray, salt: ByteArray, ciphertext: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(salt)
            doFinal(ciphertext).copyOf(TAG_BYTES)
        }

    internal fun pack(text: String): ByteArray {
        val out = ByteArray((text.length * 6 + 7) / 8)
        var bit = 0
        for (char in text) {
            val value = PACKED.indexOf(char)
            require(value > 0) { "not packable: $char" }
            for (i in 5 downTo 0) {
                if ((value shr i) and 1 == 1) {
                    out[bit / 8] = (out[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
                }
                bit++
            }
        }
        return out
    }

    /** Null if a value falls outside the alphabet, which a good tag rules out. */
    internal fun unpack(bytes: ByteArray): String? {
        val out = StringBuilder()
        val totalBits = bytes.size * 8
        var bit = 0
        while (bit + 6 <= totalBits) {
            var value = 0
            repeat(6) {
                val set = (bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1
                value = (value shl 1) or set
                bit++
            }
            if (value == 0) continue
            if (value >= PACKED.length) return null
            out.append(PACKED[value])
        }
        return out.toString()
    }

    internal fun toBase32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    internal fun fromBase32(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in text) {
            val value = BASE32.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private fun group(text: String): String = text.chunked(5).joinToString(" ")
}
