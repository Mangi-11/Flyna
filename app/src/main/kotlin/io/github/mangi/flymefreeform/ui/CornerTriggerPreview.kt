package io.github.mangi.flymefreeform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.gesture.CornerArc
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.platform.DisplayCornerGeometry
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 只在拖动时绘制，不接收输入；草稿值在 draw 阶段读取，不驱动整个设置页重组。 */
@Composable
internal fun CornerTriggerPreview(
    lengthPreview: State<Int?>,
    widthPreview: State<Int?>,
    confirmedLengthDp: Int,
    confirmedWidthDp: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val geometry = remember(context, configuration) {
        DisplayCornerGeometry.read(context, ModulePreferences.DEFAULT_CORNER_ARC_LENGTH_DP,
            ModulePreferences.DEFAULT_CORNER_ARC_WIDTH_DP)
    } ?: return
    val pixelsPerDp = geometry.thickness / ModulePreferences.DEFAULT_CORNER_ARC_WIDTH_DP
    val activeColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f)
    val inactiveColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)
    Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val draftLength = lengthPreview.value
        val draftWidth = widthPreview.value
        if (draftLength == null && draftWidth == null) return@Canvas
        val region = CornerTriggerRegion.create(
            geometry.displayWidth, geometry.displayHeight, geometry.left, geometry.right,
            draftLength ?: confirmedLengthDp, pixelsPerDp, draftWidth ?: confirmedWidthDp,
        )
        // 全屏时为实际尺寸；自由窗等较小宿主等比缩放，不按不同轴拉伸圆角。
        val scale = minOf(size.width / region.displayWidth, size.height / region.displayHeight)
        val origin = Offset((size.width - region.displayWidth * scale) / 2f, size.height - region.displayHeight * scale)
        fun drawBand(arc: CornerArc?, side: CornerSide, enabled: Boolean) {
            if (arc == null) return
            val band = arc.band(side, region.thickness, region.arcLength)
            val radius = (band.outerRadius + band.innerRadius) * 0.5f * scale
            val width = (band.outerRadius - band.innerRadius) * scale
            if (radius <= 0f || width <= 0f) return
            drawArc(
                color = if (enabled) activeColor else inactiveColor,
                startAngle = band.startAngle,
                sweepAngle = band.sweepAngle,
                useCenter = false,
                topLeft = origin + Offset(band.centerX * scale - radius, band.centerY * scale - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = width, cap = StrokeCap.Butt),
            )
        }
        drawBand(region.left, CornerSide.Left, leftEnabled)
        drawBand(region.right, CornerSide.Right, rightEnabled)
    }
}
