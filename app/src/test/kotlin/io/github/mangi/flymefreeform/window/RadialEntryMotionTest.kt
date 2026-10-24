package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialEntryMotionTest {
    @Test
    fun opensFromTheCornerAndSettlesAtTheFixedGeometry() {
        val start = RadialEntryMotion.sample(0f, angularSpanDegrees = 16f)
        val end = RadialEntryMotion.sample(1f, angularSpanDegrees = 16f)

        assertEquals(0f, start.contentAlpha, 0f)
        assertEquals(0f, start.radialProgress, 0f)
        assertEquals(0f, start.horizontalOvershoot, 0f)
        assertEquals(-120f, start.rotationDegrees, 0f)
        assertEquals(0.78f, start.iconScale, 0f)
        assertEquals(16f, start.angularOffsetDegrees, 0f)
        assertEquals(1f, end.contentAlpha, 0f)
        assertEquals(1f, end.radialProgress, 0f)
        assertEquals(0f, end.horizontalOvershoot, 0f)
        assertEquals(0f, end.rotationDegrees, 0f)
        assertEquals(1f, end.iconScale, 0f)
        assertEquals(0f, end.angularOffsetDegrees, 0f)
    }

    @Test
    fun reachesTheExpandedPositionBeforeTheRebound() {
        val expanded = RadialEntryMotion.sample(220f / 440f, angularSpanDegrees = 18f)

        assertEquals(1f, expanded.contentAlpha, 0.0001f)
        assertEquals(1f, expanded.radialProgress, 0.0001f)
        assertEquals(0.75f, expanded.horizontalOvershoot, 0.0001f)
        assertEquals(4f, expanded.rotationDegrees, 0.0001f)
        assertEquals(1.035f, expanded.iconScale, 0.0001f)
        assertEquals(0f, expanded.angularOffsetDegrees, 0.0001f)
    }

    @Test
    fun preservesGentleRotationReboundAndSmallHorizontalOvershoot() {
        val rotationPeak = RadialEntryMotion.sample(275f / 440f)
        val horizontalRebound = RadialEntryMotion.sample(380f / 440f)

        assertTrue(rotationPeak.rotationDegrees in 7f..14f)
        assertEquals(1f, rotationPeak.radialProgress, 0f)
        assertTrue(horizontalRebound.horizontalOvershoot < 0f)
        assertTrue(horizontalRebound.horizontalOvershoot > -0.1f)
    }

    @Test
    fun keepsTheTwoAnimationPhasesContinuous() {
        val before = RadialEntryMotion.sample(219.999f / 440f, angularSpanDegrees = 14f)
        val after = RadialEntryMotion.sample(220.001f / 440f, angularSpanDegrees = 14f)

        assertEquals(before.contentAlpha, after.contentAlpha, 0.001f)
        assertEquals(before.radialProgress, after.radialProgress, 0.001f)
        assertEquals(before.horizontalOvershoot, after.horizontalOvershoot, 0.001f)
        assertEquals(before.rotationDegrees, after.rotationDegrees, 0.01f)
        assertEquals(before.iconScale, after.iconScale, 0.001f)
        assertEquals(before.angularOffsetDegrees, after.angularOffsetDegrees, 0.001f)
    }

    @Test
    fun finishesPositionAndOpacityBeforeTheRotationSettles() {
        for (millis in 220..440) {
            val visuals = RadialEntryMotion.sample(millis / 440f, angularSpanDegrees = 12f)
            assertEquals(1f, visuals.contentAlpha, 0.0001f)
            assertEquals(1f, visuals.radialProgress, 0.0001f)
            assertEquals(0f, visuals.angularOffsetDegrees, 0.0001f)
            assertTrue(visuals.iconScale in 1f..1.0351f)
        }
    }

    @Test
    fun initialPullCouplingAdvancesProgressMonotonicallyAndConvergesAtRest() {
        assertEquals(0f, RadialEntryMotion.effectiveProgress(0f, 0f), 0f)
        assertTrue(RadialEntryMotion.effectiveProgress(0f, 0.5f) > 0f)
        assertTrue(
            RadialEntryMotion.effectiveProgress(0.2f, 0.8f) >
                RadialEntryMotion.effectiveProgress(0.2f, 0.2f),
        )
        assertEquals(1f, RadialEntryMotion.effectiveProgress(1f, 0f), 0f)
        assertEquals(1f, RadialEntryMotion.effectiveProgress(1f, 1f), 0f)
        for (pullStep in 0..10) {
            val pull = pullStep / 10f
            var previous = 0f
            for (timeStep in 0..100) {
                val value = RadialEntryMotion.effectiveProgress(timeStep / 100f, pull)
                assertTrue(value in previous..1f)
                previous = value
            }
        }
    }

    @Test
    fun staggeredItemsFanOutInOrderAndAllSettleAtFullProgress() {
        val midLower = RadialEntryMotion.sampleItem(0.25f, slot = 0, itemCount = 7)
        val midUpper = RadialEntryMotion.sampleItem(0.25f, slot = 6, itemCount = 7)
        assertTrue(midLower.radialProgress > midUpper.radialProgress)
        assertTrue(midUpper.angularOffsetDegrees > midLower.angularOffsetDegrees)

        for (slot in 0 until 7) {
            val start = RadialEntryMotion.sampleItem(0f, slot, 7)
            val settled = RadialEntryMotion.sampleItem(1f, slot, 7)
            assertEquals(0f, start.radialProgress, 0f)
            assertEquals(1f, settled.radialProgress, 0f)
            assertEquals(0f, settled.angularOffsetDegrees, 0f)
            assertEquals(0f, settled.rotationDegrees, 0f)
            assertEquals(1f, settled.iconScale, 0f)
        }
    }

    @Test
    fun selectionGrowsOnlyTheOuterRingWithoutOvershoot() {
        assertEquals(130L, RadialEntryMotion.SELECTION_DURATION_MILLIS)
        assertEquals(0f, RadialEntryMotion.selectionProgress(0f), 0f)
        assertEquals(1f, RadialEntryMotion.selectionProgress(1f), 0f)
        var previous = 0f
        for (step in 1..100) {
            val progress = RadialEntryMotion.selectionProgress(step / 100f)
            assertTrue(progress in previous..1f)
            previous = progress
        }
    }

    @Test
    fun clampsExternalTimeAtBothEnds() {
        assertEquals(RadialEntryMotion.sample(0f), RadialEntryMotion.sample(-1f))
        assertEquals(RadialEntryMotion.sample(1f), RadialEntryMotion.sample(2f))
        assertEquals(0f, RadialEntryMotion.selectionProgress(-1f), 0f)
        assertEquals(1f, RadialEntryMotion.selectionProgress(2f), 0f)
    }
}
