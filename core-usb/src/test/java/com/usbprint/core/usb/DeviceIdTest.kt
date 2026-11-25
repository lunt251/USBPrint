package com.usbprint.core.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdTest {

    @Test
    fun parsesStandardFields() {
        val id = DeviceId.parse("MFG:Hewlett-Packard;MDL:HP LaserJet 1020;CMD:ZJS,PJL,ACL;CLS:PRINTER;DES:HP LaserJet 1020;FWVER:20050309;")
        assertEquals("Hewlett-Packard", id.manufacturer)
        assertEquals("HP LaserJet 1020", id.model)
        assertEquals(listOf("ZJS", "PJL", "ACL"), id.commandSet)
        assertEquals("PRINTER", id.deviceClass)
        assertTrue(id.supports("pjl"))
        assertTrue(HostBasedFirmware.isLoaded(id))
    }

    @Test
    fun acceptsLongKeyNames() {
        val id = DeviceId.parse("MANUFACTURER:EPSON;COMMAND SET:ESCPL2,BDC,D4;MODEL:L3150 Series;")
        assertEquals("EPSON", id.manufacturer)
        assertEquals("L3150 Series", id.model)
        assertEquals(listOf("ESCPL2", "BDC", "D4"), id.commandSet)
    }

    @Test
    fun parsesResponseWithLengthPrefix() {
        val text = "MFG:Canon;MDL:LBP2900;"
        val bytes = ByteArray(2 + text.length)
        bytes[0] = 0
        bytes[1] = (2 + text.length).toByte()
        text.toByteArray().copyInto(bytes, 2)
        assertEquals("LBP2900", DeviceId.fromResponse(bytes, bytes.size)?.model)
    }

    @Test
    fun clampsBadLengthPrefix() {
        val text = "MFG:Brother;MDL:HL-1110 series;"
        val bytes = ByteArray(2 + text.length)
        bytes[0] = 0x7F
        bytes[1] = 0x7F
        text.toByteArray().copyInto(bytes, 2)
        assertEquals("HL-1110 series", DeviceId.fromResponse(bytes, bytes.size)?.model)
    }

    @Test
    fun rejectsEmptyResponse() {
        assertNull(DeviceId.fromResponse(ByteArray(2), 2))
        assertNull(DeviceId.fromResponse(ByteArray(10), -1))
    }

    @Test
    fun brandDetection() {
        assertEquals(Brand.HP, Brand.from("Hewlett-Packard"))
        assertEquals(Brand.EPSON, Brand.from("SEIKO EPSON"))
        assertEquals(Brand.OTHER, Brand.from(null, "Generic"))
        assertEquals(Brand.BROTHER, Brand.from(null, null, "Brother HL-1110"))
    }
}
