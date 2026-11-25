package com.usbprint.app.print

import com.usbprint.core.usb.UsbPrinter
import com.usbprint.core.usb.UsbPrinterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Minimal text test page for printers that accept text directly (PCL lasers, ESC/POS receipt
 * printers). Full raster test pages arrive with the driver milestone.
 */
object TestPage {

    enum class Language { PCL, ESC_POS }

    fun languageFor(printer: UsbPrinter): Language? {
        val id = printer.deviceId ?: return null
        val cmds = id.commandSet.map { it.uppercase() }
        return when {
            cmds.any { it == "PCL" || it.startsWith("PCL5") || it == "PCLXL" || it == "PCL6" } -> Language.PCL
            cmds.any { it.replace("/", "").replace("-", "") == "ESCPOS" } -> Language.ESC_POS
            else -> null
        }
    }

    fun build(printer: UsbPrinter, language: Language): ByteArray {
        val lines = listOf(
            "USBPrint test page",
            "",
            "Printer:   ${printer.displayBrand} ${printer.displayModel}",
            "USB ID:    %04X:%04X".format(printer.vendorId, printer.productId),
            "Languages: ${printer.deviceId?.commandSet?.joinToString(", ").orEmpty()}",
            "",
            "If you can read this, USB printing works.",
        )
        val out = ByteArrayOutputStream()
        when (language) {
            Language.PCL -> {
                out.write(UEL)
                out.write("\u001BE".toByteArray()) // reset
                lines.forEach { out.write("$it\r\n".toByteArray(Charsets.ISO_8859_1)) }
                out.write(0x0C) // form feed
                out.write("\u001BE".toByteArray())
                out.write(UEL)
            }
            Language.ESC_POS -> {
                out.write(byteArrayOf(0x1B, 0x40)) // init
                lines.forEach { out.write("$it\n".toByteArray(Charsets.ISO_8859_1)) }
                out.write(byteArrayOf(0x1B, 0x64, 4)) // feed 4 lines
                out.write(byteArrayOf(0x1D, 0x56, 0x42, 0)) // partial cut
            }
        }
        return out.toByteArray()
    }

    /** Returns false if the printer needs a real driver. Throws on transfer failure. */
    suspend fun print(manager: UsbPrinterManager, printer: UsbPrinter): Boolean = withContext(Dispatchers.IO) {
        val lang = languageFor(printer) ?: return@withContext false
        manager.open(printer).use { conn ->
            conn.write(build(printer, lang))
            conn.flush()
        }
        true
    }

    private val UEL = "\u001B%-12345X".toByteArray()
}
