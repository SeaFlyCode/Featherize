package com.featherize.app.ui.component

import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 o"
    val units = arrayOf("o", "Ko", "Mo", "Go")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return if (digitGroups == 0) "$bytes o" else "%.1f %s".format(value, units[digitGroups])
}

/** [ratio] is 1 - compressed/original: positive means the file shrank, negative means it grew. */
fun formatSavings(ratio: Float): String {
    val percent = kotlin.math.abs(ratio * 100).toInt()
    return if (ratio >= 0) "-$percent%" else "+$percent%"
}
