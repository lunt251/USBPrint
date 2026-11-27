package com.usbprint.app.ui.home

import androidx.annotation.DrawableRes
import com.usbprint.app.R
import com.usbprint.core.usb.Brand

// Static references keep the bundled vectors available when resources are shrunk.
@DrawableRes
internal fun brandLogoResource(brand: Brand): Int? = when (brand) {
    Brand.BIXOLON -> R.drawable.brand_bixolon
    Brand.BRADY -> R.drawable.brand_brady
    Brand.BROTHER -> R.drawable.brand_brother
    Brand.CANON -> R.drawable.brand_canon
    Brand.CITIZEN -> R.drawable.brand_citizen
    Brand.DELL -> R.drawable.brand_dell
    Brand.DYMO -> R.drawable.brand_dymo
    Brand.EPSON -> R.drawable.brand_epson
    Brand.HP -> R.drawable.brand_hp
    Brand.KONICA_MINOLTA -> R.drawable.brand_konica_minolta
    Brand.KYOCERA -> R.drawable.brand_kyocera
    Brand.LEXMARK -> R.drawable.brand_lexmark
    Brand.OKI -> R.drawable.brand_oki
    Brand.RICOH -> R.drawable.brand_ricoh
    Brand.SAMSUNG -> R.drawable.brand_samsung
    Brand.SHARP -> R.drawable.brand_sharp
    Brand.TOSHIBA -> R.drawable.brand_toshiba
    Brand.TSC -> R.drawable.brand_tsc
    Brand.XEROX -> R.drawable.brand_xerox
    else -> null
}
