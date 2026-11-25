package com.usbprint.core.usb

/**
 * IEEE 1284 Device ID, as returned by the USB printer class GET_DEVICE_ID request.
 * Format: `KEY:value;KEY:value;...` e.g. `MFG:HP;MDL:LaserJet 1020;CMD:ZJS,PJL;`.
 */
data class DeviceId(val raw: String, val fields: Map<String, String>) {

    val manufacturer: String? get() = first("MFG", "MANUFACTURER", "MFR")
    val model: String? get() = first("MDL", "MODEL")
    val description: String? get() = first("DES", "DESCRIPTION")
    val serial: String? get() = first("SN", "SERN", "SERIALNUMBER")
    val deviceClass: String? get() = first("CLS", "CLASS")

    /** Printer languages, e.g. [PCL, PJL, POSTSCRIPT, URF, ESCPL2]. */
    val commandSet: List<String>
        get() = first("CMD", "COMMAND SET")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun supports(language: String): Boolean = commandSet.any { it.equals(language, ignoreCase = true) }

    private fun first(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { fields[it] }?.takeIf { it.isNotBlank() }

    companion object {
        fun parse(raw: String): DeviceId {
            val fields = LinkedHashMap<String, String>()
            for (part in raw.split(';')) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                val key = part.substring(0, colon).trim().uppercase()
                val value = part.substring(colon + 1).trim()
                if (key.isNotEmpty() && key !in fields) fields[key] = value
            }
            return DeviceId(raw.trim(), fields)
        }

        /**
         * Parses a raw GET_DEVICE_ID response. The first two bytes are a big-endian length that
         * includes themselves; some printers get it wrong, so it is clamped to what was received.
         */
        fun fromResponse(buffer: ByteArray, received: Int): DeviceId? {
            if (received <= 2) return null
            val declared = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            val end = if (declared in 3..received) declared else received
            val text = String(buffer, 2, end - 2, Charsets.ISO_8859_1).trimEnd('\u0000')
            return if (text.contains(':')) parse(text) else null
        }
    }
}
