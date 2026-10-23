package io.github.mangi.flymefreeform.gesture

/** 只裁决竞争视觉的 MOVE，不持有事件、不抢占输入，也不负责菜单生命周期。 */
internal class CornerVisualGate {
    private val engine = CornerGestureEngine()
    private var config: CornerGestureConfig? = null
    private var recognized = false
    private var startedAt = 0L

    fun down(
        pointerId: Int,
        x: Float,
        y: Float,
        config: CornerGestureConfig,
        eventTimeMillis: Long,
    ) {
        recognized = false
        startedAt = eventTimeMillis
        engine.down(pointerId, x, y, config, eventTimeMillis)
        this.config = config.takeIf { engine.phase == GesturePhase.Armed }
    }

    /** false 时调用方立即放行当前真实 MOVE；DOWN、长按与终止事件始终由原链处理。 */
    fun move(
        pointerId: Int,
        pointerCount: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
    ): Boolean {
        // 已识别的旧流不因反向、多指或菜单取消重新启动竞争动画。
        if (recognized) return true
        val activeConfig = config ?: return false
        // 仅限制等待中的视觉延后；不决定菜单能否触发，超时即保留系统 MOVE。
        if (eventTimeMillis - startedAt >= 250L) {
            finish()
            return false
        }
        engine.move(pointerId, pointerCount, x, y, activeConfig, eventTimeMillis)
        recognized = engine.isClaimed
        val defer = engine.phase == GesturePhase.Armed || recognized
        if (!defer) config = null
        return defer
    }

    fun finish() {
        recognized = false
        config = null
        engine.cancel()
    }

    fun cancel() = finish()

    /** 仅供身份匹配、实际输入所有者确认尚未抢占的流使用；撤销本地预测识别。 */
    fun releaseUnclaimed() = finish()

    /** 环境失效或候选多指立即让渡；已识别流的视觉门保持到真正 UP/CANCEL。 */
    fun cancelCandidate() {
        if (!recognized) finish()
    }

    fun pointerDown() = cancelCandidate()
}
