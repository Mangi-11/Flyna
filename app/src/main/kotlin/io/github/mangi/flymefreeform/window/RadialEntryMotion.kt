package io.github.mangi.flymefreeform.window

internal data class RadialEntryVisuals(
    val contentAlpha: Float,
    val radialProgress: Float,
    /** Multiplier for the horizontal, inward overshoot distance; may become negative. */
    val horizontalOvershoot: Float,
    val rotationDegrees: Float,
    val iconScale: Float,
    /** Angular lag (in degrees) tucked toward the bottom edge while fanning out; 0f at rest. */
    val angularOffsetDegrees: Float = 0f,
)

/**
 * 角落扇形展开曲线：结合手指初段上滑牵引、微错峰弧向舒展与柔和旋入回摆。
 */
internal object RadialEntryMotion {
    const val DURATION_MILLIS = 440L
    const val SELECTION_DURATION_MILLIS = 130L
    const val HORIZONTAL_OVERSHOOT_DP = 6f
    const val SELECTION_RING_ALPHA = 64f / 255f
    const val LEAD_IN_RADIUS_FRACTION = 0.24f

    private const val EXPANSION_MILLIS = 220f
    private const val REBOUND_MILLIS = 220f
    private const val PULL_LEAD_WEIGHT = 0.28f
    private const val MAX_STAGGER_FRACTION = 0.14f
    private const val MIN_ARC_FAN_DEGREES = 6f
    private const val MAX_ARC_FAN_DEGREES = 22f
    private const val START_ROTATION_DEGREES = -120f
    private const val HANDOFF_ROTATION_DEGREES = 4f
    private const val START_ICON_SCALE = 0.78f
    private const val PEAK_ICON_SCALE = 1.035f
    private const val PEAK_OVERSHOOT_FRACTION = 0.75f

    private val fade = CubicMotionCurve(0.26f, 0.08f, 0.52f, 1f)
    private val expandRotation = CubicMotionCurve(0.22f, 0.14f, 0.42f, 0.88f)
    private val expandScale = CubicMotionCurve(0.22f, 0.30f, 0.38f, 1f)
    // 末端控制点 y2=1f，使径向展开在交界处速度平滑收敛为 0，消除 130ms 急停折角。
    private val expandPosition = CubicMotionCurve(0.22f, 0.16f, 0.34f, 1f)
    private val expandArc = CubicMotionCurve(0.20f, 0.12f, 0.32f, 1f)
    private val expandOvershoot = CubicMotionCurve(0.24f, 0.20f, 0.44f, 0.86f)
    private val reboundRotation = CubicMotionCurve(0.20f, -3.2f, 0.46f, 1f)
    private val reboundScale = CubicMotionCurve(0.22f, 0.28f, 0.52f, 1f)
    private val reboundPosition = CubicMotionCurve(0.22f, 1.16f, 0.66f, 1f)
    private val selection = CubicMotionCurve(0.33f, 0f, 0.66f, 1f)

    /**
     * 将时间推进与手指初段牵引耦合：初段随手指上滑距离即时推进，随后平滑汇入完整展开终点。
     */
    fun effectiveProgress(timeProgress: Float, pullFraction: Float): Float {
        val t = timeProgress.coerceIn(0f, 1f)
        val p = pullFraction.coerceIn(0f, 1f)
        val remaining = 1f - t
        return (t + p * PULL_LEAD_WEIGHT * remaining * remaining).coerceIn(0f, 1f)
    }

    fun sample(linearProgress: Float, angularSpanDegrees: Float = 0f): RadialEntryVisuals {
        val elapsed = linearProgress.coerceIn(0f, 1f) * DURATION_MILLIS
        val safeSpan = angularSpanDegrees.coerceAtLeast(0f)
        if (elapsed <= EXPANSION_MILLIS) {
            val progress = elapsed / EXPANSION_MILLIS
            val travel = expandPosition.sample(progress)
            val arcTravel = expandArc.sample(progress)
            val scaleRange = PEAK_ICON_SCALE - START_ICON_SCALE
            val rotationSpan = HANDOFF_ROTATION_DEGREES - START_ROTATION_DEGREES
            return RadialEntryVisuals(
                contentAlpha = fade.sample(progress),
                radialProgress = travel,
                horizontalOvershoot = PEAK_OVERSHOOT_FRACTION * expandOvershoot.sample(progress),
                rotationDegrees = START_ROTATION_DEGREES + rotationSpan * expandRotation.sample(progress),
                iconScale = START_ICON_SCALE + scaleRange * expandScale.sample(progress),
                angularOffsetDegrees = safeSpan * (1f - arcTravel),
            )
        }
        val progress = (elapsed - EXPANSION_MILLIS) / REBOUND_MILLIS
        val settleScale = PEAK_ICON_SCALE - 1f
        return RadialEntryVisuals(
            contentAlpha = 1f,
            radialProgress = 1f,
            horizontalOvershoot = PEAK_OVERSHOOT_FRACTION * (1f - reboundPosition.sample(progress)),
            rotationDegrees = HANDOFF_ROTATION_DEGREES * (1f - reboundRotation.sample(progress)),
            iconScale = 1f + settleScale * (1f - reboundScale.sample(progress)),
            angularOffsetDegrees = 0f,
        )
    }

    /**
     * 按槽位（0 为最贴近底边的“更多”，向上递增）施加微错峰与圆弧角度舒展。
     */
    fun sampleItem(linearProgress: Float, slot: Int, itemCount: Int): RadialEntryVisuals {
        val clampedProgress = linearProgress.coerceIn(0f, 1f)
        val safeCount = itemCount.coerceAtLeast(1)
        val clampedSlot = slot.coerceIn(0, safeCount - 1)
        val slotFraction = if (safeCount > 1) clampedSlot.toFloat() / (safeCount - 1).toFloat() else 0f
        val delay = slotFraction * MAX_STAGGER_FRACTION
        val localProgress = ((clampedProgress - delay) / (1f - MAX_STAGGER_FRACTION)).coerceIn(0f, 1f)
        val angularSpan = MIN_ARC_FAN_DEGREES + (MAX_ARC_FAN_DEGREES - MIN_ARC_FAN_DEGREES) * slotFraction
        return sample(localProgress, angularSpan)
    }

    /** Animates ring thickness only; the selected icon retains its size. */
    fun selectionProgress(linearProgress: Float): Float = selection.sample(linearProgress)
}

/** Evaluates time-based cubic motion while preserving values outside the unit interval. */
internal class CubicMotionCurve(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {
    fun sample(linearProgress: Float): Float {
        val x = linearProgress.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        var low = 0f
        var high = 1f
        repeat(20) {
            val parameter = (low + high) * 0.5f
            if (coordinate(parameter, x1, x2) < x) {
                low = parameter
            } else {
                high = parameter
            }
        }
        // Time is bounded, but the curve's output must retain its overshoot.
        return coordinate((low + high) * 0.5f, y1, y2)
    }

    private fun coordinate(parameter: Float, first: Float, second: Float): Float {
        val remainder = 1f - parameter
        return 3f * remainder * remainder * parameter * first +
            3f * remainder * parameter * parameter * second +
            parameter * parameter * parameter
    }
}
