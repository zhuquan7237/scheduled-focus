package com.zhuquan.scheduledfocus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun FocusNavIcon(index: Int) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (index) {
            0 -> {
                drawCircle(color, 9 * unit, point(12f, 12f), style = stroke)
                drawLine(color, point(12f, 6f), point(12f, 12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, point(12f, 12f), point(16f, 14f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            1 -> {
                drawRoundRect(color, point(3f, 5f), Size(18 * unit, 16 * unit), CornerRadius(2 * unit), style = stroke)
                for (x in listOf(8f, 16f)) drawLine(color, point(x, 3f), point(x, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, point(3f, 10f), point(21f, 10f), stroke.width)
                for (x in listOf(8f, 12f, 16f)) drawCircle(color, unit, point(x, 15f))
            }
            2 -> {
                drawRoundRect(color, point(3f, 3f), Size(18 * unit, 18 * unit), CornerRadius(5 * unit), style = stroke)
                val path = Path().apply { moveTo(7 * unit, 12 * unit); lineTo(10.5f * unit, 15.5f * unit); lineTo(17 * unit, 9 * unit) }
                drawPath(path, color, style = stroke)
            }
            else -> {
                val path = Path().apply { moveTo(12 * unit, 2 * unit); lineTo(20 * unit, 5 * unit); lineTo(20 * unit, 12 * unit); quadraticTo(20 * unit, 18 * unit, 12 * unit, 22 * unit); quadraticTo(4 * unit, 18 * unit, 4 * unit, 12 * unit); lineTo(4 * unit, 5 * unit); close() }
                drawPath(path, color, style = stroke)
                drawLine(color, point(9f, 12f), point(11f, 14f), stroke.width, StrokeCap.Round)
                drawLine(color, point(11f, 14f), point(15f, 10f), stroke.width, StrokeCap.Round)
            }
        }
    }
}
