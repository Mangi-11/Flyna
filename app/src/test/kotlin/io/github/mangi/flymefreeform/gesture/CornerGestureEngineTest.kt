package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerGestureEngineTest {
    private val config =
        CornerGestureConfig(
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
    fun leftAndRightRequireMirroredInwardMovement() {
        val left = CornerGestureEngine()
        left.down(0, 5f, 1995f, config, 0L)
        assertTrue(left.move(0, 1, 20f, 1989f, config, 0L) is GestureAction.Activate)

        val right = CornerGestureEngine()
        right.down(0, 995f, 1995f, config, 0L)
        assertTrue(right.move(0, 1, 980f, 1989f, config, 0L) is GestureAction.Activate)
    }

    @Test
    fun reverseAndSecondPointerCancelWithoutClaiming() {
        val reverse = CornerGestureEngine()
        reverse.down(0, 5f, 1995f, config, 0L)
        assertTrue(reverse.move(0, 1, -5f, 1995f, config, 0L) is GestureAction.PassThrough)
        assertFalse(reverse.isClaimed)

        val multi = CornerGestureEngine()
        multi.down(0, 5f, 1995f, config, 0L)
        assertTrue(multi.move(0, 2, 30f, 1980f, config, 0L) is GestureAction.PassThrough)
        assertFalse(multi.isClaimed)
    }

    @Test
    fun repeatedMovesActivateOnlyOnceThenUpdate() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, 0L)
        val activation = engine.move(0, 1, 25f, 1985f, config, 0L)
        assertTrue(activation is GestureAction.Activate)
        activation as GestureAction.Activate
        assertEquals(CornerSide.Left, activation.side)
        assertEquals(5f, activation.originX, 0f)
        assertEquals(1995f, activation.originY, 0f)
        assertEquals(25f, activation.x, 0f)
        assertEquals(1985f, activation.y, 0f)
        assertTrue(engine.move(0, 1, 35f, 1975f, config, 0L) is GestureAction.Update)
        assertTrue(engine.up(0) is GestureAction.Commit)
    }

    @Test
    fun tapAndSubThresholdMovementNeverClaimInput() {
        val engine = CornerGestureEngine()

        assertTrue(engine.down(0, 5f, 1995f, config, 0L) is GestureAction.PassThrough)
        assertTrue(engine.move(0, 1, 15f, 1992f, config, 0L) is GestureAction.PassThrough)
        assertTrue(engine.up(0) is GestureAction.PassThrough)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun cancelAfterActivationReportsCancellationAndResets() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, 0L)
        engine.move(0, 1, 25f, 1985f, config, 0L)
        assertTrue(engine.cancel() is GestureAction.Cancel)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun secondPointerCancelsAnActivatedMenu() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, 0L)
        engine.move(0, 1, 25f, 1985f, config, 0L)

        assertTrue(engine.move(0, 2, 30f, 1980f, config, 0L) is GestureAction.Cancel)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun diagonalActivationKeepsOriginalThresholdsAndIsNotExpiredAfterClaim() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, 100L)
        assertEquals(GestureAction.PassThrough, engine.move(0, 1, 18f, 1982f, config, 110L))
        assertTrue(engine.move(0, 1, 19f, 1981f, config, 120L) is GestureAction.Activate)
        assertTrue(engine.move(0, 1, 100f, 1900f, config, 1000L) is GestureAction.Update)
        assertTrue(engine.up(0) is GestureAction.Commit)
    }

    @Test
    fun secondPointerAndReverseYieldPermanentlyUntilNewDown() {
        for (reverse in listOf(false, true)) {
            val engine = CornerGestureEngine()
            engine.down(0, 5f, 1995f, config, 100L)
            engine.move(0, if (reverse) 1 else 2, if (reverse) -5f else 6f, 1990f, config, 110L)
            assertEquals(GesturePhase.Yielded, engine.phase)
            assertEquals(GestureAction.PassThrough, engine.move(0, 1, 50f, 1950f, config, 120L))
            engine.down(0, 5f, 1995f, config, 130L)
            assertTrue(engine.move(0, 1, 25f, 1975f, config, 140L) is GestureAction.Activate)
        }
    }

    @Test
    fun delayedAndSlowCornerSwipesKeepOriginalActivationThresholds() {
        for (delay in listOf(250L, 353L, 356L, 600L, 1200L)) {
            val engine = CornerGestureEngine()
            engine.down(0, 5f, 1995f, config, 100L)
            assertTrue(engine.move(0, 1, 19f, 1991f, config, 100L + delay) is GestureAction.Activate)
        }
    }

    @Test
    fun shallowAndSteepSwipesOnlyRequireBothOriginalDistances() {
        for ((x, y) in listOf(19f to 1991f, 65f to 1991f, 19f to 1900f)) {
            val engine = CornerGestureEngine()
            engine.down(0, 5f, 1995f, config, 100L)
            assertTrue(engine.move(0, 1, x, y, config, 160L) is GestureAction.Activate)
        }
    }

    @Test
    fun startsAlongBottomAndSideEdgesUseTheSameQuarterCircleRange() {
        for ((x, y) in listOf(2f to 1950f, 50f to 1998f, 5f to 1995f)) {
            for (right in listOf(false, true)) {
                val startX = if (right) config.displayWidth - x else x
                val engine = CornerGestureEngine()
                engine.down(0, startX, y, config, 100L)
                assertEquals(GesturePhase.Armed, engine.phase)
                val moveX = startX + if (right) -14f else 14f
                assertTrue(engine.move(0, 1, moveX, y - 4f, config, 700L) is GestureAction.Activate)
            }
        }
    }

    @Test
    fun straightMovementWaitsForTheOtherOriginalThreshold() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, 100L)
        assertEquals(GestureAction.PassThrough, engine.move(0, 1, 5f, 1950f, config, 150L))
        assertEquals(GesturePhase.Armed, engine.phase)
        assertTrue(engine.move(0, 1, 19f, 1950f, config, 450L) is GestureAction.Activate)
    }

    @Test
    fun configuredRangeUsesDpWhileMovementThresholdsIgnoreRange() {
        val compact =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 800f,
                displayHeight = 1600f,
                touchSlop = 10f,
                density = 2f,
                triggerRangeDp = 84,
                leftEnabled = true,
                rightEnabled = true,
            )
        val wide =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 1600f,
                displayHeight = 2400f,
                touchSlop = 10f,
                density = 2f,
                triggerRangeDp = 160,
                leftEnabled = true,
                rightEnabled = true,
            )
        val largerTouchSlop =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 800f,
                displayHeight = 1600f,
                touchSlop = 30f,
                density = 2f,
                triggerRangeDp = 84,
                leftEnabled = true,
                rightEnabled = true,
            )

        assertTrue(wide.triggerRadius > compact.triggerRadius)
        assertTrue(largerTouchSlop.triggerRadius == compact.triggerRadius)
        assertTrue(wide.inwardThreshold == compact.inwardThreshold)
        assertTrue(wide.upwardThreshold == compact.upwardThreshold)
        assertTrue(largerTouchSlop.inwardThreshold > compact.inwardThreshold)
    }
}
