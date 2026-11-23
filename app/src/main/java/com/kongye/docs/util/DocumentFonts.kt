package com.kongye.docs.util

import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/** Some Xiaomi font configs map 600 and 700 to the same, lighter variable axis. */
internal object DocumentFonts {
    private fun miSans(weight: Int,axis: Int): Typeface? {
        if(!Build.MANUFACTURER.equals("Xiaomi",true) && !Build.MANUFACTURER.equals("Redmi",true)) return null
        val file=File("/system/fonts/MiSansVF.ttf")
        if(!file.isFile) return null
        return runCatching { Typeface.Builder(file).setFontVariationSettings("'wght' $axis").setWeight(weight).build() }.getOrNull()
    }
    // MiSans axis values are not CSS weights. Keep a modest distinction from the system's
    // 450-axis bold; the previous 600-axis correction was too heavy in everyday documents.
    private val correctedBold by lazy { miSans(700,520) }
    private val correctedSemiBold by lazy { miSans(600,480) }
    val boldFamily: FontFamily? by lazy { correctedBold?.let { FontFamily(it) } }
    val semiBoldFamily: FontFamily? by lazy { correctedSemiBold?.let { FontFamily(it) } }
    val nativeBold: Typeface get()=correctedBold ?: Typeface.DEFAULT_BOLD
    val nativeSemiBold: Typeface get()=correctedSemiBold ?: if(Build.VERSION.SDK_INT>=28) Typeface.create(Typeface.DEFAULT,600,false) else Typeface.DEFAULT_BOLD
    internal val hasCorrection get()=correctedBold!=null
}
