package com.javimetallab.morselightcrypt.crypto

import java.security.MessageDigest

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018), written for speed.
 *
 * Android's own "PBKDF2WithHmacSHA256" runs in pure Java through BouncyCastle
 * and takes several seconds for 600 000 rounds on a phone. This one computes
 * the HMAC key states once and then spends each round on exactly two SHA-256
 * compressions over fixed blocks, with no allocation in the loop.
 *
 * The output is the standard one, bit for bit; the tests check it against the
 * JDK's implementation.
 */
internal object Pbkdf2 {

    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ).let { k -> IntArray(k.size) { k[it].toInt() } }

    private val IV = longArrayOf(
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ).let { v -> IntArray(v.size) { v[it].toInt() } }

    /** Bits hashed by the time a round's 32-byte message ends: key block + message. */
    private const val ROUND_BITS = (64 + 32) * 8

    fun derive(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1)
        val key = if (password.size > 64) MessageDigest.getInstance("SHA-256").digest(password) else password
        val w = IntArray(64)

        // HMAC key states: SHA-256 after absorbing key^ipad and key^opad.
        val padded = key.copyOf(64)
        val inner = IntArray(8)
        val outer = IntArray(8)
        compress(IV, words(ByteArray(64) { (padded[it].toInt() xor 0x36).toByte() }), inner, w)
        compress(IV, words(ByteArray(64) { (padded[it].toInt() xor 0x5c).toByte() }), outer, w)

        val out = ByteArray(length)
        val block = IntArray(16)
        val u = IntArray(8)
        val t = IntArray(8)
        val tmp = IntArray(8)
        var blockIndex = 1
        var written = 0
        while (written < length) {
            // U1 = HMAC(salt || INT(i)), of any length: the general path.
            val first = salt + byteArrayOf(
                (blockIndex ushr 24).toByte(), (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(), blockIndex.toByte()
            )
            hashAfterKeyBlock(inner, first, tmp, w)
            hashAfterKeyBlock(outer, bytes(tmp), u, w)
            u.copyInto(t)

            // U2..Un: each message is one 32-byte digest, a single fixed block.
            for (round in 1 until iterations) {
                fillRoundBlock(block, u)
                compress(inner, block, tmp, w)
                fillRoundBlock(block, tmp)
                compress(outer, block, u, w)
                for (i in 0 until 8) t[i] = t[i] xor u[i]
            }

            val chunk = bytes(t)
            val take = minOf(32, length - written)
            chunk.copyInto(out, written, 0, take)
            written += take
            blockIndex++
        }
        return out
    }

    /** A 32-byte digest followed by SHA-256 padding for [ROUND_BITS]. */
    private fun fillRoundBlock(block: IntArray, digest: IntArray) {
        digest.copyInto(block, 0, 0, 8)
        block[8] = 0x80000000.toInt()
        for (i in 9 until 15) block[i] = 0
        block[15] = ROUND_BITS
    }

    /** SHA-256 of a message that follows one 64-byte block already absorbed into [state]. */
    private fun hashAfterKeyBlock(state: IntArray, message: ByteArray, out: IntArray, w: IntArray) {
        val totalBits = (64L + message.size) * 8
        var paddedLength = message.size + 1 + 8
        paddedLength += (64 - paddedLength % 64) % 64
        val padded = message.copyOf(paddedLength)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[paddedLength - 1 - i] = (totalBits ushr (8 * i)).toByte()

        var current = state.copyOf()
        val next = IntArray(8)
        for (offset in 0 until paddedLength step 64) {
            compress(current, words(padded.copyOfRange(offset, offset + 64)), next, w)
            current = next.copyOf()
        }
        current.copyInto(out)
    }

    private fun compress(state: IntArray, block: IntArray, out: IntArray, w: IntArray) {
        for (i in 0 until 16) w[i] = block[i]
        for (i in 16 until 64) {
            val a = w[i - 15]
            val b = w[i - 2]
            val s0 = Integer.rotateRight(a, 7) xor Integer.rotateRight(a, 18) xor (a ushr 3)
            val s1 = Integer.rotateRight(b, 17) xor Integer.rotateRight(b, 19) xor (b ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in 0 until 64) {
            val s1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + K[i] + w[i]
            val s0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        out[0] = state[0] + a
        out[1] = state[1] + b
        out[2] = state[2] + c
        out[3] = state[3] + d
        out[4] = state[4] + e
        out[5] = state[5] + f
        out[6] = state[6] + g
        out[7] = state[7] + h
    }

    private fun words(bytes: ByteArray): IntArray = IntArray(bytes.size / 4) { i ->
        ((bytes[4 * i].toInt() and 0xFF) shl 24) or ((bytes[4 * i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[4 * i + 2].toInt() and 0xFF) shl 8) or (bytes[4 * i + 3].toInt() and 0xFF)
    }

    private fun bytes(words: IntArray): ByteArray = ByteArray(words.size * 4) { i ->
        (words[i / 4] ushr (24 - 8 * (i % 4))).toByte()
    }
}
