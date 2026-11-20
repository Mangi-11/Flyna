package io.github.mangi.flyna.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.flyna.config.ModulePreferences
import io.github.mangi.flyna.gesture.CornerArc
import io.github.mangi.flyna.gesture.CornerArcBand
import io.github.mangi.flyna.gesture.CornerSide
import io.github.mangi.flyna.gesture.CornerTriggerRegion
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class CornerPreviewDimension {
    Length,
    Width,
}

@Stable
internal class CornerTriggerPreviewState(
    initialLengthDp: Int,
    initialWidthDp: Int,
) {
    var lengthDp by mutableIntStateOf(initialLengthDp)
        private set
    var widthDp by mutableIntStateOf(initialWidthDp)
        private set
    var activeDimension by mutableStateOf<CornerPreviewDimension?>(null)
        private set
    var isAdjustingLength by mutableStateOf(false)
        private set
    var isAdjustingWidth by mutableStateOf(false)
        private set
    var releaseGeneration by mutableIntStateOf(0)
        private set

    val isActive: Boolean
        get() = isAdjustingLength || isAdjustingWidth

    fun syncConfirmed(confirmedLengthDp: Int, confirmedWidthDp: Int) {
        if (!isAdjustingLength) lengthDp = confirmedLengthDp
        if (!isAdjustingWidth) widthDp = confirmedWidthDp
    }

    fun startLengthPreview(draftDp: Int) {
        lengthDp = draftDp
        activeDimension = CornerPreviewDimension.Length
        isAdjustingLength = true
    }

    fun updateLengthPreview(draftDp: Int) {
        lengthDp = draftDp
        activeDimension = CornerPreviewDimension.Length
        isAdjustingLength = true
    }

    fun finishLengthPreview() {
        if (!isAdjustingLength) return
        isAdjustingLength = false
        if (isAdjustingWidth) {
            activeDimension = CornerPreviewDimension.Width
        } else {
            releaseGeneration++
        }
    }

    fun startWidthPreview(draftDp: Int) {
        widthDp = draftDp
        activeDimension = CornerPreviewDimension.Width
        isAdjustingWidth = true
    }

    fun updateWidthPreview(draftDp: Int) {
        widthDp = draftDp
        activeDimension = CornerPreviewDimension.Width
        isAdjustingWidth = true
    }

    fun finishWidthPreview() {
        if (!isAdjustingWidth) return
        isAdjustingWidth = false
        if (isAdjustingLength) {
            activeDimension = CornerPreviewDimension.Length
        } else {
            releaseGeneration++
        }
    }

    fun dismiss() {
        isAdjustingLength = false
        isAdjustingWidth = false
        activeDimension = null
        releaseGeneration = 0
    }
}

/** 圆角环形扇区的圆弧切角几何，保证外弧贴合屏幕圆角、四角平滑相切。 */
internal data class CornerBandContour(
    val centerX: Float,
    val centerY: Float,
    val innerRadius: Float,
    val outerRadius: Float,
    val startAngle: Float,
    val sweepAngle: Float,
    val filletRadius: Float,
    val outerCornerAngleOffset: Float,
    val innerCornerAngleOffset: Float,
    val side: CornerSide,
    val extensionLength: Float,
) {
    val endAngle: Float
        get() = startAngle + sweepAngle

    val hasRoundedCorners: Boolean
        get() = filletRadius > 0f && outerCornerAngleOffset > 0f && innerCornerAngleOffset > 0f

    companion object {
        fun from(band: CornerArcBand, scale: Float, origin: Offset): CornerBandContour? {
            val outerRadius = band.outerRadius * scale
            val innerRadius = band.innerRadius * scale
            val thickness = outerRadius - innerRadius
            val sweepAngle = band.sweepAngle
            if (outerRadius <= 0f || thickness <= 0f || sweepAngle <= 0f) return null
            val centerX = origin.x + band.centerX * scale
            val centerY = origin.y + band.centerY * scale
            val midRadius = (outerRadius + innerRadius) * 0.5f
            val arcSpanPx = midRadius * Math.toRadians(sweepAngle.toDouble()).toFloat()
            val candidateFillet =
                minOf(
                    thickness * 0.44f,
                    arcSpanPx * 0.26f,
                    innerRadius * 0.42f,
                )
            val outerOrbit = outerRadius - candidateFillet
            val innerOrbit = innerRadius + candidateFillet
            if (candidateFillet <= 0.5f || innerRadius <= 1f || outerOrbit <= innerOrbit) {
                return CornerBandContour(
                    centerX = centerX,
                    centerY = centerY,
                    innerRadius = innerRadius,
                    outerRadius = outerRadius,
                    startAngle = band.startAngle,
                    sweepAngle = sweepAngle,
                    filletRadius = 0f,
                    outerCornerAngleOffset = 0f,
                    innerCornerAngleOffset = 0f,
                    side = band.side,
                    extensionLength = band.extensionLength * scale,
                )
            }
            val alphaOut =
                Math.toDegrees(asin((candidateFillet / outerOrbit).coerceIn(0f, 0.999f).toDouble())).toFloat()
            val alphaIn =
                Math.toDegrees(asin((candidateFillet / innerOrbit).coerceIn(0f, 0.999f).toDouble())).toFloat()
            if (alphaOut * 2f >= sweepAngle - 0.4f || alphaIn * 2f >= sweepAngle - 0.4f) {
                return CornerBandContour(
                    centerX = centerX,
                    centerY = centerY,
                    innerRadius = innerRadius,
                    outerRadius = outerRadius,
                    startAngle = band.startAngle,
                    sweepAngle = sweepAngle,
                    filletRadius = 0f,
                    outerCornerAngleOffset = 0f,
                    innerCornerAngleOffset = 0f,
                    side = band.side,
                    extensionLength = band.extensionLength * scale,
                )
            }
            return CornerBandContour(
                centerX = centerX,
                centerY = centerY,
                innerRadius = innerRadius,
                outerRadius = outerRadius,
                startAngle = band.startAngle,
                sweepAngle = sweepAngle,
                filletRadius = candidateFillet,
                outerCornerAngleOffset = alphaOut,
                innerCornerAngleOffset = alphaIn,
                side = band.side,
                extensionLength = band.extensionLength * scale,
            )
        }
    }
}

/** 只在调整时绘制，不接收输入；全部动画值在 draw 阶段读取，不驱动整个设置页重组。 */
@Composable
internal fun CornerTriggerPreview(
    state: CornerTriggerPreviewState,
    geometry: CornerTriggerRegion?,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    if (geometry == null) return
    val pixelsPerDp = geometry.thickness / ModulePreferences.DEFAULT_CORNER_ARC_WIDTH_DP
    val primaryColor = MiuixTheme.colorScheme.primary
    val neutralColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f

    val visibility = remember { Animatable(0f) }
    val animatedLengthDp = remember { Animatable(state.lengthDp.toFloat()) }
    val animatedWidthDp = remember { Animatable(state.widthDp.toFloat()) }
    val lengthFocus = remember { Animatable(0f) }
    val widthFocus = remember { Animatable(0f) }
    val trackPath = remember { Path() }
    val activePath = remember { Path() }
    val capAccentPath = remember { Path() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                state.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 显隐与维度焦点独立于数值步进协程，确保按下滑块即刻浮现、拖动途中不被打断。
    LaunchedEffect(state.isActive, state.releaseGeneration, state.activeDimension) {
        val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        val targetLengthFocus =
            if (state.isActive && state.activeDimension == CornerPreviewDimension.Length) 1f else 0f
        val targetWidthFocus =
            if (state.isActive && state.activeDimension == CornerPreviewDimension.Width) 1f else 0f

        if (state.isActive) {
            if (visibility.value <= VISIBILITY_STOP_THRESHOLD) {
                animatedLengthDp.snapTo(state.lengthDp.toFloat())
                animatedWidthDp.snapTo(state.widthDp.toFloat())
            }
            if (!animationsEnabled) {
                lengthFocus.snapTo(targetLengthFocus)
                widthFocus.snapTo(targetWidthFocus)
                visibility.snapTo(1f)
                return@LaunchedEffect
            }
            coroutineScope {
                launch {
                    visibility.animateTo(
                        targetValue = 1f,
                        animationSpec = folmeSpring(damping = ENTRY_DAMPING, response = ENTRY_RESPONSE),
                    )
                }
                launch {
                    lengthFocus.animateTo(
                        targetValue = targetLengthFocus,
                        animationSpec = folmeSpring(damping = FOCUS_DAMPING, response = FOCUS_RESPONSE),
                    )
                }
                launch {
                    widthFocus.animateTo(
                        targetValue = targetWidthFocus,
                        animationSpec = folmeSpring(damping = FOCUS_DAMPING, response = FOCUS_RESPONSE),
                    )
                }
            }
        } else if (state.releaseGeneration == 0) {
            visibility.snapTo(0f)
            lengthFocus.snapTo(0f)
            widthFocus.snapTo(0f)
        } else {
            delay(RELEASE_HOLD_MILLIS)
            if (!animationsEnabled) {
                lengthFocus.snapTo(0f)
                widthFocus.snapTo(0f)
                visibility.snapTo(0f)
                return@LaunchedEffect
            }
            coroutineScope {
                launch {
                    lengthFocus.animateTo(
                        targetValue = 0f,
                        animationSpec = folmeSpring(damping = 1f, response = EXIT_RESPONSE),
                    )
                }
                launch {
                    widthFocus.animateTo(
                        targetValue = 0f,
                        animationSpec = folmeSpring(damping = 1f, response = EXIT_RESPONSE),
                    )
                }
                visibility.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        folmeSpring(
                            damping = 1f,
                            response = EXIT_RESPONSE,
                            visibilityThreshold = VISIBILITY_STOP_THRESHOLD,
                        ),
                )
            }
        }
    }

    LaunchedEffect(state.lengthDp) {
        val target = state.lengthDp.toFloat()
        if (!ValueAnimator.areAnimatorsEnabled() || visibility.value <= VISIBILITY_STOP_THRESHOLD) {
            animatedLengthDp.snapTo(target)
        } else {
            animatedLengthDp.animateTo(
                targetValue = target,
                animationSpec = folmeSpring(damping = VALUE_DAMPING, response = VALUE_RESPONSE),
            )
        }
    }

    LaunchedEffect(state.widthDp) {
        val target = state.widthDp.toFloat()
        if (!ValueAnimator.areAnimatorsEnabled() || visibility.value <= VISIBILITY_STOP_THRESHOLD) {
            animatedWidthDp.snapTo(target)
        } else {
            animatedWidthDp.animateTo(
                targetValue = target,
                animationSpec = folmeSpring(damping = VALUE_DAMPING, response = VALUE_RESPONSE),
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val currentVisibility = visibility.value.coerceIn(0f, 1f)
        if (currentVisibility <= VISIBILITY_STOP_THRESHOLD) return@Canvas

        // 全屏时为实际尺寸；自由窗等较小宿主等比缩放，不按不同轴拉伸圆角。
        val scale = minOf(size.width / geometry.displayWidth, size.height / geometry.displayHeight)
        if (!scale.isFinite() || scale <= 0f) return@Canvas
        val origin =
            Offset(
                x = (size.width - geometry.displayWidth * scale) * 0.5f,
                y = size.height - geometry.displayHeight * scale,
            )

        val currentLengthFocus = lengthFocus.value.coerceIn(0f, 1f)
        val currentWidthFocus = widthFocus.value.coerceIn(0f, 1f)

        // 入场与退场时沿屏幕边缘向内轻微舒展与收拢，避免生硬闪烁。
        val radialPresence = 0.78f + 0.22f * currentVisibility
        val sweepPresence = 0.92f + 0.08f * currentVisibility
        val thicknessPx =
            (animatedWidthDp.value.coerceIn(
                ModulePreferences.MIN_CORNER_ARC_WIDTH_DP.toFloat(),
                ModulePreferences.MAX_CORNER_ARC_WIDTH_DP.toFloat(),
            ) * pixelsPerDp * radialPresence).coerceAtLeast(1f)
        val arcLengthPx =
            (animatedLengthDp.value.coerceIn(
                ModulePreferences.MIN_CORNER_ARC_LENGTH_DP.toFloat(),
                ModulePreferences.MAX_CORNER_ARC_LENGTH_DP.toFloat(),
            ) * pixelsPerDp * sweepPresence).coerceAtLeast(1f)

        drawCornerBand(
            arc = geometry.left,
            side = CornerSide.Left,
            enabled = leftEnabled,
            thicknessPx = thicknessPx,
            activeBand = geometry.band(CornerSide.Left, thicknessPx, arcLengthPx),
            scale = scale,
            origin = origin,
            visibility = currentVisibility,
            lengthFocus = currentLengthFocus,
            widthFocus = currentWidthFocus,
            primaryColor = primaryColor,
            neutralColor = neutralColor,
            isDark = isDark,
            trackPath = trackPath,
            activePath = activePath,
            capAccentPath = capAccentPath,
        )
        drawCornerBand(
            arc = geometry.right,
            side = CornerSide.Right,
            enabled = rightEnabled,
            thicknessPx = thicknessPx,
            activeBand = geometry.band(CornerSide.Right, thicknessPx, arcLengthPx),
            scale = scale,
            origin = origin,
            visibility = currentVisibility,
            lengthFocus = currentLengthFocus,
            widthFocus = currentWidthFocus,
            primaryColor = primaryColor,
            neutralColor = neutralColor,
            isDark = isDark,
            trackPath = trackPath,
            activePath = activePath,
            capAccentPath = capAccentPath,
        )
    }
}

private fun DrawScope.drawCornerBand(
    arc: CornerArc?,
    side: CornerSide,
    enabled: Boolean,
    thicknessPx: Float,
    activeBand: CornerArcBand?,
    scale: Float,
    origin: Offset,
    visibility: Float,
    lengthFocus: Float,
    widthFocus: Float,
    primaryColor: Color,
    neutralColor: Color,
    isDark: Boolean,
    trackPath: Path,
    activePath: Path,
    capAccentPath: Path,
) {
    if (arc == null || activeBand == null) return
    val fullBand = arc.band(side, thicknessPx, arc.quarterArcLength)
    val trackContour = CornerBandContour.from(fullBand, scale, origin) ?: return
    val activeContour = CornerBandContour.from(activeBand, scale, origin) ?: return

    val baseTone = if (enabled) primaryColor else neutralColor
    val center = Offset(activeContour.centerX, activeContour.centerY)

    // 1. 全圆角基准底轨：克制标示整个 90° 物理圆角的可调边界。
    populateRoundedBandPath(trackPath, trackContour)
    val trackFillAlpha =
        if (enabled) {
            ((if (isDark) 0.08f else 0.055f) + 0.035f * lengthFocus) * visibility
        } else {
            (if (isDark) 0.05f else 0.035f) * visibility
        }
    val trackStrokeAlpha =
        if (enabled) {
            ((if (isDark) 0.16f else 0.12f) + 0.07f * lengthFocus) * visibility
        } else {
            0.08f * visibility
        }
    drawPath(path = trackPath, color = baseTone.copy(alpha = trackFillAlpha))
    drawPath(
        path = trackPath,
        color = baseTone.copy(alpha = trackStrokeAlpha),
        style = Stroke(width = 1.dp.toPx()),
    )

    // 2. 内侧环境柔光：消除弧带与背景卡片之间的生硬割裂。
    if (enabled) {
        val glowSpread = (10.dp.toPx() * (0.85f + 0.30f * widthFocus)).coerceAtMost(activeContour.innerRadius * 0.45f)
        val glowInnerRadius = (activeContour.innerRadius - glowSpread).coerceAtLeast(1f)
        val glowMidRadius = (activeContour.innerRadius + glowInnerRadius) * 0.5f
        val glowWidth = activeContour.innerRadius - glowInnerRadius
        if (glowWidth > 0.5f && activeContour.outerRadius > glowInnerRadius) {
            val glowInnerStop = (glowInnerRadius / activeContour.outerRadius).coerceIn(0f, 0.98f)
            val glowEdgeStop = (activeContour.innerRadius / activeContour.outerRadius).coerceIn(glowInnerStop + 0.01f, 1f)
            val glowAlpha = ((if (isDark) 0.14f else 0.10f) + 0.06f * widthFocus) * visibility
            val glowInset = activeContour.innerCornerAngleOffset.coerceAtMost(activeContour.sweepAngle * 0.45f)
            val glowSweep = (activeContour.sweepAngle - glowInset * 2f).coerceAtLeast(0.5f)
            drawArc(
                brush =
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        glowInnerStop to Color.Transparent,
                        glowEdgeStop to primaryColor.copy(alpha = glowAlpha),
                        1f to Color.Transparent,
                        center = center,
                        radius = activeContour.outerRadius,
                    ),
                startAngle = activeContour.startAngle + glowInset,
                sweepAngle = glowSweep,
                useCenter = false,
                topLeft = Offset(center.x - glowMidRadius, center.y - glowMidRadius),
                size = Size(glowMidRadius * 2f, glowMidRadius * 2f),
                style = Stroke(width = glowWidth, cap = StrokeCap.Round),
            )
        }
    }

    // 3. 弧带主体：由外沿向内沿递减的径向渐变，呈现轻透的玻璃层次感。
    populateRoundedBandPath(activePath, activeContour)
    val innerStop = (activeContour.innerRadius / activeContour.outerRadius).coerceIn(0f, 0.98f)
    val midStop = ((innerStop + 1f) * 0.5f).coerceIn(innerStop + 0.01f, 0.99f)
    val innerAlpha =
        if (enabled) {
            ((if (isDark) 0.22f else 0.17f) + 0.05f * widthFocus) * visibility
        } else {
            0.06f * visibility
        }
    val midAlpha =
        if (enabled) {
            ((if (isDark) 0.36f else 0.30f) + 0.04f * lengthFocus) * visibility
        } else {
            0.09f * visibility
        }
    val outerAlpha =
        if (enabled) {
            ((if (isDark) 0.52f else 0.44f) + 0.05f * lengthFocus) * visibility
        } else {
            0.13f * visibility
        }
    val arcFill = Brush.radialGradient(
        0f to Color.Transparent,
        innerStop to baseTone.copy(alpha = innerAlpha),
        midStop to baseTone.copy(alpha = midAlpha),
        1f to baseTone.copy(alpha = outerAlpha),
        center = center,
        radius = activeContour.outerRadius,
    )
    if (activeContour.extensionLength > 0f) {
        val c = activeContour
        clipRect(
            left = if (side == CornerSide.Left) c.centerX - c.outerRadius else c.centerX,
            top = c.centerY,
            right = if (side == CornerSide.Left) c.centerX else c.centerX + c.outerRadius,
            bottom = c.centerY + c.outerRadius,
        ) {
            drawPath(path = activePath, brush = arcFill)
        }
    } else {
        drawPath(path = activePath, brush = arcFill)
    }

    if (activeContour.extensionLength > 0f) {
        // 直线段按距屏幕边缘的深度渐变，与圆弧在切点保持同色。
        val c = activeContour
        val direction = if (side == CornerSide.Left) -1f else 1f
        val innerX = c.centerX + direction * c.innerRadius
        val outerX = c.centerX + direction * c.outerRadius
        val bottomEndX = c.centerX - direction * c.extensionLength
        val stops = arrayOf(
            0f to baseTone.copy(alpha = innerAlpha),
            0.5f to baseTone.copy(alpha = midAlpha),
            1f to baseTone.copy(alpha = outerAlpha),
        )
        clipPath(activePath) {
            drawRect(
                brush = Brush.linearGradient(*stops, start = Offset(innerX, c.centerY), end = Offset(outerX, c.centerY)),
                topLeft = Offset(minOf(innerX, outerX), c.centerY - c.extensionLength),
                size = Size(c.outerRadius - c.innerRadius, c.extensionLength),
            )
            drawRect(
                brush = Brush.linearGradient(*stops,
                    start = Offset(c.centerX, c.centerY + c.innerRadius),
                    end = Offset(c.centerX, c.centerY + c.outerRadius)),
                topLeft = Offset(minOf(c.centerX, bottomEndX), c.centerY + c.innerRadius),
                size = Size(c.extensionLength, c.outerRadius - c.innerRadius),
            )
        }
    }

    // 4. 连续细线包边：勾勒平滑切角轮廓。
    val contourAlpha = (if (enabled) (if (isDark) 0.56f else 0.48f) else 0.16f) * visibility
    drawPath(
        path = activePath,
        color = baseTone.copy(alpha = contourAlpha),
        style = Stroke(width = 1.1.dp.toPx()),
    )

    if (!enabled) return

    // 5. 维度感知高光：调整弧宽时强化内沿边界弦线，调整弧长时点亮两端圆弧切角。
    val innerInset = if (activeContour.extensionLength > 0f) 0f else activeContour.innerCornerAngleOffset
    val innerArcSweep = (activeContour.sweepAngle - innerInset * 2f).coerceAtLeast(0f)
    if (innerArcSweep > 0.5f) {
        val innerHighlightAlpha = (0.58f + 0.34f * widthFocus) * visibility
        val innerHighlightWidth = (1.3f + 0.55f * widthFocus).dp.toPx()
        drawArc(
            color = primaryColor.copy(alpha = innerHighlightAlpha),
            startAngle = activeContour.startAngle + innerInset,
            sweepAngle = innerArcSweep,
            useCenter = false,
            topLeft = Offset(center.x - activeContour.innerRadius, center.y - activeContour.innerRadius),
            size = Size(activeContour.innerRadius * 2f, activeContour.innerRadius * 2f),
            style = Stroke(width = innerHighlightWidth, cap = StrokeCap.Round),
        )
    }

    if (activeContour.extensionLength > 0f) {
        val c = activeContour
        val direction = if (side == CornerSide.Left) -1f else 1f
        val endInset = minOf(c.filletRadius, c.extensionLength)
        val highlight = primaryColor.copy(alpha = (0.58f + 0.34f * widthFocus) * visibility)
        val strokeWidth = (1.3f + 0.55f * widthFocus).dp.toPx()
        drawLine(highlight,
            Offset(c.centerX + direction * c.innerRadius, c.centerY),
            Offset(c.centerX + direction * c.innerRadius, c.centerY - c.extensionLength + endInset), strokeWidth)
        drawLine(highlight,
            Offset(c.centerX, c.centerY + c.innerRadius),
            Offset(c.centerX - direction * (c.extensionLength - endInset), c.centerY + c.innerRadius), strokeWidth)
    }

    if (lengthFocus > 0.02f && activeContour.hasRoundedCorners) {
        populateEndCapsPath(capAccentPath, activeContour)
        drawPath(
            path = capAccentPath,
            color = primaryColor.copy(alpha = (0.42f * lengthFocus) * visibility),
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

internal fun populateRoundedBandPath(path: Path, contour: CornerBandContour) {
    path.rewind()
    if (contour.extensionLength > 0f) {
        populateExtendedBandPath(path, contour)
        return
    }
    val cx = contour.centerX
    val cy = contour.centerY
    val outerR = contour.outerRadius
    val innerR = contour.innerRadius
    val start = contour.startAngle
    val end = contour.endAngle

    if (!contour.hasRoundedCorners) {
        path.arcTo(
            rect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR),
            startAngleDegrees = start,
            sweepAngleDegrees = contour.sweepAngle,
            forceMoveTo = true,
        )
        path.arcTo(
            rect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR),
            startAngleDegrees = end,
            sweepAngleDegrees = -contour.sweepAngle,
            forceMoveTo = false,
        )
        path.close()
        return
    }

    val r = contour.filletRadius
    val alphaOut = contour.outerCornerAngleOffset
    val alphaIn = contour.innerCornerAngleOffset
    val outerOrbit = outerR - r
    val innerOrbit = innerR + r

    // 1. 外弧（顺时针）
    path.arcTo(
        rect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR),
        startAngleDegrees = start + alphaOut,
        sweepAngleDegrees = contour.sweepAngle - 2f * alphaOut,
        forceMoveTo = true,
    )
    // 2. 末端外切角圆弧
    val endOuterAngleRad = Math.toRadians((end - alphaOut).toDouble())
    val endOuterCx = cx + outerOrbit * cos(endOuterAngleRad).toFloat()
    val endOuterCy = cy + outerOrbit * sin(endOuterAngleRad).toFloat()
    path.arcTo(
        rect = Rect(endOuterCx - r, endOuterCy - r, endOuterCx + r, endOuterCy + r),
        startAngleDegrees = end - alphaOut,
        sweepAngleDegrees = 90f + alphaOut,
        forceMoveTo = false,
    )
    // 3. 末端内切角圆弧
    val endInnerAngleRad = Math.toRadians((end - alphaIn).toDouble())
    val endInnerCx = cx + innerOrbit * cos(endInnerAngleRad).toFloat()
    val endInnerCy = cy + innerOrbit * sin(endInnerAngleRad).toFloat()
    path.arcTo(
        rect = Rect(endInnerCx - r, endInnerCy - r, endInnerCx + r, endInnerCy + r),
        startAngleDegrees = end + 90f,
        sweepAngleDegrees = 90f - alphaIn,
        forceMoveTo = false,
    )
    // 4. 内弧（逆时针）
    path.arcTo(
        rect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR),
        startAngleDegrees = end - alphaIn,
        sweepAngleDegrees = -(contour.sweepAngle - 2f * alphaIn),
        forceMoveTo = false,
    )
    // 5. 始端内切角圆弧
    val startInnerAngleRad = Math.toRadians((start + alphaIn).toDouble())
    val startInnerCx = cx + innerOrbit * cos(startInnerAngleRad).toFloat()
    val startInnerCy = cy + innerOrbit * sin(startInnerAngleRad).toFloat()
    path.arcTo(
        rect = Rect(startInnerCx - r, startInnerCy - r, startInnerCx + r, startInnerCy + r),
        startAngleDegrees = start + alphaIn + 180f,
        sweepAngleDegrees = 90f - alphaIn,
        forceMoveTo = false,
    )
    // 6. 始端外切角圆弧
    val startOuterAngleRad = Math.toRadians((start + alphaOut).toDouble())
    val startOuterCx = cx + outerOrbit * cos(startOuterAngleRad).toFloat()
    val startOuterCy = cy + outerOrbit * sin(startOuterAngleRad).toFloat()
    path.arcTo(
        rect = Rect(startOuterCx - r, startOuterCy - r, startOuterCx + r, startOuterCy + r),
        startAngleDegrees = start - 90f,
        sweepAngleDegrees = 90f + alphaOut,
        forceMoveTo = false,
    )
    path.close()
}

private fun populateExtendedBandPath(path: Path, contour: CornerBandContour, capsOnly: Boolean = false) {
    val cx = contour.centerX
    val cy = contour.centerY
    val outer = contour.outerRadius
    val inner = contour.innerRadius
    val extension = contour.extensionLength
    val direction = if (contour.side == CornerSide.Left) -1f else 1f
    val sideAngle = if (contour.side == CornerSide.Left) 180f else 0f
    val capAngle = if (contour.side == CornerSide.Left) 0f else 180f
    val r = minOf(contour.filletRadius, extension)
    fun cap(x: Float, y: Float, start: Float) {
        if (r > 0f) path.arcTo(Rect(x - r, y - r, x + r, y + r), start, direction * 90f, false)
    }

    if (!capsOnly) {
        path.moveTo(cx + direction * outer, cy - extension + r)
        path.lineTo(cx + direction * outer, cy)
        path.arcTo(Rect(cx - outer, cy - outer, cx + outer, cy + outer), sideAngle, direction * 90f, false)
        path.lineTo(cx - direction * (extension - r), cy + outer)
    } else {
        path.moveTo(cx - direction * (extension - r), cy + outer)
    }
    cap(cx - direction * (extension - r), cy + outer - r, 90f)
    path.lineTo(cx - direction * extension, cy + inner + r)
    cap(cx - direction * (extension - r), cy + inner + r, capAngle)
    if (!capsOnly) {
        path.lineTo(cx, cy + inner)
        path.arcTo(Rect(cx - inner, cy - inner, cx + inner, cy + inner), 90f, -direction * 90f, false)
        path.lineTo(cx + direction * inner, cy - extension + r)
    } else {
        path.moveTo(cx + direction * inner, cy - extension + r)
    }
    cap(cx + direction * (inner + r), cy - extension + r, capAngle)
    path.lineTo(cx + direction * (outer - r), cy - extension)
    cap(cx + direction * (outer - r), cy - extension + r, 270f)
    if (!capsOnly) path.close()
}

private fun populateEndCapsPath(path: Path, contour: CornerBandContour) {
    path.rewind()
    if (contour.extensionLength > 0f) {
        populateExtendedBandPath(path, contour, capsOnly = true)
        return
    }
    if (!contour.hasRoundedCorners) return
    val cx = contour.centerX
    val cy = contour.centerY
    val r = contour.filletRadius
    val alphaOut = contour.outerCornerAngleOffset
    val alphaIn = contour.innerCornerAngleOffset
    val outerOrbit = contour.outerRadius - r
    val innerOrbit = contour.innerRadius + r
    val start = contour.startAngle
    val end = contour.endAngle

    val endOuterAngleRad = Math.toRadians((end - alphaOut).toDouble())
    val endOuterCx = cx + outerOrbit * cos(endOuterAngleRad).toFloat()
    val endOuterCy = cy + outerOrbit * sin(endOuterAngleRad).toFloat()
    path.arcTo(
        rect = Rect(endOuterCx - r, endOuterCy - r, endOuterCx + r, endOuterCy + r),
        startAngleDegrees = end - alphaOut,
        sweepAngleDegrees = 90f + alphaOut,
        forceMoveTo = true,
    )
    val endInnerAngleRad = Math.toRadians((end - alphaIn).toDouble())
    val endInnerCx = cx + innerOrbit * cos(endInnerAngleRad).toFloat()
    val endInnerCy = cy + innerOrbit * sin(endInnerAngleRad).toFloat()
    path.arcTo(
        rect = Rect(endInnerCx - r, endInnerCy - r, endInnerCx + r, endInnerCy + r),
        startAngleDegrees = end + 90f,
        sweepAngleDegrees = 90f - alphaIn,
        forceMoveTo = false,
    )

    val startInnerAngleRad = Math.toRadians((start + alphaIn).toDouble())
    val startInnerCx = cx + innerOrbit * cos(startInnerAngleRad).toFloat()
    val startInnerCy = cy + innerOrbit * sin(startInnerAngleRad).toFloat()
    path.arcTo(
        rect = Rect(startInnerCx - r, startInnerCy - r, startInnerCx + r, startInnerCy + r),
        startAngleDegrees = start + alphaIn + 180f,
        sweepAngleDegrees = 90f - alphaIn,
        forceMoveTo = true,
    )
    val startOuterAngleRad = Math.toRadians((start + alphaOut).toDouble())
    val startOuterCx = cx + outerOrbit * cos(startOuterAngleRad).toFloat()
    val startOuterCy = cy + outerOrbit * sin(startOuterAngleRad).toFloat()
    path.arcTo(
        rect = Rect(startOuterCx - r, startOuterCy - r, startOuterCx + r, startOuterCy + r),
        startAngleDegrees = start - 90f,
        sweepAngleDegrees = 90f + alphaOut,
        forceMoveTo = false,
    )
}

private const val ENTRY_DAMPING = 0.96f
private const val ENTRY_RESPONSE = 0.12f
private const val VALUE_DAMPING = 0.96f
private const val VALUE_RESPONSE = 0.075f
private const val FOCUS_DAMPING = 0.96f
private const val FOCUS_RESPONSE = 0.12f
private const val EXIT_RESPONSE = 0.22f
private const val RELEASE_HOLD_MILLIS = 150L
private const val VISIBILITY_STOP_THRESHOLD = 0.004f
