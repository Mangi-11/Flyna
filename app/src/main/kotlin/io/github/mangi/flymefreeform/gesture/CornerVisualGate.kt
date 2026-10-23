package io.github.mangi.flymefreeform.gesture

/** 窄弧 DOWN 预期会被输入所有者立即接管；竞争视觉保持到终止或明确的未抢占通知。 */
internal class CornerVisualGate {
    var defersMoves: Boolean = false
        private set

    fun down(x: Float, y: Float, config: CornerGestureConfig) {
        defersMoves = config.triggerRegion.detectSide(x, y, config.leftEnabled, config.rightEnabled) != null
    }

    fun finish() {
        defersMoves = false
    }

    fun cancel() = finish()

    /** 只接受实际输入所有者确认未抢占的流；抢占后的取消不能恢复原生竞争动画。 */
    fun releaseUnclaimed() = finish()
}
