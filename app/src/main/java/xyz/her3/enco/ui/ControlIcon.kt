package xyz.her3.enco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import xyz.her3.enco.ui.theme.LocalEncoColors

internal enum class ControlSymbol {
    PHONE,
    LINK,
    LAYERS,
    CAPTURE,
    BACK,
    HOME,
    CLOSE,
    CHECK,
    NEXT,
    CHAT,
    SEND,
    STOP,
}

@Composable
internal fun ControlIcon(
    symbol: ControlSymbol,
    modifier: Modifier = Modifier,
    color: Color = LocalEncoColors.current.text,
) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        val stroke = 1.7f * unit

        fun point(
            x: Int,
            y: Int,
        ) = Offset(x * unit, y * unit)

        fun line(
            x: Int,
            y: Int,
            toX: Int,
            toY: Int,
        ) {
            drawLine(color, point(x, y), point(toX, toY), stroke, StrokeCap.Round)
        }

        fun rectangle(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            radius: Int,
        ) {
            drawRoundRect(
                color,
                topLeft = point(x, y),
                size = Size(width * unit, height * unit),
                cornerRadius = CornerRadius(radius * unit),
                style = Stroke(stroke),
            )
        }
        when (symbol) {
            ControlSymbol.PHONE -> {
                rectangle(6, 2, 12, 20, 3)
                line(10, 18, 14, 18)
                drawCircle(color, 1.4f * unit, point(12, 10))
            }

            ControlSymbol.LINK -> {
                rectangle(2, 8, 10, 8, 4)
                rectangle(12, 8, 10, 8, 4)
                line(8, 12, 16, 12)
            }

            ControlSymbol.LAYERS -> {
                rectangle(8, 4, 12, 12, 2)
                line(4, 8, 4, 20)
                line(4, 20, 16, 20)
            }

            ControlSymbol.CAPTURE -> {
                line(3, 8, 3, 3)
                line(3, 3, 8, 3)
                line(16, 3, 21, 3)
                line(21, 3, 21, 8)
                line(21, 16, 21, 21)
                line(21, 21, 16, 21)
                line(8, 21, 3, 21)
                line(3, 21, 3, 16)
                rectangle(8, 8, 8, 8, 1)
            }

            ControlSymbol.BACK -> {
                line(20, 12, 4, 12)
                line(4, 12, 10, 6)
                line(4, 12, 10, 18)
            }

            ControlSymbol.HOME -> {
                line(3, 11, 12, 3)
                line(12, 3, 21, 11)
                line(6, 10, 6, 20)
                line(6, 20, 18, 20)
                line(18, 20, 18, 10)
            }

            ControlSymbol.CLOSE -> {
                line(6, 6, 18, 18)
                line(6, 18, 18, 6)
            }

            ControlSymbol.CHECK -> {
                line(5, 12, 10, 17)
                line(10, 17, 19, 7)
            }

            ControlSymbol.NEXT -> {
                line(9, 5, 16, 12)
                line(16, 12, 9, 19)
            }

            ControlSymbol.CHAT -> {
                rectangle(3, 4, 18, 13, 4)
                line(7, 17, 7, 21)
                line(7, 21, 12, 17)
                line(8, 10, 16, 10)
            }

            ControlSymbol.SEND -> {
                line(12, 20, 12, 4)
                line(6, 10, 12, 4)
                line(12, 4, 18, 10)
            }

            ControlSymbol.STOP -> {
                rectangle(6, 6, 12, 12, 2)
            }
        }
    }
}
