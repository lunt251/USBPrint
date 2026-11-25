package com.usbprint.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PrinterState {
    /** No printer attached. */
    data object Searching : PrinterState

    /** A printer is attached but the user hasn't granted access yet. */
    data class NeedsPermission(val label: String) : PrinterState

    /** Permission granted, reading the Device ID. */
    data class Identifying(val label: String) : PrinterState

    data class Connected(val printer: UsbPrinter) : PrinterState
}

/**
 * Event-driven USB printer detection. Listens for attach/detach broadcasts instead of polling,
 * asks for permission once, then reads the IEEE 1284 Device ID and port status.
 */
class UsbPrinterManager(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probeJob: Job? = null
    private var permissionRequested: String? = null

    private val _state = MutableStateFlow<PrinterState>(PrinterState.Searching)
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    permissionRequested = null
                    refresh()
                }
                ACTION_PERMISSION -> refresh(requestPermission = false)
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Re-scans attached devices. Call from onResume and when a USB_DEVICE_ATTACHED intent launches the app. */
    fun refresh(requestPermission: Boolean = true) {
        probeJob?.cancel()
        probeJob = scope.launch { scan(requestPermission) }
    }

    /** Explicit "Allow access" tap from the UI. */
    fun requestPermission() {
        permissionRequested = null
        refresh(requestPermission = true)
    }

    fun findDevice(printer: UsbPrinter): UsbDevice? {
        val devices = usbManager.deviceList
        devices[printer.deviceName]?.let { if (it.vendorId == printer.vendorId && it.productId == printer.productId) return it }
        return devices.values.firstOrNull { it.vendorId == printer.vendorId && it.productId == printer.productId }
    }

    private fun scan(requestPermission: Boolean) {
        val device = usbManager.deviceList.values.firstOrNull(UsbPrinterClass::hasPrinterInterface)
        if (device == null) {
            _state.value = PrinterState.Searching
            return
        }
        val label = device.productName ?: device.manufacturerName ?: "USB printer"
        if (!usbManager.hasPermission(device)) {
            _state.value = PrinterState.NeedsPermission(label)
            if (requestPermission && permissionRequested != device.deviceName) {
                permissionRequested = device.deviceName
                askPermission(device)
            }
            return
        }
        val current = _state.value
        if (current !is PrinterState.Connected || current.printer.deviceName != device.deviceName) {
            _state.value = PrinterState.Identifying(label)
        }
        _state.value = probe(device)?.let { PrinterState.Connected(it) } ?: PrinterState.Searching
    }

    private fun askPermission(device: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        usbManager.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent, flags))
    }

    /** Opens the device briefly to read the Device ID and status, then releases it. */
    private fun probe(device: UsbDevice): UsbPrinter? {
        val c = UsbPrinterClass.findPrinterInterface(device) ?: return null
        var deviceId: DeviceId? = null
        var status: PortStatus? = null
        usbManager.openDevice(device)?.let { conn ->
            try {
                if (conn.claimInterface(c.iface, true)) {
                    deviceId = UsbPrinterClass.getDeviceId(conn, c.iface)
                    status = UsbPrinterClass.getPortStatus(conn, c.iface)
                    conn.releaseInterface(c.iface)
                }
            } finally {
                conn.close()
            }
        }
        val serial = try { device.serialNumber } catch (_: SecurityException) { null }
        return UsbPrinter(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            interfaceIndex = c.index,
            interfaceNumber = c.iface.id,
            alternateSetting = c.iface.alternateSetting,
            protocol = c.iface.interfaceProtocol,
            bulkOutIndex = c.bulkOut,
            bulkInIndex = c.bulkIn,
            usbManufacturer = device.manufacturerName,
            usbProduct = device.productName,
            usbSerial = serial,
            usbVersion = device.version,
            deviceId = deviceId,
            status = status,
        )
    }

    /** Opens a print connection. Must be called off the main thread. */
    fun open(printer: UsbPrinter): UsbPrinterConnection {
        val device = findDevice(printer) ?: throw java.io.IOException("Printer disconnected")
        if (!usbManager.hasPermission(device)) throw SecurityException("No permission for USB printer")
        return UsbPrinterConnection.open(usbManager, device, printer)
    }

    companion object {
        const val ACTION_PERMISSION = "com.usbprint.USB_PERMISSION"
    }
}
