package com.usbprint.core.usb

import java.io.InputStream

/**
 * HP host-based LaserJets (1000/1005/1018/1020/P100x/P1505) have no firmware in flash and
 * need it uploaded after every power-on. The firmware files themselves ship with the
 * native driver pack (foo2zjs `sihp*.dl`).
 */
object HostBasedFirmware {
    private const val HP_VENDOR_ID = 0x03F0

    private val firmwareByProductId = mapOf(
        0x0517 to "sihp1000.dl",
        0x1317 to "sihp1005.dl",
        0x4117 to "sihp1018.dl",
        0x2B17 to "sihp1020.dl",
        0x3D17 to "sihpP1005.dl",
        0x3E17 to "sihpP1006.dl",
        0x4817 to "sihpP1007.dl",
        0x4917 to "sihpP1008.dl",
        0x3F17 to "sihpP1505.dl",
    )

    fun firmwareFileFor(printer: UsbPrinter): String? =
        if (printer.vendorId == HP_VENDOR_ID) firmwareByProductId[printer.productId] else null

    /** Loaded firmware shows up as an FWVER field in the Device ID. */
    fun isLoaded(deviceId: DeviceId?): Boolean = deviceId?.raw?.contains("FWVER", ignoreCase = true) == true

    /**
     * Uploads firmware if needed and waits until the printer reports it (instead of a fixed sleep).
     * Returns true if firmware was uploaded.
     */
    fun ensureLoaded(conn: UsbPrinterConnection, open: (String) -> InputStream?, timeoutMs: Long = 20_000): Boolean {
        val file = firmwareFileFor(conn.printer) ?: return false
        if (isLoaded(conn.deviceId())) return false
        val stream = open(file) ?: return false
        stream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                conn.write(buf, 0, n)
            }
        }
        conn.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (conn.isCancelled) throw PrintCancelledException()
            if (isLoaded(conn.deviceId())) return true
            Thread.sleep(250)
        }
        return true
    }
}
