package io.github.mangi.flymefreeform.gesture

/** 窄边缘先接管 DOWN，准备回复只授权菜单显示；实际抢占状态一直保留到流结束。 */
internal class CornerInputHandoff {
    private enum class Stage { Idle, Claimed, Prepared, Active }

    private var stage = Stage.Idle

    val isClaimed: Boolean get() = stage != Stage.Idle
    val isActive: Boolean get() = stage == Stage.Active

    fun claimInput(claim: () -> Boolean): Boolean {
        if (isClaimed) return true
        if (!claim()) return false
        stage = Stage.Claimed
        return true
    }

    fun acceptPreparation() {
        if (stage == Stage.Claimed) stage = Stage.Prepared
    }

    /** false 表示副作用失败，调用方必须终止本次会话；不在后续 MOVE 重试。 */
    fun activateIfReady(
        menuReady: Boolean,
        activateMenu: () -> Boolean,
    ): Boolean {
        if (!menuReady || stage != Stage.Prepared) return true
        if (!activateMenu()) return false
        stage = Stage.Active
        return true
    }

    fun reset() {
        stage = Stage.Idle
    }
}
