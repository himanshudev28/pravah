package dev.pravah.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pravah.app.data.CurrentPoint
import dev.pravah.app.ui.theme.pravah
import kotlin.math.max
import kotlin.math.min

private const val WINDOW_MS = 10 * 60 * 1000L

/**
 * Live current chart. Zero baseline; area above = charging, below = draining.
 * Single reused Path, no per-frame allocation beyond the draw pass.
 */
@Composable
fun CurrentChart(points: List<CurrentPoint>, color: Color, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    val area = remember { Path() }
    val hairline = MaterialTheme.pravah.hairline

    val lo = remember(points) { min(0f, points.minOfOrNull { it.currentMa } ?: 0f) }
    val hi = remember(points) { max(0f, points.maxOfOrNull { it.currentMa } ?: 0f) }

    Column(modifier) {
        if (points.size < 2) {
            Text(
                "Collecting samples…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.pravah.dim,
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 48.dp),
            )
        } else {
            Canvas(
                Modifier.fillMaxWidth().height(120.dp).semantics {
                    contentDescription =
                        "Current over the last 10 minutes, from ${signed(lo.toDouble())} to ${signed(hi.toDouble())} milliamps"
                },
            ) {
                val pad = 6.dp.toPx()
                val span = max(hi - lo, 50f)
                val top = hi + (span - (hi - lo)) / 2f
                fun y(v: Float) = pad + (top - v) / span * (size.height - 2 * pad)
                val tEnd = points.last().tMillis
                val tStart = tEnd - WINDOW_MS
                fun x(t: Long) = ((t - tStart).toFloat() / WINDOW_MS).coerceIn(0f, 1f) * size.width

                val zeroY = y(0f)
                path.reset(); area.reset()
                points.forEachIndexed { i, p ->
                    val px = x(p.tMillis); val py = y(p.currentMa)
                    if (i == 0) { path.moveTo(px, py); area.moveTo(px, zeroY); area.lineTo(px, py) }
                    else { path.lineTo(px, py); area.lineTo(px, py) }
                }
                area.lineTo(x(tEnd), zeroY); area.close()

                clipRect(top = 0f, bottom = zeroY) { drawPath(area, color.copy(alpha = 0.16f)) }
                clipRect(top = zeroY, bottom = size.height) { drawPath(area, color.copy(alpha = 0.16f)) }
                drawLine(hairline, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
                drawPath(path, color, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangementSpace()) {
            Text("10 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.pravah.dim)
            if (points.size >= 2) {
                Text(
                    "${signed(lo.toDouble())} to ${signed(hi.toDouble())} mA",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.pravah.dim,
                )
            }
            Text("now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.pravah.dim)
        }
    }
}

private fun horizontalArrangementSpace() = androidx.compose.foundation.layout.Arrangement.SpaceBetween
