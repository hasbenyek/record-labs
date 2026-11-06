@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.recordlabs.desktop.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/**
 * AES-256-GCM for the Connect relay on iOS (the desktop and Android use the JDK's
 * "AES/GCM/NoPadding"): the same messages, so an iPhone and the other devices read
 * each other's. CommonCrypto has AES but no public GCM; GCM is built here on AES
 * (CommonCrypto, ECB for the counter blocks) as NIST SP 800-38D describes it, with
 * 96-bit nonces and 128-bit tags only (all Connect uses).
 */
internal object IosAesGcm {
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        Gcm.seal(::aesEcb, key, nonce, aad, plain)

    /** Null when the message was not sealed with [key] (or was altered). */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        Gcm.open(::aesEcb, key, nonce, aad, sealed)

    /** AES (key size from [key]) of whole 16-byte blocks, each on its own (ECB). */
    private fun aesEcb(key: ByteArray, input: ByteArray): ByteArray {
        require(input.size % 16 == 0 && input.isNotEmpty())
        val out = ByteArray(input.size)
        memScoped {
            val moved = alloc<size_tVar>()
            val status = key.usePinned { k ->
                input.usePinned { i ->
                    out.usePinned { o ->
                        CCCrypt(
                            kCCEncrypt, kCCAlgorithmAES, kCCOptionECBMode,
                            k.addressOf(0), key.size.convert(),
                            null,
                            i.addressOf(0), input.size.convert(),
                            o.addressOf(0), out.size.convert(),
                            moved.ptr,
                        )
                    }
                }
            }
            check(status == kCCSuccess && moved.value.toInt() == input.size) { "AES failed ($status)" }
        }
        return out
    }
}

/** GCM on any AES block function (pure Kotlin; tested against the JDK's AES/GCM). */
internal object Gcm {
    private const val TAG = 16

    fun seal(aes: (ByteArray, ByteArray) -> ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(nonce.size == 12)
        val blocks = (plain.size + 15) / 16
        // One AES call: H = E(0), E(J0), then the keystream E(J0+1) ... E(J0+n).
        val stream = aes(key, counterInput(nonce, blocks))
        val h = stream.copyOfRange(0, 16)
        val ej0 = stream.copyOfRange(16, 32)
        val cipher = ByteArray(plain.size) { (plain[it].toInt() xor stream[32 + it].toInt()).toByte() }
        val tag = ghash(h, aad, cipher)
        for (i in 0 until TAG) tag[i] = (tag[i].toInt() xor ej0[i].toInt()).toByte()
        return cipher + tag
    }

    fun open(aes: (ByteArray, ByteArray) -> ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (nonce.size != 12 || sealed.size < TAG) return null
        val cipher = sealed.copyOfRange(0, sealed.size - TAG)
        val blocks = (cipher.size + 15) / 16
        val stream = aes(key, counterInput(nonce, blocks))
        val h = stream.copyOfRange(0, 16)
        val ej0 = stream.copyOfRange(16, 32)
        val expected = ghash(h, aad, cipher)
        var diff = 0
        for (i in 0 until TAG) diff = diff or ((expected[i].toInt() xor ej0[i].toInt()) xor sealed[cipher.size + i].toInt())
        if (diff and 0xff != 0) return null
        return ByteArray(cipher.size) { (cipher[it].toInt() xor stream[32 + it].toInt()).toByte() }
    }

    /** 0^128, J0 = nonce || 1, then J0+1 ... J0+[blocks] (32-bit counter). */
    private fun counterInput(nonce: ByteArray, blocks: Int): ByteArray {
        val out = ByteArray(32 + blocks * 16)
        for (b in 0..blocks) {
            val at = 16 + b * 16
            nonce.copyInto(out, at)
            val counter = 1 + b
            out[at + 12] = (counter ushr 24).toByte()
            out[at + 13] = (counter ushr 16).toByte()
            out[at + 14] = (counter ushr 8).toByte()
            out[at + 15] = counter.toByte()
        }
        return out
    }

    private fun ghash(h: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
        val hHi = h.longAt(0)
        val hLo = h.longAt(8)
        var yHi = 0L
        var yLo = 0L
        fun absorb(data: ByteArray) {
            var at = 0
            while (at < data.size) {
                val block = ByteArray(16)
                data.copyInto(block, 0, at, minOf(at + 16, data.size))
                yHi = yHi xor block.longAt(0)
                yLo = yLo xor block.longAt(8)
                val (zHi, zLo) = multiply(yHi, yLo, hHi, hLo)
                yHi = zHi
                yLo = zLo
                at += 16
            }
        }
        absorb(aad)
        absorb(cipher)
        yHi = yHi xor (aad.size.toLong() * 8)
        yLo = yLo xor (cipher.size.toLong() * 8)
        val (zHi, zLo) = multiply(yHi, yLo, hHi, hLo)
        val out = ByteArray(16)
        out.putLong(0, zHi)
        out.putLong(8, zLo)
        return out
    }

    /** X * Y in GF(2^128), GCM's bit order (SP 800-38D, algorithm 1). */
    private fun multiply(xHi: Long, xLo: Long, yHi: Long, yLo: Long): Pair<Long, Long> {
        var zHi = 0L
        var zLo = 0L
        var vHi = yHi
        var vLo = yLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
            if (bit != 0L) {
                zHi = zHi xor vHi
                zLo = zLo xor vLo
            }
            val carry = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (carry != 0L) vHi = vHi xor -0x1f00000000000000L // 0xE1 << 56
        }
        return zHi to zLo
    }

    private fun ByteArray.longAt(at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (this[at + i].toLong() and 0xff)
        return v
    }

    private fun ByteArray.putLong(at: Int, value: Long) {
        for (i in 0 until 8) this[at + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
