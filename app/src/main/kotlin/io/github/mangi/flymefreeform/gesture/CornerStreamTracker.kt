package io.github.mangi.flymefreeform.gesture

/** Android 适配层的 DOWN 去重记录；回注可能改变 downTime，因此不以它作为流身份。 */
internal class CornerStreamTracker {
    var identity: CornerStreamIdentity? = null
        private set

    /** 同一个仍有效的原始 DOWN 返回 false；UP/CANCEL 后相同标识也可建立新流。 */
    fun begin(
        deviceId: Int,
        displayId: Int,
        pointerId: Int,
        eventTimeMillis: Long,
        x: Float,
        y: Float,
    ): Boolean {
        val down = CornerStreamIdentity(deviceId, displayId, pointerId, eventTimeMillis, x, y)
        if (identity == down) return false
        identity = down
        return true
    }

    fun matches(deviceId: Int, displayId: Int): Boolean =
        identity?.let { it.deviceId == deviceId && it.displayId == displayId } == true

    fun matches(identity: CornerStreamIdentity): Boolean = this.identity == identity

    fun finish() {
        identity = null
    }
}
