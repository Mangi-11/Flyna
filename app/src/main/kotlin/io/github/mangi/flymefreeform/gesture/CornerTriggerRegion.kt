package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.ModulePreferences
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.min

/** 系统圆角在物理显示坐标中的四分之一圆近似，不包含 Android 对象。 */
internal data class CornerArc(val centerX: Float, val centerY: Float, val radius: Float) {
    val quarterArcLength: Float
        get() = (radius * (PI / 2.0)).toFloat()

    fun band(side: CornerSide, thickness: Float, arcLength: Float): CornerArcBand {
        // 圆弧填满后，剩余长度沿两端切线均分，保持触发带宽度不变。
        val halfAngle = min(PI / 4, arcLength / (2.0 * radius))
        val halfDegrees = Math.toDegrees(halfAngle).toFloat()
        return CornerArcBand(centerX, centerY, radius, (radius - thickness).coerceAtLeast(0f),
            (if (side == CornerSide.Left) 135f else 45f) - halfDegrees, halfDegrees * 2f,
            side, ((arcLength - quarterArcLength) / 2f).coerceAtLeast(0f))
    }
}

/** 预览与命中共用的弧带及切线延伸段，角度沿屏幕坐标顺时针增加。 */
internal data class CornerArcBand(
    val centerX: Float,
    val centerY: Float,
    val outerRadius: Float,
    val innerRadius: Float,
    val startAngle: Float,
    val sweepAngle: Float,
    val side: CornerSide,
    val extensionLength: Float,
) {
    val bounds: CornerInputBounds
        get() = CornerInputBounds(
            left = if (side == CornerSide.Left) centerX - outerRadius else centerX - extensionLength,
            top = centerY - extensionLength,
            right = if (side == CornerSide.Left) centerX + extensionLength else centerX + outerRadius,
            bottom = centerY + outerRadius,
        )

    fun contains(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val dx = x - centerX
        val dy = y - centerY
        if (extensionLength > 0f) {
            val direction = if (side == CornerSide.Left) -1f else 1f
            val innerX = centerX + direction * innerRadius
            val outerX = centerX + direction * outerRadius
            val bottomEndX = centerX - direction * extensionLength
            if (x in minOf(innerX, outerX)..maxOf(innerX, outerX) &&
                y in (centerY - extensionLength)..centerY) return true
            if (x in minOf(centerX, bottomEndX)..maxOf(centerX, bottomEndX) &&
                y in (centerY + innerRadius)..(centerY + outerRadius)) return true
        }
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared < innerRadius * innerRadius || distanceSquared > outerRadius * outerRadius) return false
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        return angle >= startAngle && angle <= startAngle + sweepAngle
    }
}

internal data class CornerInputBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** 两个实际屏幕圆角内侧的贴边触发带；所有输入入口和接收端共用此几何事实。 */
internal data class CornerTriggerRegion(
    val displayWidth: Float,
    val displayHeight: Float,
    val left: CornerArc?,
    val right: CornerArc?,
    val thickness: Float,
    val arcLength: Float,
) {
    fun detectSide(x: Float, y: Float, leftEnabled: Boolean, rightEnabled: Boolean): CornerSide? {
        if (!x.isFinite() || !y.isFinite() || x < 0f || x > displayWidth || y < 0f || y > displayHeight) return null
        if (leftEnabled && band(CornerSide.Left)?.contains(x, y) == true) return CornerSide.Left
        if (rightEnabled && band(CornerSide.Right)?.contains(x, y) == true) return CornerSide.Right
        return null
    }

    fun band(side: CornerSide, width: Float = thickness, length: Float = arcLength): CornerArcBand? {
        val arc = (if (side == CornerSide.Left) left else right) ?: return null
        return arc.band(side, width, length.coerceAtMost(maxLength(arc, side)))
    }

    private fun maxLength(arc: CornerArc, side: CornerSide): Float {
        // 两侧最多延伸到各自半屏，防止窄显示上的热区互相交叠。
        val bottomSpace = if (side == CornerSide.Left) displayWidth / 2f - arc.centerX else arc.centerX - displayWidth / 2f
        val sideSpace = arc.centerY - displayHeight / 2f
        return arc.quarterArcLength + 2f * min(bottomSpace, sideSpace).coerceAtLeast(0f)
    }

    /** 按当前显示可容纳的弧带长度限制滑块，包含两端的直线延伸段。 */
    fun maxArcLengthDp(density: Float): Int {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: return ModulePreferences.MAX_CORNER_ARC_LENGTH_DP
        val maxAvailableLength = maxOf(
            left?.let { maxLength(it, CornerSide.Left) } ?: 0f,
            right?.let { maxLength(it, CornerSide.Right) } ?: 0f,
        )
        if (maxAvailableLength <= 0f) return ModulePreferences.MAX_CORNER_ARC_LENGTH_DP
        return ceil(maxAvailableLength / safeDensity).toInt().coerceIn(
            ModulePreferences.MIN_CORNER_ARC_LENGTH_DP,
            ModulePreferences.MAX_CORNER_ARC_LENGTH_DP,
        )
    }

    companion object {
        fun create(
            width: Float,
            height: Float,
            left: CornerArc?,
            right: CornerArc?,
            rangeDp: Int,
            density: Float,
            widthDp: Int = ModulePreferences.DEFAULT_CORNER_ARC_WIDTH_DP,
        ): CornerTriggerRegion {
            val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
            return CornerTriggerRegion(
                width, height, left, right, ModulePreferences.coerceCornerArcWidthDp(widthDp) * safeDensity,
                ModulePreferences.coerceCornerArcLengthDp(rangeDp) * safeDensity,
            )
        }
    }
}
