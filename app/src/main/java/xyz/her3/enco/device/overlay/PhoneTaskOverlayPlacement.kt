package xyz.her3.enco.device.overlay

import kotlin.math.roundToInt

internal enum class OverlayDockSide {
    LEFT,
    RIGHT,
}

internal data class OverlaySafeArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Float get() = (left + right) / 2f
}

internal data class OverlayOffset(
    val x: Int,
    val y: Int,
)

internal data class OverlayDock(
    val side: OverlayDockSide,
    val verticalFraction: Float,
)

internal object PhoneTaskOverlayPlacement {
    fun clamp(
        area: OverlaySafeArea,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): OverlayOffset =
        OverlayOffset(
            x = if (width >= area.width) area.left else x.coerceIn(area.left, area.right - width),
            y = if (height >= area.height) area.top else y.coerceIn(area.top, area.bottom - height),
        )

    fun release(
        area: OverlaySafeArea,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): OverlayDock {
        val clamped = clamp(area, width, height, x, y)
        val availableY = (area.height - height).coerceAtLeast(0)
        return OverlayDock(
            side = if (clamped.x + width / 2f < area.centerX) OverlayDockSide.LEFT else OverlayDockSide.RIGHT,
            verticalFraction = if (availableY == 0) 0f else (clamped.y - area.top).toFloat() / availableY,
        )
    }

    fun place(
        area: OverlaySafeArea,
        width: Int,
        height: Int,
        dock: OverlayDock,
    ): OverlayOffset {
        val availableY = (area.height - height).coerceAtLeast(0)
        val x =
            when {
                width >= area.width -> area.left
                dock.side == OverlayDockSide.LEFT -> area.left
                else -> area.right - width
            }
        val y = area.top + (availableY * dock.verticalFraction.coerceIn(0f, 1f)).roundToInt()
        return clamp(area, width, height, x, y)
    }
}
