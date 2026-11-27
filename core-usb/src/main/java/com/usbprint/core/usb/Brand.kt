package com.usbprint.core.usb

/** Printer manufacturers with a dedicated brand logo. Everything else falls back to text. */
enum class Brand(val displayName: String, private val aliases: List<String>) {
    HP("HP", listOf("hp", "hewlett-packard", "hewlett packard")),
    CANON("Canon", listOf("canon")),
    EPSON("Epson", listOf("epson", "seiko epson")),
    BROTHER("Brother", listOf("brother")),
    SAMSUNG("Samsung", listOf("samsung")),
    XEROX("Xerox", listOf("xerox", "fuji xerox", "fujifilm")),
    LEXMARK("Lexmark", listOf("lexmark")),
    KYOCERA("Kyocera", listOf("kyocera")),
    RICOH("Ricoh", listOf("ricoh")),
    OKI("OKI", listOf("oki", "okidata")),
    DELL("Dell", listOf("dell")),
    ZEBRA("Zebra", listOf("zebra")),
    PANTUM("Pantum", listOf("pantum")),
    KONICA_MINOLTA("Konica Minolta", listOf("konica", "minolta")),
    SHARP("Sharp", listOf("sharp")),
    TOSHIBA("Toshiba", listOf("toshiba")),
    DYMO("Dymo", listOf("dymo")),
    STAR("Star Micronics", listOf("star micronics", "star")),
    BIXOLON("Bixolon", listOf("bixolon")),
    CITIZEN("Citizen", listOf("citizen")),
    XPRINTER("Xprinter", listOf("xprinter")),
    TSC("TSC", listOf("tsc")),
    GODEX("Godex", listOf("godex")),
    BRADY("Brady", listOf("brady")),
    OTHER("", emptyList());

    companion object {
        fun from(vararg names: String?): Brand {
            for (name in names) {
                val n = name?.lowercase()?.trim() ?: continue
                if (n.isEmpty()) continue
                entries.firstOrNull { b -> b.aliases.any { n == it || n.startsWith("$it ") || n.startsWith(it) } }
                    ?.let { return it }
            }
            return OTHER
        }
    }
}
