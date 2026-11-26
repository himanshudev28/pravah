package dev.pravah.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Thin horizontal charge bar. A tick marks the charge limit when the phone stops early
 * (battery protection) — the unreachable part of the track is faded — otherwise 80 %
 * (where charging slows). A taller marker shows the alarm target when enabled.
 */
@Composable
fun ChargeBar(
    percent: Int,
    fill: Color,
    track: Color,
    tick: Color,
    alarmPercent: Int?,
    modifier: Modifier = Modifier,
    limitPercent: Int? = null,
) {
    val frac by animateFloatAsState(
        percent.coerceIn(0, 100) / 100f,
        tween(300, easing = FastOutSlowInEasing),
        label = "bar",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics {
                contentDescription = "Battery $percent percent" +
                    (limitPercent?.let { ", charging stops at $it percent" } ?: "") +
                    (alarmPercent?.let { ", alarm at $it percent" } ?: "")
            },
    ) {
        val h = 6.dp.toPx()
        val top = (size.height - h) / 2f
        val r = CornerRadius(h / 2f)
        val limitFrac = limitPercent?.coerceIn(1, 100)?.div(100f)
        if (limitFrac != null && limitFrac < 1f) {
            // reachable part solid, the part above the limit faded
            drawRoundRect(track.copy(alpha = track.alpha * 0.45f), Offset(0f, top), Size(size.width, h), r)
            drawRoundRect(track, Offset(0f, top), Size(size.width * limitFrac, h), r)
        } else drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
        if (frac > 0f) drawRoundRect(fill, Offset(0f, top), Size(maxOf(size.width * frac, h), h), r)
        val xt = size.width * (limitFrac?.takeIf { it < 1f } ?: 0.8f)
        drawLine(tick, Offset(xt, top - 3.dp.toPx()), Offset(xt, top + h + 3.dp.toPx()), 1.5.dp.toPx())
        if (alarmPercent != null) {
            val xa = size.width * alarmPercent.coerceIn(0, 100) / 100f
            drawLine(fill, Offset(xa, 0f), Offset(xa, size.height), 2.5.dp.toPx())
        }
    }
}
