package dev.pravah.app.ui.components

import android.content.Context
import android.text.format.DateFormat
import dev.pravah.app.data.BatteryTelemetry.PlugType
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val MINUS = "−"

/** Signed number with a real minus sign. [plus] adds "+" for positives. */
fun signed(v: Double, decimals: Int = 0, plus: Boolean = true): String {
    val body = String.format(Locale.getDefault(), "%,.${decimals}f", abs(v))
    val zero = body.all { it == '0' || it == '.' || it == ',' }
    return when {
        zero -> body
        v < 0 -> MINUS + body
        plus -> "+$body"
        else -> body
    }
}

fun duration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m m"
    }
}

fun clockTime(context: Context, millis: Long): String =
    DateFormat.getTimeFormat(context).format(Date(millis))

fun plugLabel(type: PlugType): String = when (type) {
    PlugType.NONE -> "Unplugged"
    PlugType.AC -> "AC"
    PlugType.USB -> "USB"
    PlugType.WIRELESS -> "Wireless"
    PlugType.DOCK -> "Dock"
}

fun plugLabel(name: String): String =
    runCatching { plugLabel(PlugType.valueOf(name)) }.getOrDefault("Unknown")

fun temperature(celsius: Float, fahrenheit: Boolean): String =
    if (fahrenheit) "${(celsius * 9f / 5f + 32f).roundToInt()}°F"
    else String.format(Locale.getDefault(), "%.1f°C", celsius)

fun mah(v: Double): String = String.format(Locale.getDefault(), "%,d mAh", v.roundToInt())
