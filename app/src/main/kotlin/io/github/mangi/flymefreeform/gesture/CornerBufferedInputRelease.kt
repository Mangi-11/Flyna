package io.github.mangi.flymefreeform.gesture

/** 只允许当前物理起点放行一次；回注别名不能重置已请求状态。 */
internal class CornerBufferedInputRelease {
    private var identity: CornerStreamIdentity? = null
    private var requested = false

    fun begin(identity: CornerStreamIdentity) {
        if (this.identity == identity) return
        this.identity = identity
        requested = false
    }

    fun request(identity: CornerStreamIdentity): Boolean {
        if (this.identity != identity || requested) return false
        requested = true
        return true
    }

    fun matches(identity: CornerStreamIdentity): Boolean = this.identity == identity

    fun finish() {
        identity = null
        requested = false
    }
}
