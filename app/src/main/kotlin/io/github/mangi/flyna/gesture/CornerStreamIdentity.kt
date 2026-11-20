package io.github.mangi.flyna.gesture

/** 原始 DOWN 的稳定身份；厂商回注修改的 downTime 不参与匹配。 */
internal data class CornerStreamIdentity(
    val deviceId: Int,
    val displayId: Int,
    val pointerId: Int,
    val eventTimeMillis: Long,
    val x: Float,
    val y: Float,
)
