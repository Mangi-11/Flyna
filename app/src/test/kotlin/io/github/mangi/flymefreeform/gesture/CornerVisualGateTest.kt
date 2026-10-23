package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerVisualGateTest {
    private val config = CornerGestureConfig(
        displayWidth = 1000f,
        displayHeight = 2000f,
        triggerRadius = 100f,
        inwardThreshold = 14f,
        upwardThreshold = 4f,
        reverseTolerance = 8f,
        leftEnabled = true,
        rightEnabled = true,
    )

    @Test
    fun deferCandidateAndRecognizedDiagonalUntilTerminal() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertTrue(gate.move(0, 1, 10f, 1990f, 110L))
        assertTrue(gate.move(0, 1, 25f, 1975f, 120L))
        // 两个原生重载收到同一 MOVE，重复查询不能改变视觉归属。
        assertTrue(gate.move(0, 1, 25f, 1975f, 120L))
        assertTrue(gate.move(0, 1, 80f, 1920f, 1000L))
        gate.finish()
        assertFalse(gate.move(0, 1, 90f, 1910f, 1010L))
    }

    @Test
    fun yieldingReleasesTheCurrentMoveAndAllLaterMoves() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertFalse(gate.move(0, 1, -5f, 1995f, 125L))
        assertFalse(gate.move(0, 1, 100f, 1800f, 150L))
    }

    @Test
    fun ordinaryTapOutsideTouchAndLongHoldLeaveNoDeferral() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        gate.finish()
        assertFalse(gate.move(0, 1, 20f, 1980f, 120L))
        gate.down(0, 500f, 1000f, config, 200L)
        assertFalse(gate.move(0, 1, 525f, 975f, 210L))
        gate.down(0, 5f, 1995f, config, 300L)
        assertFalse(gate.move(0, 1, 25f, 1975f, 800L))
    }

    @Test
    fun multiplePointersAndCancellationImmediatelyReleaseVisualGate() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertFalse(gate.move(0, 2, 10f, 1990f, 110L))
        assertFalse(gate.move(0, 1, 25f, 1975f, 120L))
        gate.down(0, 5f, 1995f, config, 200L)
        assertTrue(gate.move(0, 1, 25f, 1975f, 210L))
        gate.cancel()
        assertFalse(gate.move(0, 1, 40f, 1960f, 220L))
    }

    @Test
    fun recognizedStreamNeverRestartsCompetingAnimationOnReverseOrMultiplePointers() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertTrue(gate.move(0, 1, 25f, 1975f, 110L))
        assertTrue(gate.move(0, 1, -20f, 2020f, 120L))
        gate.pointerDown()
        assertTrue(gate.move(0, 2, 30f, 1965f, 130L))
        assertTrue(gate.move(0, 1, 40f, 1960f, 1000L))
        gate.finish()
        assertFalse(gate.move(0, 1, 50f, 1950f, 1010L))
    }

    @Test
    fun pointerDownWhileCandidateYieldsWithoutLateReactivation() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertTrue(gate.move(0, 1, 10f, 1990f, 110L))
        gate.pointerDown()
        assertFalse(gate.move(0, 1, 25f, 1975f, 120L))
    }

    @Test
    fun disablingOnlyYieldsCandidatesAndDoesNotReviveAnAlreadyRecognizedStream() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        gate.cancelCandidate()
        assertFalse(gate.move(0, 1, 25f, 1975f, 110L))
        gate.down(0, 5f, 1995f, config, 200L)
        assertTrue(gate.move(0, 1, 25f, 1975f, 210L))
        gate.cancelCandidate()
        assertTrue(gate.move(0, 1, 40f, 1960f, 220L))
        gate.finish()
        assertFalse(gate.move(0, 1, 50f, 1950f, 230L))
    }

    @Test
    fun ownerRejectionReleasesPredictedRecognitionWithoutRearmingTheStream() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertTrue(gate.move(0, 1, 25f, 1975f, 110L))
        gate.releaseUnclaimed()
        assertFalse(gate.move(0, 1, 30f, 1970f, 120L))
        assertFalse(gate.move(0, 1, 40f, 1960f, 130L))
        gate.down(0, 5f, 1995f, config, 200L)
        assertTrue(gate.move(0, 1, 25f, 1975f, 210L))
    }

    @Test
    fun claimedStreamWithoutOwnerRejectionRemainsDeferredUntilTerminal() {
        val gate = CornerVisualGate()
        gate.down(0, 5f, 1995f, config, 100L)
        assertTrue(gate.move(0, 1, 25f, 1975f, 110L))
        // 实际所有者已经抢占时不得发送 releaseUnclaimed；环境变化只取消候选。
        gate.cancelCandidate()
        gate.pointerDown()
        assertTrue(gate.move(0, 2, 5f, 1995f, 500L))
        gate.finish()
        assertFalse(gate.move(0, 1, 30f, 1970f, 510L))
    }
}
