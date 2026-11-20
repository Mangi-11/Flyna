package io.github.mangi.flyna.gesture

import kotlin.math.max

internal enum class CornerSide {
    Left,
    Right,
}

internal enum class GesturePhase {
    Idle,
    Armed,
    Yielded,
    Revealing,
    Selecting,
    Committing,
    MorePanel,
    Cancelling,
}

internal sealed interface GestureAction {
    data object Ignore : GestureAction
    data object PassThrough : GestureAction
    data class Activate(
        val side: CornerSide,
        val originX: Float,
        val originY: Float,
        val x: Float,
        val y: Float,
    ) : GestureAction
    data class Update(val side: CornerSide, val x: Float, val y: Float) : GestureAction
    data class Commit(val selectedIndex: Int?) : GestureAction
    data object Cancel : GestureAction
}

internal data class CornerGestureConfig(
    val triggerRegion: CornerTriggerRegion,
    val activationDistance: Float,
    val reverseTolerance: Float,
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
)

internal object AdaptiveCornerGestureConfig {
    fun create(
        triggerRegion: CornerTriggerRegion,
        touchSlop: Float,
        density: Float,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): CornerGestureConfig {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        return CornerGestureConfig(
            triggerRegion = triggerRegion,
            activationDistance = max(touchSlop, safeDensity * MIN_ACTIVATION_DP),
            reverseTolerance = max(touchSlop, safeDensity * REVERSE_TOLERANCE_DP),
            leftEnabled = leftEnabled,
            rightEnabled = rightEnabled,
        )
    }

    private const val REVERSE_TOLERANCE_DP = 8f
    private const val MIN_ACTIVATION_DP = 4f
}

/** 单指角落手势状态机；不持有 MotionEvent，便于跨进程入口复用与单元测试。 */
internal class CornerGestureEngine {
    var phase: GesturePhase = GesturePhase.Idle
        private set
    var selectedIndex: Int? = null
        private set

    private var pointerId = -1
    private var originX = 0f
    private var originY = 0f
    private var side: CornerSide? = null
    private var downTimeMillis = 0L

    fun down(
        pointerId: Int,
        x: Float,
        y: Float,
        config: CornerGestureConfig,
        eventTimeMillis: Long,
    ): GestureAction {
        reset()
        val detectedSide = detectSide(x, y, config) ?: return GestureAction.PassThrough
        this.pointerId = pointerId
        originX = x
        originY = y
        downTimeMillis = eventTimeMillis
        side = detectedSide
        phase = GesturePhase.Armed
        return GestureAction.Ignore
    }

    fun move(
        pointerId: Int,
        pointerCount: Int,
        x: Float,
        y: Float,
        config: CornerGestureConfig,
        eventTimeMillis: Long,
    ): GestureAction {
        val activeSide = side ?: return GestureAction.PassThrough
        if (pointerId != this.pointerId || pointerCount != 1 || !x.isFinite() || !y.isFinite()) {
            return reject()
        }

        val inward = if (activeSide == CornerSide.Left) x - originX else originX - x
        val upward = originY - y
        if (inward < -config.reverseTolerance || upward < -config.reverseTolerance) {
            return reject()
        }
        if (!isMenuReady) {
            if (eventTimeMillis < downTimeMillis) return reject()
            // 边缘 DOWN 已被接管；向内横滑与上滑共用展开阈值，避免横滑只吞输入却不显示菜单。
            if (max(inward, upward) < config.activationDistance) return GestureAction.Ignore
            phase = GesturePhase.Revealing
            return GestureAction.Activate(activeSide, originX, originY, x, y)
        }

        phase = GesturePhase.Selecting
        return GestureAction.Update(activeSide, x, y)
    }

    fun up(pointerId: Int): GestureAction {
        if (pointerId != this.pointerId) return cancel()
        if (side == null) {
            reset()
            return GestureAction.PassThrough
        }
        // 边缘点击或短滑仅结束会话，不能提交一个不可见的选择。
        if (!isMenuReady) return cancel()
        val selection = selectedIndex
        clearTracking(GesturePhase.Committing)
        val result = GestureAction.Commit(selection)
        return result
    }

    fun cancel(): GestureAction {
        val wasTracking = side != null
        clearTracking(if (wasTracking) GesturePhase.Cancelling else GesturePhase.Idle)
        return if (wasTracking) GestureAction.Cancel else GestureAction.PassThrough
    }

    fun setSelection(index: Int?) {
        if (isMenuReady) selectedIndex = index
    }

    val isMenuReady: Boolean
        get() = phase == GesturePhase.Revealing || phase == GesturePhase.Selecting

    private fun reject(): GestureAction {
        if (isMenuReady) return cancel()
        clearTracking(GesturePhase.Yielded)
        return GestureAction.PassThrough
    }

    private fun detectSide(x: Float, y: Float, config: CornerGestureConfig): CornerSide? {
        return config.triggerRegion.detectSide(
            x = x,
            y = y,
            leftEnabled = config.leftEnabled,
            rightEnabled = config.rightEnabled,
        )
    }

    private fun reset() {
        clearTracking(GesturePhase.Idle)
    }

    private fun clearTracking(nextPhase: GesturePhase) {
        phase = nextPhase
        selectedIndex = null
        pointerId = -1
        originX = 0f
        originY = 0f
        side = null
        downTimeMillis = 0L
    }
}
