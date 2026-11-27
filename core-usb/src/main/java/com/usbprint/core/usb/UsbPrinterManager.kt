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
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PrinterState {
    data object Searching : PrinterState
    data object Disconnected : PrinterState
    data class NeedsPermission(val label: String) : PrinterState
    data class Identifying(val label: String) : PrinterState
    data class Connected(val printer: UsbPrinter) : PrinterState
}

/** Tracks USB attachment and probes a newly connected printer once permission is granted. */
class UsbPrinterManager(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var probeJob: Job? = null
    private var reconnectMessageJob: Job? = null
    private var probingDevice: String? = null
    private var permissionRequested: String? = null
    private val interfaceLock = Any()
    @Volatile private var activeConnection: UsbPrinterConnection? = null

    private val _state = MutableStateFlow<PrinterState>(PrinterState.Searching)
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    init {
        // These protected system broadcasts are separate from the app's permission callback.
        // NOT_EXPORTED can filter out system senders (on older AndroidX it uses a signature permission).
        val usbEvents = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val detached = if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.deviceName
                } else null
                scan(requestPermission = true, detached = detached)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, usbEvents, filter, ContextCompat.RECEIVER_EXPORTED)
        val permissionResult = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh(requestPermission = false)
        }
        ContextCompat.registerReceiver(appContext, permissionResult, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Reconciles USB enumeration without reopening the interface of an already identified printer. */
    fun refresh(requestPermission: Boolean = true) {
        scope.launch { scan(requestPermission) }
    }

    fun requestPermission() {
        scope.launch {
            permissionRequested = null
            scan(requestPermission = true)
        }
    }

    private fun findDevice(printer: UsbPrinter): UsbDevice? =
        usbManager.deviceList[printer.deviceName]?.takeIf { it.matches(printer) }

    private fun UsbDevice.matches(printer: UsbPrinter): Boolean =
        deviceName == printer.deviceName && vendorId == printer.vendorId && productId == printer.productId

    // The receivers and refresh calls run here on main; only the interface probe blocks on I/O.
    private fun scan(requestPermission: Boolean, detached: String? = null) {
        val devices = usbManager.deviceList
        activeConnection?.let { conn ->
            val p = conn.printer
            if (p.deviceName == detached || devices[p.deviceName]?.matches(p) != true) conn.markDisconnected()
        }
        if (permissionRequested == detached || permissionRequested?.let { it !in devices } == true) {
            permissionRequested = null
        }
        val current = (_state.value as? PrinterState.Connected)?.printer
        val attached = devices.values.filter { it.deviceName != detached }
        val device = attached.firstOrNull { current != null && it.matches(current) }
            ?: attached.firstOrNull(UsbPrinterClass::hasPrinterInterface)
        if (probingDevice != device?.deviceName) {
            probeJob?.cancel()
            probingDevice = null
        }
        if (device == null) {
            if (current != null) {
                _state.value = PrinterState.Disconnected
                reconnectMessageJob = scope.launch {
                    delay(10_000)
                    _state.compareAndSet(PrinterState.Disconnected, PrinterState.Searching)
                }
            } else if (_state.value != PrinterState.Disconnected) {
                _state.value = PrinterState.Searching
            }
            return
        }
        reconnectMessageJob?.cancel()
        reconnectMessageJob = null
        val label = device.productName ?: device.manufacturerName ?: "USB printer"
        if (!usbManager.hasPermission(device)) {
            _state.value = PrinterState.NeedsPermission(label)
            if (requestPermission && permissionRequested != device.deviceName) {
                permissionRequested = device.deviceName
                askPermission(device)
            }
            return
        }
        if (current != null && device.matches(current)) return
        if (probingDevice == device.deviceName && probeJob?.isActive == true) return
        probingDevice = device.deviceName
        _state.value = PrinterState.Identifying(label)
        probeJob = scope.launch {
            val printer = withContext(Dispatchers.IO) {
                synchronized(interfaceLock) {
                    try {
                        probe(device)
                    } catch (_: SecurityException) {
                        // The attachment or its permission may have disappeared during the probe.
                        null
                    }
                }
            }
            if (printer != null && findDevice(printer)?.let(usbManager::hasPermission) == true) {
                _state.value = PrinterState.Connected(printer)
            } else {
                _state.value = PrinterState.Searching
            }
        }
    }

    private fun askPermission(device: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        usbManager.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent, flags))
    }

    private fun probe(device: UsbDevice): UsbPrinter? {
        val c = UsbPrinterClass.findPrinterInterface(device) ?: return null
        var deviceId: DeviceId? = null
        var status: PortStatus? = null
        usbManager.openDevice(device)?.let { conn ->
            try {
                if (conn.claimInterface(c.iface, true)) {
                    try {
                        deviceId = UsbPrinterClass.getDeviceId(conn, c.iface)
                        status = UsbPrinterClass.getPortStatus(conn, c.iface)
                    } finally {
                        conn.releaseInterface(c.iface)
                    }
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

    /** Opens the exact current USB attachment; only an explicit retry may choose a reconnected device. */
    fun open(printer: UsbPrinter): UsbPrinterConnection = synchronized(interfaceLock) {
        fun isAttached(): Boolean = (findDevice(printer) != null).also { if (!it) refresh(requestPermission = false) }
        val device = findDevice(printer) ?: run {
            refresh(requestPermission = false)
            throw PrinterDisconnectedException()
        }
        if (!usbManager.hasPermission(device)) throw SecurityException("No permission for USB printer")
        val conn = UsbPrinterConnection.open(usbManager, device, printer, ::isAttached)
        activeConnection = conn
        try {
            conn.ensureAttached()
        } catch (e: PrinterDisconnectedException) {
            conn.close()
            throw e
        }
        conn
    }

    companion object {
        const val ACTION_PERMISSION = "com.usbprint.USB_PERMISSION"
    }
}
