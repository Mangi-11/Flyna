package io.github.mangi.flyna.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerReleaseHistoryTest {
    private val identity = CornerStreamIdentity(6, 0, 0, 100L, 5f, 1995f)

    @Test
    fun releaseBeforeDownIsRetainedForTheExactOriginalIdentity() {
        val history = CornerReleaseHistory()
        history.record(identity, 120L)
        assertTrue(history.contains(identity.copy(), 130L))
        assertFalse(history.contains(identity.copy(eventTimeMillis = 101L), 130L))
        assertFalse(history.contains(identity.copy(pointerId = 1), 130L))
        assertFalse(history.contains(identity.copy(x = 6f), 130L))
    }

    @Test
    fun expiredOrFutureIdentityCannotBeRecordedAndDuplicatesDoNotExtendLifetime() {
        val history = CornerReleaseHistory()
        history.record(identity, 99L)
        assertFalse(history.contains(identity, 100L))
        history.record(identity, 120L)
        history.record(identity, 1000L)
        assertTrue(history.contains(identity, 1100L))
        assertFalse(history.contains(identity, 1101L))
        history.record(identity, 1200L)
        assertFalse(history.contains(identity, 1201L))
    }

    @Test
    fun oldestEntryIsEvictedAtCapacityWithoutDuplicateRecordsEvictingOthers() {
        val history = CornerReleaseHistory()
        val identities = (0..CornerReleaseHistory.CAPACITY).map {
            identity.copy(eventTimeMillis = 100L + it)
        }
        identities.take(CornerReleaseHistory.CAPACITY).forEach { history.record(it, 200L) }
        history.record(identities.first(), 201L)
        assertTrue(history.contains(identities[1], 201L))
        history.record(identities.last(), 202L)
        assertFalse(history.contains(identities.first(), 202L))
        identities.drop(1).forEach { assertTrue(history.contains(it, 202L)) }
    }

    @Test
    fun clearDropsAllPreviouslyReleasedStreams() {
        val history = CornerReleaseHistory()
        history.record(identity, 120L)
        history.clear()
        assertFalse(history.contains(identity, 130L))
    }
}
