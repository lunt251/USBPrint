package com.usbprint.core.usb

/** USB printer class interface protocols. */
object UsbPrinterProtocol {
    const val UNIDIRECTIONAL = 1
    const val BIDIRECTIONAL = 2
    const val IEEE1284_4 = 3
    const val IPP_OVER_USB = 4
}

/** A printer interface found on a USB device, with everything readable over USB. */
data class UsbPrinter(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val interfaceIndex: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val protocol: Int,
    val bulkOutIndex: Int,
    val bulkInIndex: Int,
    val usbManufacturer: String?,
    val usbProduct: String?,
    val usbSerial: String?,
    val usbVersion: String?,
    val deviceId: DeviceId?,
    val status: PortStatus?,
) {
    /** Stable identity across re-plugs (deviceName changes every time). */
    val key: String get() = "%04x:%04x:%s".format(vendorId, productId, serial ?: "")

    val manufacturer: String? get() = deviceId?.manufacturer ?: usbManufacturer
    val model: String? get() = deviceId?.model ?: usbProduct
    val serial: String? get() = deviceId?.serial ?: usbSerial

    val brand: Brand get() = Brand.from(deviceId?.manufacturer, usbManufacturer, model)

    /** Model name without a repeated brand prefix, e.g. "HP LaserJet 1020" -> "LaserJet 1020". */
    val displayModel: String
        get() {
            val m = model?.trim().orEmpty()
            if (m.isEmpty()) return "USB printer"
            val prefixes = listOfNotNull(manufacturer, brand.displayName.takeIf { it.isNotEmpty() })
            for (p in prefixes) {
                if (m.length > p.length && m.startsWith(p, ignoreCase = true)) return m.substring(p.length).trim()
            }
            return m
        }

    val displayBrand: String
        get() = brand.displayName.ifEmpty { manufacturer?.trim().orEmpty().ifEmpty { "Printer" } }

    val protocolName: String
        get() = when (protocol) {
            UsbPrinterProtocol.UNIDIRECTIONAL -> "Unidirectional"
            UsbPrinterProtocol.BIDIRECTIONAL -> "Bidirectional"
            UsbPrinterProtocol.IEEE1284_4 -> "IEEE 1284.4"
            UsbPrinterProtocol.IPP_OVER_USB -> "IPP over USB"
            else -> "Protocol $protocol"
        }
}

/** Decoded GET_PORT_STATUS byte (USB printer class 1.1, section 4.2.2). */
data class PortStatus(val raw: Int) {
    val paperEmpty: Boolean get() = raw and 0x20 != 0
    val selected: Boolean get() = raw and 0x10 != 0
    val error: Boolean get() = raw and 0x08 == 0

    val summary: Summary
        get() = when {
            paperEmpty -> Summary.PAPER_OUT
            error -> Summary.ERROR
            !selected -> Summary.OFFLINE
            else -> Summary.READY
        }

    enum class Summary { READY, PAPER_OUT, OFFLINE, ERROR }
}
