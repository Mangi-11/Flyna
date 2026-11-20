package io.github.mangi.flyna.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerInputHandoffTest {
    @Test
    fun downClaimsWithoutWaitingForPreparationAndOnlyOnce() {
        val handoff = CornerInputHandoff()
        var claims = 0
        repeat(2) { assertTrue(handoff.claimInput { claims++; true }) }
        assertEquals(1, claims)
        assertTrue(handoff.isClaimed)
        assertFalse(handoff.isActive)
        assertTrue(handoff.activateIfReady(true) {
            throw AssertionError("Menu must await preparation")
        })
    }

    @Test
    fun preparationAndMenuDistanceCanArriveInEitherOrder() {
        for (preparedFirst in listOf(false, true)) {
            val handoff = CornerInputHandoff()
            var activations = 0
            handoff.claimInput { true }
            if (preparedFirst) handoff.acceptPreparation()
            handoff.activateIfReady(!preparedFirst) { activations++; true }
            assertEquals(0, activations)
            handoff.acceptPreparation()
            repeat(2) {
                handoff.activateIfReady(true) { activations++; true }
                handoff.acceptPreparation()
            }
            assertEquals(1, activations)
            assertTrue(handoff.isActive)
        }
    }

    @Test
    fun failedClaimCannotActivateEvenAfterPreparation() {
        val handoff = CornerInputHandoff()
        assertFalse(handoff.claimInput { false })
        handoff.acceptPreparation()
        assertTrue(handoff.activateIfReady(true) {
            throw AssertionError("Menu cannot activate after failed input claim")
        })
        assertFalse(handoff.isClaimed)
        assertFalse(handoff.isActive)
    }

    @Test
    fun failedActivationKeepsClaimSoCleanupDoesNotReviveNativeGestures() {
        val handoff = CornerInputHandoff()
        handoff.claimInput { true }
        handoff.acceptPreparation()
        assertFalse(handoff.activateIfReady(true) { false })
        assertTrue(handoff.isClaimed)
        assertFalse(handoff.isActive)
        handoff.reset()
        assertFalse(handoff.isClaimed)
    }

    @Test
    fun cancellationDropsPreparationAndLateReplyCannotReviveAClosedStream() {
        val handoff = CornerInputHandoff()
        handoff.claimInput { true }
        handoff.acceptPreparation()
        handoff.reset()
        handoff.acceptPreparation()
        assertFalse(handoff.isClaimed)
        handoff.claimInput { true }
        assertTrue(handoff.activateIfReady(true) {
            throw AssertionError("New stream needs its own preparation")
        })
        handoff.acceptPreparation()
        assertTrue(handoff.activateIfReady(true) { true })
        assertTrue(handoff.isActive)
    }
}
