package com.usbprint.core.print.capt

/**
 * Canon "HiSCoA" band compressor used by CAPT printers.
 * Kotlin port of hiscoa-compress.c from captdriver (GPL-3.0, (C) 2013 Alexey Galakhov).
 */
internal class HiscoaCompressor(
    private val input: ByteArray,
    private val inputSize: Int,
    private val lineSize: Int,
    private val output: ByteArray,
    params: HiscoaParams = HiscoaParams.DEFAULT,
) {
    private val outputBitSize = output.size.toLong() * 8
    private var bitPos = 0L
    private var inputPos = 0
    private var nBytes = 0
    private val bytes = IntArray(16)
    private val origin = IntArray(6)

    init {
        origin[1] = 0
        origin[3] = params.origin3
        origin[5] = params.origin5
        origin[0] = params.origin0 + lineSize
        origin[2] = params.origin2 + lineSize
        origin[4] = params.origin4
    }

    private fun pushBits(bitsIn: Long, countIn: Int) {
        val bits = bitsIn and 0xFFFFFFFFL
        var count = countIn
        while (count > 0 && bitPos < outputBitSize) {
            val word = (bitPos / 8).toInt()
            val spc = 8 - (bitPos % 8).toInt()
            val cnt = if (count > spc) spc else count
            val mask = ((0xFF ushr (8 - cnt)) shl (spc - cnt)) and 0xFF
            val v = ((bits ushr (count - cnt)) shl (spc - cnt)).toInt() and 0xFF
            var x = output[word].toInt() and 0xFF
            x = x xor XOR_VAL
            x = x and mask.inv() and 0xFF
            x = x or (mask and v)
            x = x xor XOR_VAL
            output[word] = x.toByte()
            bitPos += cnt
            count -= cnt
        }
    }

    private fun tryMatch(diff: Int): Int {
        var pos = inputPos
        if (pos < diff) return 0
        while (input[pos] == input[pos - diff]) {
            ++pos
            if (pos >= inputSize) break
            if (pos >= inputPos + 512 + 127) break
            if (pos % lineSize == 0) break
        }
        return pos - inputPos
    }

    private fun findMsb(v: Int): Int = if (v == 0) 0 else 32 - Integer.numberOfLeadingZeros(v)

    private fun tryWriteLongRepeat(): Boolean {
        var bestCmd = 0
        var bestLen = 0
        for (cmd in 0 until 6) {
            if (origin[cmd] != 0) {
                val len = tryMatch(origin[cmd])
                if (len > bestLen) {
                    bestCmd = cmd
                    bestLen = len
                }
            }
        }
        if (bestLen <= 1) return false

        var len = bestLen
        if (len > 127) {
            val v = len / 128
            val nbits = findMsb(v) - 1
            pushBits(0xFC, 8)
            pushBits(nbits.toLong(), 2)
            pushBits(v.inv().toLong(), nbits)
            len %= 128
        }

        pushBits(0xFFFFFFFEL, bestCmd + 1) // longrep
        if (bestCmd == 2) pushBits(0x0, 1) // subcommand 0

        when (len) {
            0 -> pushBits(0x3F, 6)
            1 -> pushBits(0x0, 2)
            2 -> pushBits(0x3, 3)
            3 -> pushBits(0x2, 3)
            else -> {
                val nbits = findMsb(len) - 1
                pushBits(0xFFFFFFFEL, nbits)
                pushBits(len.inv().toLong(), nbits)
            }
        }

        if (bestCmd == 2) swap(2, 0)
        if (bestCmd == 5) swap(5, 3)

        inputPos += bestLen
        return true
    }

    private fun swap(a: Int, b: Int) {
        val t = origin[a]
        origin[a] = origin[b]
        origin[b] = t
    }

    private fun tryWriteByteRepeat(): Boolean {
        val byte = input[inputPos].toInt() and 0xFF
        for (i in 0 until nBytes) {
            if (byte == bytes[i]) {
                for (j in i downTo 1) bytes[j] = bytes[j - 1]
                bytes[0] = byte
                pushBits((0x20 or ((15 - i) and 0xF)).toLong(), 6)
                inputPos += 1
                return true
            }
        }
        return false
    }

    private fun writeSimpleByte() {
        val byte = input[inputPos].toInt() and 0xFF
        if (nBytes < 16) nBytes += 1
        for (i in nBytes - 1 downTo 1) bytes[i] = bytes[i - 1]
        bytes[0] = byte
        if (byte == 0) pushBits(0xFD, 8) else pushBits((0xD00 or byte).toLong(), 12)
        inputPos += 1
    }

    /** Compresses the band and returns the number of output bytes used. */
    fun compress(lastBand: Boolean = false): Int {
        while (inputPos < inputSize) {
            if (tryWriteLongRepeat()) continue
            if (tryWriteByteRepeat()) continue
            writeSimpleByte()
        }
        pushBits(0xFE, 8) // end
        pushBits(if (lastBand) 1 else 0, 2)
        pushBits(0xFFFFFFFFL, 32 - (bitPos % 32).toInt())
        return (bitPos / 8).toInt()
    }

    companion object {
        private const val XOR_VAL = 0x43

        /** Compresses [lines] rows of [lineSize] bytes. Output buffer must be >= 2 * band size. */
        fun compressBand(band: ByteArray, lineSize: Int, lines: Int, out: ByteArray): Int {
            out.fill(0)
            return HiscoaCompressor(band, lineSize * lines, lineSize, out).compress()
        }
    }
}

internal data class HiscoaParams(
    val origin3: Int,
    val origin5: Int,
    val origin0: Int,
    val origin2: Int,
    val origin4: Int,
) {
    /** The 8-byte parameter block sent with CAPT_SET_PARM_HISCOA. */
    fun format(): ByteArray = byteArrayOf(
        origin3.toByte(),
        origin5.toByte(),
        0x01,
        0x01,
        origin0.toByte(),
        origin2.toByte(),
        (origin4 and 0xFF).toByte(),
        ((origin4 shr 8) and 0xFF).toByte(),
    )

    companion object {
        val DEFAULT = HiscoaParams(origin3 = 1, origin5 = 4, origin0 = 0, origin2 = -7, origin4 = 0)
    }
}
