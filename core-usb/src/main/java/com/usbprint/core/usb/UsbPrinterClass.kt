package com.usbprint.core.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface

/** Low-level USB printer class (bInterfaceClass 7) helpers. */
internal object UsbPrinterClass {
    const val CLASS_PRINTER = 7

    private const val REQ_GET_DEVICE_ID = 0
    private const val REQ_GET_PORT_STATUS = 1
    private const val REQ_SOFT_RESET = 2

    // bmRequestType: class request, interface recipient.
    private const val IN_CLASS_INTERFACE = 0xA1
    private const val OUT_CLASS_INTERFACE = 0x21
    // Some early printers expect "other" as recipient for SOFT_RESET.
    private const val OUT_CLASS_OTHER = 0x23

    data class Candidate(
        val index: Int,
        val iface: UsbInterface,
        val bulkOut: Int,
        val bulkIn: Int,
    )

    /**
     * Finds the best printer interface on [device]. Bidirectional is preferred over unidirectional;
     * IPP-over-USB only if nothing else exists (it needs an IPP client rather than a raw stream).
     */
    fun findPrinterInterface(device: UsbDevice): Candidate? {
        val candidates = ArrayList<Candidate>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != CLASS_PRINTER) continue
            var out = -1
            var inp = -1
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT && out == -1) out = e
                if (ep.direction == UsbConstants.USB_DIR_IN && inp == -1) inp = e
            }
            if (out != -1) candidates += Candidate(i, iface, out, inp)
        }
        fun rank(c: Candidate) = when (c.iface.interfaceProtocol) {
            UsbPrinterProtocol.BIDIRECTIONAL -> 0
            UsbPrinterProtocol.UNIDIRECTIONAL -> 1
            UsbPrinterProtocol.IEEE1284_4 -> 2
            UsbPrinterProtocol.IPP_OVER_USB -> 4
            else -> 3
        }
        return candidates.minByOrNull(::rank)
    }

    fun hasPrinterInterface(device: UsbDevice): Boolean = findPrinterInterface(device) != null

    fun getDeviceId(conn: UsbDeviceConnection, iface: UsbInterface, timeoutMs: Int = 2000): DeviceId? {
        val buf = ByteArray(1024)
        val index = (iface.id shl 8) or iface.alternateSetting
        val n = conn.controlTransfer(IN_CLASS_INTERFACE, REQ_GET_DEVICE_ID, 0, index, buf, buf.size, timeoutMs)
        return DeviceId.fromResponse(buf, n)
    }

    fun getPortStatus(conn: UsbDeviceConnection, iface: UsbInterface, timeoutMs: Int = 1000): PortStatus? {
        val buf = ByteArray(1)
        val n = conn.controlTransfer(IN_CLASS_INTERFACE, REQ_GET_PORT_STATUS, 0, iface.id, buf, 1, timeoutMs)
        return if (n == 1) PortStatus(buf[0].toInt() and 0xFF) else null
    }

    /** Flushes the printer's buffers and resets the interface. Returns true if the printer accepted it. */
    fun softReset(conn: UsbDeviceConnection, iface: UsbInterface, timeoutMs: Int = 1000): Boolean {
        if (conn.controlTransfer(OUT_CLASS_INTERFACE, REQ_SOFT_RESET, 0, iface.id, null, 0, timeoutMs) >= 0) return true
        return conn.controlTransfer(OUT_CLASS_OTHER, REQ_SOFT_RESET, 0, iface.id, null, 0, timeoutMs) >= 0
    }
}
