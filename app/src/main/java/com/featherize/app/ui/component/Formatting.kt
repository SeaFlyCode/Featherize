package com.featherize.app.ui.component

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 o"
    val units = arrayOf("o", "Ko", "Mo", "Go")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    // The rest of the UI is hardcoded French text, not locale-aware — formatting this with the
    // device's default locale would silently mismatch it (comma decimal separator, or even
    // non-Latin digits on some locales) instead of the "." used everywhere else in the app.
    return if (digitGroups == 0) "$bytes o" else "%.1f %s".format(Locale.US, value, units[digitGroups])
}

/** [ratio] is 1 - compressed/original: positive means the file shrank, negative means it grew. */
fun formatSavings(ratio: Float): String {
    val percent = kotlin.math.abs(ratio * 100).toInt()
    return if (ratio >= 0) "-$percent%" else "+$percent%"
}
