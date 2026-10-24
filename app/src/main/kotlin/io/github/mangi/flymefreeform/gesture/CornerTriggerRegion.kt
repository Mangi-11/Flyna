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
        // 弧长以圆角中点为中心展开；再大的范围也不会延伸到底边或侧边直线段。
        val halfAngle = min(PI / 4, arcLength / (2.0 * radius))
        val halfDegrees = Math.toDegrees(halfAngle).toFloat()
        return CornerArcBand(centerX, centerY, radius, (radius - thickness).coerceAtLeast(0f),
            (if (side == CornerSide.Left) 135f else 45f) - halfDegrees, halfDegrees * 2f)
    }
}

/** 预览绘制与触摸命中共用的环形扇区，角度沿屏幕坐标顺时针增加。 */
internal data class CornerArcBand(
    val centerX: Float,
    val centerY: Float,
    val outerRadius: Float,
    val innerRadius: Float,
    val startAngle: Float,
    val sweepAngle: Float,
) {
    fun contains(x: Float, y: Float): Boolean {
        val dx = x - centerX
        val dy = y - centerY
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared < innerRadius * innerRadius || distanceSquared > outerRadius * outerRadius) return false
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        return angle >= startAngle && angle <= startAngle + sweepAngle
    }
}

/** 两个实际屏幕圆角内侧的窄弧带；所有输入入口和接收端共用此几何事实。 */
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
        if (leftEnabled && left?.band(CornerSide.Left, thickness, arcLength)?.contains(x, y) == true) return CornerSide.Left
        if (rightEnabled && right?.band(CornerSide.Right, thickness, arcLength)?.contains(x, y) == true) return CornerSide.Right
        return null
    }

    /** 当前显示圆角对应的四分之一圆弧长上限（dp），避免滑块高段超出圆角范围后无响应。 */
    fun maxArcLengthDp(density: Float): Int {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: return ModulePreferences.MAX_CORNER_ARC_LENGTH_DP
        val maxQuarterArcLength = maxOf(left?.quarterArcLength ?: 0f, right?.quarterArcLength ?: 0f)
        if (maxQuarterArcLength <= 0f) return ModulePreferences.MAX_CORNER_ARC_LENGTH_DP
        return ceil(maxQuarterArcLength / safeDensity).toInt().coerceIn(
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
