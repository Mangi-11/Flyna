package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerInputSessionTest {
    @Test
    fun onlyPreparedSessionCanActivateOnceAndDeliverMoves() {
        val state = CornerInputSession()
        assertFalse(state.activate(1L, 100L, 100L))
        assertFalse(state.move(1L, 100L, 100L))
        assertTrue(state.prepare(1L, 100L, 100L))
        assertFalse(state.move(1L, 110L, 110L))
        assertTrue(state.activate(1L, 120L, 120L))
        assertFalse(state.activate(1L, 121L, 121L))
        assertTrue(state.move(1L, 122L, 122L))
        assertTrue(state.up(1L, 123L, 123L))
        assertEquals(0L, state.id)
        assertFalse(state.isActivated)
    }

    @Test
    fun upBeforePrepareOrBeforeActivationNeverCommits() {
        val state = CornerInputSession()
        assertFalse(state.up(1L, 100L, 100L))
        assertTrue(state.prepare(1L, 110L, 110L))
        assertFalse(state.up(1L, 120L, 120L))
        assertEquals(0L, state.id)
        assertFalse(state.activate(1L, 121L, 121L))
    }

    @Test
    fun oldPrepareActivateUpAndCancelCannotDisplaceNewSession() {
        val state = CornerInputSession()
        assertTrue(state.prepare(1L, 100L, 100L))
        assertTrue(state.activate(1L, 110L, 110L))
        assertEquals(1L, state.clear())
        assertTrue(state.prepare(2L, 120L, 120L))
        assertFalse(state.prepare(1L, 130L, 130L))
        assertFalse(state.activate(1L, 130L, 130L))
        assertFalse(state.up(1L, 130L, 130L))
        assertFalse(state.cancel(1L))
        assertEquals(2L, state.id)
        assertTrue(state.activate(2L, 140L, 140L))
    }

    @Test
    fun cancellationAndRejectedPreparationKeepTheMonotonicWatermark() {
        val state = CornerInputSession()
        assertTrue(state.prepare(10L, 100L, 100L))
        // 环境拒绝、断连与取消均调用 clear/cancel，不回退 ID 水位。
        assertEquals(10L, state.clear())
        assertFalse(state.prepare(10L, 110L, 110L))
        assertFalse(state.prepare(9L, 110L, 110L))
        assertTrue(state.prepare(11L, 120L, 120L))
        assertTrue(state.cancel(11L))
        assertFalse(state.activate(11L, 130L, 130L))
        assertFalse(state.prepare(11L, 130L, 130L))
        assertEquals(11L, state.lastPreparedId)
    }

    @Test
    fun sessionExpiresAtFifteenSecondsWithoutRenewalByMoves() {
        val state = CornerInputSession()
        state.prepare(1L, 1000L, 1000L)
        state.activate(1L, 1010L, 1010L)
        assertTrue(state.move(1L, 15_999L, 15_999L))
        assertEquals(1L, state.expire(16_000L))
        assertFalse(state.up(1L, 16_001L, 16_001L))
        assertFalse(state.prepare(1L, 16_002L, 16_002L))
        assertEquals(0L, state.expire(16_003L))
    }

    @Test
    fun lateHandlerCannotDeliverFreshCoordinatesAfterSessionDeadline() {
        val state = CornerInputSession()
        state.prepare(1L, 1000L, 1000L)
        state.activate(1L, 1010L, 1010L)
        assertFalse(state.move(1L, 16_000L, 16_000L))
        assertEquals(0L, state.id)
        assertFalse(state.activate(1L, 16_001L, 16_001L))
        assertFalse(state.up(1L, 16_001L, 16_001L))
    }

    @Test
    fun staleFutureAndOutOfOrderEventsCannotDeliverOrAdvanceTheWatermark() {
        val state = CornerInputSession()
        assertFalse(state.prepare(9L, 100L, 601L))
        assertFalse(state.prepare(8L, 200L, 199L))
        assertEquals(0L, state.lastPreparedId)
        state.prepare(1L, 200L, 200L)
        state.activate(1L, 250L, 250L)
        assertFalse(state.move(1L, 249L, 251L))
        assertFalse(state.move(1L, 1000L, 999L))
        assertFalse(state.move(1L, 250L, 751L))
        assertTrue(state.move(1L, 752L, 752L))
    }

    @Test
    fun sampleValidationRejectsStaleCoordinatesAndInvalidGeometry() {
        val state = CornerInputSession()
        assertTrue(state.isSampleValid(100L, 600L, 0f, 2000f, 1000f, 2000f))
        assertFalse(state.isSampleValid(100L, 601L, 0f, 2000f, 1000f, 2000f))
        assertFalse(state.isSampleValid(101L, 100L, 0f, 2000f, 1000f, 2000f))
        assertFalse(state.isSampleValid(100L, 100L, Float.NaN, 0f, 1000f, 2000f))
        assertFalse(state.isSampleValid(100L, 100L, 0f, Float.POSITIVE_INFINITY, 1000f, 2000f))
        assertFalse(state.isSampleValid(100L, 100L, -1f, 0f, 1000f, 2000f))
        assertFalse(state.isSampleValid(100L, 100L, 1001f, 0f, 1000f, 2000f))
        assertFalse(state.isSampleValid(100L, 100L, 0f, 0f, 0f, 2000f))
    }
}
