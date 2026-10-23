package io.github.mangi.flymefreeform.gesture

/** 接收端的单会话协议状态；调用方串行调用，并在触发外部副作用前提交状态转换。 */
internal class CornerInputSession(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxEventAgeMillis: Long = DEFAULT_MAX_EVENT_AGE_MILLIS,
) {
    var id: Long = 0L
        private set
    var isActivated: Boolean = false
        private set
    var lastPreparedId: Long = 0L
        private set

    private var preparedAt = 0L
    private var lastEventTime = 0L

    init {
        require(timeoutMillis > 0L)
        require(maxEventAgeMillis >= 0L)
    }

    fun canPrepare(id: Long, time: Long, now: Long): Boolean =
        id > lastPreparedId && isTimeValid(time, now)

    /** 先清旧会话的外部资源，再调用本方法；环境拒绝时 clear，但不回退已消费的 ID。 */
    fun prepare(id: Long, time: Long, now: Long): Boolean {
        if (!canPrepare(id, time, now)) return false
        lastPreparedId = id
        this.id = id
        isActivated = false
        preparedAt = now
        lastEventTime = time
        return true
    }

    fun activate(id: Long, time: Long, now: Long): Boolean {
        if (!accepts(id, time, now) || isActivated) return false
        lastEventTime = time
        isActivated = true
        return true
    }

    fun move(id: Long, time: Long, now: Long): Boolean {
        if (!accepts(id, time, now) || !isActivated) return false
        lastEventTime = time
        return true
    }

    /** 匹配的 UP 总会终止 prepared/active 会话；仅 active 会话返回 true 供调用方提交。 */
    fun up(id: Long, time: Long, now: Long): Boolean {
        if (!accepts(id, time, now)) return false
        val deliver = isActivated
        clear()
        return deliver
    }

    fun cancel(id: Long): Boolean {
        if (id <= 0L || this.id != id) return false
        clear()
        return true
    }

    /** 断连、拒绝和终止均保留水位，旧消息不能重建已经消费的会话。 */
    fun clear(): Long {
        val previous = id
        id = 0L
        isActivated = false
        preparedAt = 0L
        lastEventTime = 0L
        return previous
    }

    /** 期限从 PREPARE 的接收时间开始，MOVE 不续期；定时器迟到时消息校验也会执行。 */
    fun expire(now: Long): Long =
        if (id != 0L && now >= preparedAt && now - preparedAt >= timeoutMillis) clear() else 0L

    fun isSampleValid(
        time: Long,
        now: Long,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): Boolean =
        isTimeValid(time, now) && x.isFinite() && y.isFinite() &&
            width.isFinite() && height.isFinite() && width > 0f && height > 0f &&
            x >= 0f && y >= 0f && x <= width && y <= height

    private fun accepts(id: Long, time: Long, now: Long): Boolean {
        expire(now)
        return id > 0L && this.id == id && now >= preparedAt &&
            time >= lastEventTime && isTimeValid(time, now)
    }

    private fun isTimeValid(time: Long, now: Long): Boolean =
        time >= 0L && now >= time && now - time <= maxEventAgeMillis

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_MAX_EVENT_AGE_MILLIS = 500L
    }
}
