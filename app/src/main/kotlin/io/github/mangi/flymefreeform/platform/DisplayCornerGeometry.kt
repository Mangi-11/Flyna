package io.github.mangi.flymefreeform.platform

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.RoundedCorner
import io.github.mangi.flymefreeform.gesture.CornerArc
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion

/** 使用显示坐标，避免小输入窗口的 WindowInsets 裁掉或平移圆角信息。仅在配置更新或 DOWN 读取。 */
internal object DisplayCornerGeometry {
    @Suppress("DEPRECATION")
    fun read(context: Context, arcLengthDp: Int, arcWidthDp: Int): CornerTriggerRegion? {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = Point()
        display.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) return null
        fun corner(position: Int): CornerArc? {
            val value = display.getRoundedCorner(position) ?: return null
            val center = value.center
            if (value.radius <= 0 || center.x !in 0..size.x || center.y !in 0..size.y) return null
            return CornerArc(center.x.toFloat(), center.y.toFloat(), value.radius.toFloat())
        }
        val left = corner(RoundedCorner.POSITION_BOTTOM_LEFT)
        val right = corner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        if (left == null && right == null) return null
        return CornerTriggerRegion.create(
            size.x.toFloat(), size.y.toFloat(), left, right, arcLengthDp, context.resources.displayMetrics.density, arcWidthDp,
        )
    }
}
