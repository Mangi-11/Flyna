package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerVisualGateTest {
    private val config = CornerGestureConfig(
        CornerTriggerRegion.create(1000f, 2000f, CornerArc(50f, 1950f, 50f), CornerArc(950f, 1950f, 50f), 84, 1f),
        8f, 8f, true, true,
    )

    @Test
    fun eligibleDownImmediatelyDefersUntilPhysicalTerminal() {
        val gate = CornerVisualGate()
        gate.down(16f, 1984f, config)
        repeat(5) { assertTrue(gate.defersMoves) }
        gate.finish()
        assertFalse(gate.defersMoves)
    }

    @Test
    fun inputOwnerFailureReleasesPredictionWithoutRearming() {
        val gate = CornerVisualGate()
        gate.down(16f, 1984f, config)
        gate.releaseUnclaimed()
        repeat(5) { assertFalse(gate.defersMoves) }
        gate.down(984f, 1984f, config)
        assertTrue(gate.defersMoves)
    }

    @Test
    fun interiorAndBottomCenterDoNotDeferNativeMovement() {
        val gate = CornerVisualGate()
        for ((x, y) in listOf(40f to 1960f, 500f to 1999f, 1f to 1900f)) {
            gate.down(x, y, config)
            assertFalse(gate.defersMoves)
        }
    }
}
