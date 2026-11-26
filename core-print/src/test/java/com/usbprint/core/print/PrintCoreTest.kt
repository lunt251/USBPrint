package com.usbprint.core.print

import com.usbprint.core.print.capt.CaptProfile
import com.usbprint.core.print.capt.HiscoaCompressor
import com.usbprint.core.print.pcl.PackBits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrintCoreTest {

    /** Byte-for-byte comparison against captdriver's C compressor (vectors built in CI). */
    @Test
    fun hiscoaMatchesReference() {
        val file = File(System.getProperty("hiscoa.golden") ?: "../tools/capt-ref/hiscoa_cases.bin")
        assumeTrue("golden vectors not generated (run tools/capt-ref)", file.exists())
        val data = file.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var cases = 0
        while (buf.remaining() > 0) {
            val lineSize = buf.int
            val lines = buf.int
            val input = ByteArray(buf.int).also { buf.get(it) }
            val expected = ByteArray(buf.int).also { buf.get(it) }
            val out = ByteArray(2 * input.size + 64)
            val n = HiscoaCompressor.compressBand(input, lineSize, lines, out)
            assertArrayEquals("case $cases (line $lineSize x $lines)", expected, out.copyOf(n))
            cases++
        }
        assertEquals(24, cases)
    }

    @Test
    fun hiscoaOutputIsWordAligned() {
        val band = ByteArray(595 * 160) { if (it % 7 == 0) 0x55 else 0 }
        val out = ByteArray(band.size * 2)
        val n = HiscoaCompressor.compressBand(band, 595, 160, out)
        assertEquals(0, n % 4)
    }

    @Test
    fun packBitsRoundTrip() {
        val samples = listOf(
            ByteArray(0),
            ByteArray(1) { 5 },
            ByteArray(300) { 0 },
            ByteArray(300) { (it * 37).toByte() },
            ByteArray(500) { if (it % 50 < 10) 0xFF.toByte() else (it % 3).toByte() },
        )
        for (s in samples) {
            val out = ByteArray(s.size + s.size / 128 + 2)
            val n = PackBits.encode(s, 0, s.size, out)
            assertArrayEquals(s, PackBits.decode(out, n))
        }
    }

    @Test
    fun monoPackerDither() {
        val w = 16
        val black = ByteArray(w * 2) { 0 }
        val white = ByteArray(w * 2) { 0xFF.toByte() }
        val out = ByteArray(4)
        MonoPacker.pack(black, w, 2, 0, out)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), out)
        MonoPacker.pack(white, w, 2, 0, out)
        assertArrayEquals(ByteArray(4), out)
    }

    @Test
    fun captProfiles() {
        assertEquals(CaptProfile.LBP6000, CaptProfile.forModel("LBP6000/LBP6018"))
        assertEquals(CaptProfile.LBP2900, CaptProfile.forModel("Canon LBP2900"))
        assertEquals(CaptProfile.LBP3010, CaptProfile.forModel("LBP3018"))
        assertEquals(null, CaptProfile.forModel("LBP7110Cw"))
    }
}
