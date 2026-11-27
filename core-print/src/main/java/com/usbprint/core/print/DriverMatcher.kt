package com.usbprint.core.print

import com.usbprint.core.print.capt.CaptDriver
import com.usbprint.core.print.capt.CaptProfile
import com.usbprint.core.print.escpos.EscPosDriver
import com.usbprint.core.print.pcl.PclRasterDriver
import com.usbprint.core.print.ps.PostScriptDriver
import com.usbprint.core.usb.Brand
import com.usbprint.core.usb.UsbPrinter

/** Every built-in driver, for the manual "Change driver" picker. */
object DriverRegistry {
    data class Entry(val id: String, val name: String, val create: () -> PrinterDriver)

    val all: List<Entry> = buildList {
        CaptProfile.entries.forEach { p -> add(entry { CaptDriver(p) }) }
        add(entry { PclRasterDriver(600, usePjl = true) })
        add(entry { PclRasterDriver(300, usePjl = true) })
        add(entry { PclRasterDriver(600, usePjl = false) })
        add(entry { PostScriptDriver(300) })
        add(entry { PostScriptDriver(600) })
        add(entry { EscPosDriver(576) })
        add(entry { EscPosDriver(384) })
    }

    private fun entry(create: () -> PrinterDriver): Entry {
        val d = create()
        return Entry(d.id, d.displayName, create)
    }

    fun create(id: String): PrinterDriver? = all.firstOrNull { it.id == id }?.create?.invoke()
}

/** Picks a driver from the IEEE 1284 Device ID (command set + model). */
object DriverMatcher {

    data class Match(val driver: PrinterDriver, val confident: Boolean, val note: String? = null)

    fun match(printer: UsbPrinter): Match? {
        val id = printer.deviceId
        val cmds = id?.commandSet.orEmpty().map { it.uppercase().replace("-", "").replace("/", "").replace(" ", "") }
        val model = listOfNotNull(id?.model, printer.usbProduct, id?.description).joinToString(" ")

        // Canon CAPT (host-based, bidirectional protocol)
        if ("CAPT" in cmds || (printer.brand == Brand.CANON && model.contains("LBP", true) && cmds.isEmpty())) {
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

        // PostScript and clones
        if (cmds.any { it == "POSTSCRIPT" || it == "PS" || it == "BRSCRIPT" || it == "KPDL" || it.startsWith("POSTSCRIPT") }) {
            return Match(PostScriptDriver(300), confident = true)
        }

        // ESC/POS receipt printers
        if (cmds.any { it == "ESCPOS" || it == "ESCPOSPRINTER" } || model.contains("receipt", true)) {
            val narrow = Regex("""\b58\s?mm\b""", RegexOption.IGNORE_CASE).containsMatchIn(model)
            return Match(EscPosDriver(if (narrow) 384 else 576), confident = cmds.isNotEmpty())
        }

        // PCL XL-only printers usually still accept PCL 5 raster after a PJL language switch.
        if (cmds.any { it == "PCLXL" || it == "PCL6" }) {
            return Match(PclRasterDriver(usePjl = true), confident = false, note = "PCL XL printer, trying PCL raster")
        }

        return null
    }
}
