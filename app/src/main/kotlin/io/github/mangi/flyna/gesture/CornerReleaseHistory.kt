package io.github.mangi.flyna.gesture

/** 有界的未认领流记录，处理释放通知先于另一路 DOWN 到达；不持有 Android 对象。 */
internal class CornerReleaseHistory {
    private val released = LinkedHashSet<CornerStreamIdentity>()

    fun record(identity: CornerStreamIdentity, now: Long) {
        prune(now)
        if (!isCurrent(identity, now)) return
        if (released.add(identity) && released.size > CAPACITY) {
            released.iterator().also { it.next(); it.remove() }
        }
    }

    fun contains(identity: CornerStreamIdentity, now: Long): Boolean {
        prune(now)
        return isCurrent(identity, now) && identity in released
    }

    fun clear() = released.clear()

    private fun prune(now: Long) {
        released.removeAll { !isCurrent(it, now) }
    }

    private fun isCurrent(identity: CornerStreamIdentity, now: Long): Boolean =
        identity.eventTimeMillis >= 0L && now >= identity.eventTimeMillis &&
            now - identity.eventTimeMillis <= TTL_MS

    companion object {
        const val TTL_MS = 1_000L
        const val CAPACITY = 16
    }
}
