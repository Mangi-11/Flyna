package io.github.mangi.flymefreeform.gesture

/** 侧返回只在新的物理 DOWN 上让出缓存；回注和中途恢复就绪都不能重新仲裁。 */
internal class CornerSideBackHandoff {
    private val release = CornerBufferedInputRelease()

    fun request(
        identity: CornerStreamIdentity,
        region: CornerTriggerRegion?,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
        nativeBuffering: Boolean,
    ): Boolean {
        if (release.matches(identity)) return false
        release.begin(identity)
        if (identity.displayId != 0 || region == null || !nativeBuffering) return false
        if (region.detectSide(identity.x, identity.y, leftEnabled, rightEnabled) == null) return false
        return release.request(identity)
    }
}
