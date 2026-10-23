package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerGestureEngineTest {
    private val region = CornerTriggerRegion.create(
        1000f, 2000f, CornerArc(50f, 1950f, 50f), CornerArc(950f, 1950f, 50f), 84, 1f,
    )
    private val config = CornerGestureConfig(region, 8f, 8f, true, true)

    @Test
    fun edgeDownArmsWithoutShowingMenuAndUpwardMovementRevealsOnce() {
        for (right in listOf(false, true)) {
            val engine = CornerGestureEngine()
            val x = if (right) 984f else 16f
            assertEquals(GestureAction.Ignore, engine.down(0, x, 1984f, config, 100L))
            assertEquals(GesturePhase.Armed, engine.phase)
            assertFalse(engine.isMenuReady)
            val action = engine.move(0, 1, x, 1976f, config, 110L)
            assertTrue(action is GestureAction.Activate)
            assertEquals(if (right) CornerSide.Right else CornerSide.Left, (action as GestureAction.Activate).side)
            assertEquals(x, action.originX, 0f)
            assertTrue(engine.isMenuReady)
            assertTrue(engine.move(0, 1, x, 1970f, config, 120L) is GestureAction.Update)
            assertTrue(engine.up(0) is GestureAction.Commit)
        }
    }

    @Test
    fun tapsAndShortSwipesCannotCommitInvisibleSelection() {
        val engine = CornerGestureEngine()
        engine.down(0, 16f, 1984f, config, 100L)
        assertEquals(GestureAction.Ignore, engine.move(0, 1, 20f, 1980f, config, 110L))
        engine.setSelection(0)
        assertEquals(null, engine.selectedIndex)
        assertEquals(GestureAction.Cancel, engine.up(0))
        assertFalse(engine.isMenuReady)
    }

    @Test
    fun shallowSteepAndDelayedUpwardSwipesDoNotNeedAnAngleGate() {
        for ((x, y) in listOf(24f to 1976f, 80f to 1976f, 16f to 1900f)) {
            for (delay in listOf(10L, 353L, 1200L)) {
                val engine = CornerGestureEngine()
                engine.down(0, 16f, 1984f, config, 100L)
                assertTrue(engine.move(0, 1, x, y, config, 100L + delay) is GestureAction.Activate)
            }
        }
    }

    @Test
    fun horizontalStartMayTurnUpwardInsideTheAlreadyOwnedStream() {
        val engine = CornerGestureEngine()
        engine.down(0, 16f, 1984f, config, 100L)
        assertEquals(GestureAction.Ignore, engine.move(0, 1, 40f, 1984f, config, 110L))
        assertTrue(engine.move(0, 1, 40f, 1976f, config, 120L) is GestureAction.Activate)
    }

    @Test
    fun startingOutsideArcCannotEnterAndActivateMidStream() {
        val engine = CornerGestureEngine()
        assertEquals(GestureAction.PassThrough, engine.down(0, 40f, 1960f, config, 100L))
        assertEquals(GestureAction.PassThrough, engine.move(0, 1, 16f, 1984f, config, 110L))
        assertEquals(GestureAction.PassThrough, engine.move(0, 1, 40f, 1950f, config, 120L))
    }

    @Test
    fun multiTouchReverseAndInvalidSamplesEndCandidateWithoutRearming() {
        for ((count, x, y) in listOf(Triple(2, 20f, 1980f), Triple(1, 6f, 1984f), Triple(1, Float.NaN, 1980f))) {
            val engine = CornerGestureEngine()
            engine.down(0, 16f, 1984f, config, 100L)
            engine.move(0, count, x, y, config, 110L)
            assertEquals(GesturePhase.Yielded, engine.phase)
            assertEquals(GestureAction.PassThrough, engine.move(0, 1, 50f, 1950f, config, 120L))
            engine.down(0, 16f, 1984f, config, 130L)
            assertTrue(engine.move(0, 1, 30f, 1970f, config, 140L) is GestureAction.Activate)
        }
    }

    @Test
    fun activatedMenuCancelsOnSecondPointerOrWrongPointer() {
        for ((pointer, count) in listOf(0 to 2, 1 to 1)) {
            val engine = CornerGestureEngine()
            engine.down(0, 16f, 1984f, config, 100L)
            engine.move(0, 1, 30f, 1970f, config, 110L)
            assertEquals(GestureAction.Cancel, engine.move(pointer, count, 40f, 1960f, config, 120L))
            assertFalse(engine.isMenuReady)
        }
    }

    @Test
    fun menuDistanceFollowsTouchSlopAndIgnoresArcLength() {
        val small = AdaptiveCornerGestureConfig.create(region, 10f, 2f, true, true)
        val wide = AdaptiveCornerGestureConfig.create(region.copy(arcLength = 160f), 10f, 2f, true, true)
        val largeSlop = AdaptiveCornerGestureConfig.create(region, 30f, 2f, true, true)
        assertEquals(10f, small.activationDistance, 0f)
        assertEquals(small.activationDistance, wide.activationDistance, 0f)
        assertEquals(30f, largeSlop.activationDistance, 0f)
    }
}
