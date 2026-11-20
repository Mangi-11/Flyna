package io.github.mangi.flyna.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerStreamTrackerTest {
    @Test
    fun reinjectedDownAliasDoesNotBeginAgain() {
        val tracker = CornerStreamTracker()
        assertTrue(tracker.begin(6, 0, 0, 100L, 5f, 1995f))
        // downTime 不属于身份；原始 eventTime/位置未变的回注仍是同一次 DOWN。
        assertFalse(tracker.begin(6, 0, 0, 100L, 5f, 1995f))
        assertTrue(tracker.matches(6, 0))
        assertFalse(tracker.matches(7, 0))
        assertFalse(tracker.matches(6, 1))
        tracker.finish()
        assertFalse(tracker.matches(6, 0))
        assertTrue(tracker.begin(6, 0, 0, 100L, 5f, 1995f))
    }

    @Test
    fun changedTimePositionPointerDeviceOrDisplayStartsANewStream() {
        val tracker = CornerStreamTracker()
        assertTrue(tracker.begin(6, 0, 0, 100L, 5f, 1995f))
        assertTrue(tracker.begin(6, 0, 0, 101L, 5f, 1995f))
        assertTrue(tracker.begin(6, 0, 0, 101L, 6f, 1995f))
        assertTrue(tracker.begin(6, 0, 1, 101L, 6f, 1995f))
        assertTrue(tracker.begin(7, 0, 1, 101L, 6f, 1995f))
        assertTrue(tracker.begin(7, 1, 1, 101L, 6f, 1995f))
    }

    @Test
    fun publishedIdentityMatchesAliasesButNotNewOrFinishedStreams() {
        val tracker = CornerStreamTracker()
        assertNull(tracker.identity)
        val first = CornerStreamIdentity(6, 0, 0, 100L, 5f, 1995f)
        tracker.begin(6, 0, 0, 100L, 5f, 1995f)
        assertEquals(first, tracker.identity)
        assertTrue(tracker.matches(first.copy()))
        assertFalse(tracker.begin(6, 0, 0, 100L, 5f, 1995f))
        assertTrue(tracker.matches(first))
        tracker.begin(6, 0, 0, 200L, 5f, 1995f)
        assertFalse(tracker.matches(first))
        val second = first.copy(eventTimeMillis = 200L)
        assertTrue(tracker.matches(second))
        tracker.finish()
        assertNull(tracker.identity)
        assertFalse(tracker.matches(second))
    }
}
