package com.usbprint.core.print

import com.usbprint.core.print.capt.CaptDriver
import com.usbprint.core.print.capt.CaptProfile
import com.usbprint.core.print.escpos.EscPosDriver
import com.usbprint.core.print.pcl.PclRasterDriver
import com.usbprint.core.usb.UsbPrinter

/** Picks a driver from the IEEE 1284 Device ID (command set + model). */
object DriverMatcher {

    data class Match(val driver: PrinterDriver, val confident: Boolean, val note: String? = null)

    fun match(printer: UsbPrinter): Match? {
        val id = printer.deviceId
        val cmds = id?.commandSet.orEmpty().map { it.uppercase().replace("-", "").replace("/", "").replace(" ", "") }
        val model = listOfNotNull(id?.model, printer.usbProduct, id?.description).joinToString(" ")

        // Canon CAPT (host-based, bidirectional protocol)
        if ("CAPT" in cmds || (printer.brand == com.usbprint.core.usb.Brand.CANON && model.contains("LBP", true) && cmds.isEmpty())) {
            val profile = CaptProfile.forModel(model)
            return if (profile != null) {
                Match(CaptDriver(profile), confident = profile.verified, note = if (profile.verified) null else "Experimental CAPT support")
            } else {
                Match(CaptDriver(CaptProfile.LBP3010), confident = false, note = "Unknown CAPT model, trying LBP3010 protocol")
            }
        }

        // PCL 5 / PCL 3 raster
        val pcl = cmds.any { it == "PCL" || it.startsWith("PCL5") || it == "PCL3" || it == "PCL3GUI" || it == "HPPCL" }
        if (pcl) return Match(PclRasterDriver(usePjl = "PJL" in cmds), confident = true)

        // ESC/POS receipt printers
        if (cmds.any { it == "ESCPOS" || it == "ESCPOSPRINTER" } || model.contains("receipt", true)) {
            val narrow = Regex("""\b58\s?mm\b""", RegexOption.IGNORE_CASE).containsMatchIn(model)
            return Match(EscPosDriver(if (narrow) 384 else 576), confident = cmds.isNotEmpty())
        }

        return null
    }
}
