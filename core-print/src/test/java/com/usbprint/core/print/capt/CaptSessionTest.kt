package com.usbprint.core.print.capt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CaptSessionTest {
    @Test
    fun paperOutDuringSetupClosesPartialJobBeforeRetry() {
        val printer = FakeLbp6000().apply { paperOutInSetup = true }
        val failed = session(printer)
        assertThrows(PaperOutException::class.java) { printPage(failed) }
        assertFalse(CaptCmd.SET_PARMS in printer.commands)
        assertTrue(failed.abort())
        assertEquals(listOf(CaptCmd.GPIO, CaptCmd.JOB_SETUP, CaptCmd.JOB_END), printer.commands.takeLast(3))
        assertEquals(listOf(4), printer.setupPhases)

        printer.paperOutInSetup = false
        printer.stalePaperBit = true // Can still be set before the next job after loading paper.
        val retry = session(printer)
        printPage(retry)
        retry.finish()
        assertEquals(2, printer.commands.count { it == CaptCmd.JOB_END })
        assertFalse(printer.jobActive)
    }

    @Test
    fun emptyTrayAfterFeedDoesNotHangEvenIfProcessingFlagStaysSet() {
        val printer = FakeLbp6000().apply { paperOutAfterFeed = true }
        val job = session(printer)
        assertThrows(PaperOutException::class.java) { printPage(job) }
        assertTrue(CaptCmd.FIRE in printer.commands)
        assertTrue(printer.elapsedMs in 3_000L..4_000L)
        assertTrue(job.abort())
        assertFalse(printer.jobActive)
    }

    @Test
    fun lastPageStillCompletesWhenItEmptiesTheTray() {
        val printer = FakeLbp6000().apply { lastSheet = true }
        val job = session(printer)
        printPage(job)
        job.finish()
        assertEquals(listOf(1, 2, 6, 4), printer.setupPhases)
        assertFalse(printer.jobActive)
    }

    @Test
    fun lbp6000BandReadinessIsBoundedAndUsesExtendedStatus() {
        val printer = FakeLbp6000().apply { busyDuringBands = true }
        val job = session(printer)
        val e = assertThrows(IOException::class.java) {
            job.printPage(ByteArray(40), List(16) { byteArrayOf(1) }) {}
        }
        assertEquals("Printer stopped responding", e.message)
        assertEquals(15, printer.commands.count { it == CaptCmd.PRINT_DATA })
        assertTrue(printer.elapsedMs in 31_000L..32_000L)
        assertTrue(job.abort())
    }

    @Test
    fun missingJobBeginReplyRequiresTransportReset() {
        val printer = FakeLbp6000().apply { loseJobBeginReply = true }
        val job = session(printer)
        assertThrows(IOException::class.java) { printPage(job) }
        assertFalse(job.abort())
        assertFalse(CaptCmd.JOB_END in printer.commands)
    }

    private fun session(printer: FakeLbp6000) = CaptSession(CaptProfile.LBP6000, printer) {
        printer.elapsedMs * 1_000_000
    }

    private fun printPage(job: CaptSession) = job.printPage(ByteArray(40), listOf(byteArrayOf(1))) {}

    private class FakeLbp6000 : CaptTransport {
        var paperOutInSetup = false
        var paperOutAfterFeed = false
        var stalePaperBit = false
        var lastSheet = false
        var busyDuringBands = false
        var loseJobBeginReply = false
        var elapsedMs = 0L
        var jobActive = false
            private set
        private var decoded = false
        private var fired = false
        private var bands = 0
        val commands = mutableListOf<Int>()
        val setupPhases = mutableListOf<Int>()

        override fun send(cmd: Int, data: ByteArray?) {
            commands += cmd
            if (cmd == CaptCmd.PRINT_DATA) bands++
            if (cmd == CaptCmd.PRINT_DATA_END) decoded = true
        }

        override fun sendMulti(cmd: Int, parts: List<Pair<Int, ByteArray?>>) {
            commands += cmd
        }

        override fun sendRecv(cmd: Int, data: ByteArray?): ByteArray {
            commands += cmd
            return when (cmd) {
                CaptCmd.CHKSTATUS -> error("LBP6000 requires extended status")
                CaptCmd.CHKXSTATUS -> statusReply()
                CaptCmd.JOB_BEGIN -> {
                    check(!jobActive) { "Previous job did not end" }
                    jobActive = true
                    decoded = false
                    fired = false
                    bands = 0
                    if (loseJobBeginReply) throw IOException("Missing job-begin reply")
                    byteArrayOf(0, 0, 0x34, 0x12)
                }
                CaptCmd.JOB_SETUP -> {
                    checkNotNull(data)
                    check(word(data[18], data[19]) == 0x1234)
                    setupPhases += data[16].toInt()
                    byteArrayOf()
                }
                CaptCmd.FIRE -> {
                    fired = true
                    byteArrayOf()
                }
                CaptCmd.JOB_END -> {
                    checkNotNull(data)
                    check(word(data[0], data[1]) == 0x1234)
                    jobActive = false
                    byteArrayOf()
                }
                else -> byteArrayOf()
            }
        }

        private fun statusReply() = ByteArray(40).apply {
            if (jobActive && paperOutInSetup) this[0] = 0x82.toByte() // busy + no-paper flag 1
            if (!jobActive && stalePaperBit) this[0] = 0x02
            if (busyDuringBands && bands == 15) this[0] = 0x80.toByte()
            if (decoded) {
                this[14] = 1 // pageDecoding
                this[34] = 1 // pageReceived
            }
            if (fired && paperOutAfterFeed) {
                this[8] = 0x80.toByte() // processing
                this[9] = 0x40 // no-paper flag 2
            } else if (fired) {
                this[18] = 1 // pageOut
                this[20] = 1 // pageCompleted
                if (lastSheet) this[0] = 0x02
            }
        }

        override fun pause(ms: Long) {
            elapsedMs += ms
        }
    }
}
