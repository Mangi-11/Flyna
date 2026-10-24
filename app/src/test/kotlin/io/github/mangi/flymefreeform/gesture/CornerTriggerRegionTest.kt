package io.github.mangi.flymefreeform.gesture

import androidx.compose.ui.geometry.Offset
import io.github.mangi.flymefreeform.ui.CornerBandContour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CornerTriggerRegionTest {
    private fun region(rangeDp: Int = 84, density: Float = 1f) = CornerTriggerRegion.create(
        1000f, 2000f, CornerArc(50f, 1950f, 50f), CornerArc(950f, 1950f, 50f), rangeDp, density,
    )

    @Test
    fun arcLengthUsesClampedDpAndThicknessDoesNotGrowWithRange() {
        assertEquals(48f, region(8, 2f).arcLength, 0f)
        assertEquals(168f, region(84, 2f).arcLength, 0f)
        assertEquals(320f, region(200, 2f).arcLength, 0f)
        assertEquals(24f, region(8, 2f).thickness, 0f)
        assertEquals(24f, region(200, 2f).thickness, 0f)
    }

    @Test
    fun onlyTheInnerArcBandMatchesAndBothCornersMirror() {
        val region = region()
        for (angle in listOf(0.1, PI / 4, PI / 2 - 0.1)) {
            val x = 50f - 48f * cos(angle).toFloat()
            val y = 1950f + 48f * sin(angle).toFloat()
            assertEquals(CornerSide.Left, region.detectSide(x, y, true, true))
            assertEquals(CornerSide.Right, region.detectSide(1000f - x, y, true, true))
            assertNull(region.detectSide(x, y, false, true))
            assertNull(region.detectSide(1000f - x, y, true, false))
        }
    }

    @Test
    fun interiorStraightBottomSideAndOutsideGlassAreExcluded() {
        val region = region()
        for ((x, y) in listOf(40f to 1960f, 80f to 1999f, 1f to 1900f, 0f to 1999f, 500f to 1999f)) {
            assertNull(region.detectSide(x, y, true, true))
            assertNull(region.detectSide(1000f - x, y, true, true))
        }
        assertNull(region.detectSide(Float.NaN, 1984f, true, true))
        assertNull(region.detectSide(16f, Float.POSITIVE_INFINITY, true, true))
    }

    @Test
    fun shorterArcStaysCenteredOnTheCornerWithoutBecomingAThickerBand() {
        assertEquals(CornerSide.Left, region(24).detectSide(16f, 1984f, true, true))
        assertNull(region(24).detectSide(2f, 1951f, true, true))
        assertEquals(CornerSide.Left, region(84).detectSide(2f, 1951f, true, true))
        for (range in listOf(24, 84, 92)) {
            assertNull(region(range).detectSide(25f, 1975f, true, true))
        }
    }

    @Test
    fun missingRoundedCornersDoNotInventFallbackRegions() {
        val region = region().copy(left = null)
        assertNull(region.detectSide(16f, 1984f, true, true))
        assertEquals(CornerSide.Right, region.detectSide(984f, 1984f, true, true))
        assertNull(region.copy(right = null).detectSide(984f, 1984f, true, true))
    }

    @Test
    fun deviceReportedCornerGeometryMatchesItsThinBand() {
        val region = CornerTriggerRegion.create(
            1440f, 3136f, CornerArc(203f, 2933f, 203f), CornerArc(1237f, 2933f, 203f), 84, 3.5f,
        )
        val offset = (198f / kotlin.math.sqrt(2f))
        assertEquals(CornerSide.Left, region.detectSide(203f - offset, 2933f + offset, true, true))
        assertEquals(CornerSide.Right, region.detectSide(1237f + offset, 2933f + offset, true, true))
        assertNull(region.detectSide(203f, 2933f, true, true))
        assertNull(region.detectSide(0f, 3135f, true, true))
    }

    @Test
    fun edgePressAllowsContactCenterInsetButStillExcludesTheCornerInterior() {
        val region = CornerTriggerRegion.create(
            1440f, 3136f, CornerArc(203f, 2933f, 203f), CornerArc(1237f, 2933f, 203f), 84, 3.5f,
        )
        for (inset in listOf(4f, 13f, 22f, 32f, 38f)) {
            val offset = (203f - inset) / kotlin.math.sqrt(2f)
            assertEquals(CornerSide.Left, region.detectSide(203f - offset, 2933f + offset, true, true))
            assertEquals(CornerSide.Right, region.detectSide(1237f + offset, 2933f + offset, true, true))
        }
        val outside = (203f - 44f) / kotlin.math.sqrt(2f)
        assertNull(region.detectSide(203f - outside, 2933f + outside, true, true))
    }

    @Test
    fun widthChangesOnlyTheInnerBoundaryAndLengthChangesOnlyTheSweep() {
        val arc = CornerArc(203f, 2933f, 203f)
        val narrow = arc.band(CornerSide.Left, 14f, 84f)
        val wide = arc.band(CornerSide.Left, 42f, 84f)
        val longer = arc.band(CornerSide.Left, 42f, 294f)
        assertEquals(narrow.outerRadius, wide.outerRadius, 0f)
        assertEquals(narrow.startAngle, wide.startAngle, 0f)
        assertEquals(narrow.sweepAngle, wide.sweepAngle, 0f)
        assertEquals(28f, narrow.innerRadius - wide.innerRadius, 0f)
        assertEquals(wide.innerRadius, longer.innerRadius, 0f)
        org.junit.Assert.assertTrue(longer.sweepAngle > wide.sweepAngle)
        val offset = 180f / kotlin.math.sqrt(2f)
        org.junit.Assert.assertFalse(narrow.contains(203f - offset, 2933f + offset))
        org.junit.Assert.assertTrue(wide.contains(203f - offset, 2933f + offset))
    }

    @Test
    fun displayArcStrokeUsesTheExactHitTestBoundariesAndCapsAtQuarterCircle() {
        val band = CornerArc(203f, 2933f, 203f).band(CornerSide.Left, 42f, 1000f)
        val strokeRadius = (band.outerRadius + band.innerRadius) / 2f
        val strokeWidth = band.outerRadius - band.innerRadius
        assertEquals(203f, strokeRadius + strokeWidth / 2f, 0f)
        assertEquals(161f, strokeRadius - strokeWidth / 2f, 0f)
        assertEquals(90f, band.startAngle, 0f)
        assertEquals(90f, band.sweepAngle, 0f)
    }

    @Test
    fun realDeviceLengthRangeAllowsExtensionsAtDifferentDensities() {
        val region = CornerTriggerRegion.create(
            1440f, 3136f, CornerArc(203f, 2933f, 203f), CornerArc(1237f, 2933f, 203f), 84, 3.5f,
        )
        assertEquals(160, region.maxArcLengthDp(3.5f))
        assertEquals(160, region.maxArcLengthDp(4.0f))
        val subMaxBand = CornerArc(203f, 2933f, 203f).band(CornerSide.Left, 42f, 91 * 3.5f)
        val maxBand = CornerArc(203f, 2933f, 203f).band(CornerSide.Left, 42f, 92 * 3.5f)
        assertTrue(subMaxBand.sweepAngle < 90f)
        assertEquals(90f, maxBand.sweepAngle, 0f)
    }

    @Test
    fun longerBandExtendsAlongBothEdgesWithoutWideningOrClaimingTheInterior() {
        val region = CornerTriggerRegion.create(
            1440f, 3136f, CornerArc(203f, 2933f, 203f), CornerArc(1237f, 2933f, 203f), 160, 3.5f, 10,
        )
        val band = requireNotNull(region.band(CornerSide.Left))
        assertEquals((560f - 203f * (PI / 2).toFloat()) / 2f, band.extensionLength, 0.001f)
        assertEquals(35f, band.outerRadius - band.innerRadius, 0f)
        for ((x, y) in listOf(16f to 2850f, 280f to 3120f, 16f to 2933f, 203f to 3120f)) {
            assertEquals(CornerSide.Left, region.detectSide(x, y, true, true))
            assertEquals(CornerSide.Right, region.detectSide(1440f - x, y, true, true))
            assertNull(region.detectSide(x, y, false, true))
            assertNull(region.detectSide(1440f - x, y, true, false))
        }
        for ((x, y) in listOf(36f to 2850f, 280f to 3100f, 100f to 2900f, 0f to 3135f, 720f to 3120f)) {
            assertNull(region.detectSide(x, y, true, true))
            assertNull(region.detectSide(1440f - x, y, true, true))
        }
    }

    @Test
    fun extensionEndpointsAndInputWindowBoundsGrowAndShrinkWithLength() {
        val region = CornerTriggerRegion.create(
            1440f, 3136f, CornerArc(203f, 2933f, 203f), CornerArc(1237f, 2933f, 203f), 160, 3.5f, 10,
        )
        val left = requireNotNull(region.band(CornerSide.Left))
        val right = requireNotNull(region.band(CornerSide.Right))
        assertTrue(left.contains(16f, left.bounds.top))
        assertTrue(left.contains(left.bounds.right, 3120f))
        assertTrue(right.contains(1424f, right.bounds.top))
        assertTrue(right.contains(right.bounds.left, 3120f))
        assertNull(region.detectSide(16f, left.bounds.top - 1f, true, true))
        assertNull(region.detectSide(left.bounds.right + 1f, 3120f, true, true))
        assertNull(region.detectSide(1424f, right.bounds.top - 1f, true, true))
        assertNull(region.detectSide(right.bounds.left - 1f, 3120f, true, true))
        assertEquals(0f, left.bounds.left, 0f)
        assertEquals(3136f, left.bounds.bottom, 0f)
        assertEquals(1440f, right.bounds.right, 0f)
        assertEquals(1440f - left.bounds.right, right.bounds.left, 0.001f)
        val short = region.copy(arcLength = 84 * 3.5f)
        assertNull(short.detectSide(16f, 2850f, true, true))
        assertNull(short.detectSide(280f, 3120f, true, true))
        assertEquals(203f, requireNotNull(short.band(CornerSide.Left)).bounds.right, 0f)
        assertEquals(2933f, requireNotNull(short.band(CornerSide.Left)).bounds.top, 0f)
    }

    @Test
    fun narrowDisplaysLimitExtensionsToTheirOwnHalfOfTheScreen() {
        val region = CornerTriggerRegion.create(
            140f, 200f, CornerArc(50f, 150f, 50f), CornerArc(90f, 150f, 50f), 160, 1f,
        )
        assertEquals(119, region.maxArcLengthDp(1f))
        val left = requireNotNull(region.band(CornerSide.Left))
        val right = requireNotNull(region.band(CornerSide.Right))
        assertEquals(20f, left.extensionLength, 0.001f)
        assertEquals(70f, left.bounds.right, 0.001f)
        assertEquals(70f, right.bounds.left, 0.001f)
        assertNull(region.detectSide(71f, 195f, true, false))
        assertNull(region.detectSide(69f, 195f, false, true))
        assertNull(region.detectSide(5f, 129f, true, true))
    }

    @Test
    fun previewScalesExtensionsAndInputBoundsTogether() {
        val arc = CornerArc(203f, 2933f, 203f)
        val band = arc.band(CornerSide.Left, 35f, 560f)
        val contour = requireNotNull(CornerBandContour.from(band, 0.5f, Offset(10f, 20f)))
        assertEquals(CornerSide.Left, contour.side)
        assertEquals(band.extensionLength * 0.5f, contour.extensionLength, 0f)
        assertEquals(20f + band.bounds.top * 0.5f, contour.centerY - contour.extensionLength, 0.001f)
        assertEquals(10f + band.bounds.right * 0.5f, contour.centerX + contour.extensionLength, 0.001f)
    }

    @Test
    fun previewContourKeepsRoundedFilletsInsideBandAngularAndRadialBounds() {
        val band = CornerArc(203f, 2933f, 203f).band(CornerSide.Left, 42f, 294f)
        val contour = CornerBandContour.from(band, scale = 1f, origin = Offset.Zero)
        assertNotNull(contour)
        requireNotNull(contour)
        assertTrue(contour.hasRoundedCorners)
        assertTrue(contour.filletRadius > 0f && contour.filletRadius < 21f)
        assertTrue(contour.outerCornerAngleOffset * 2f < contour.sweepAngle)
        assertTrue(contour.innerCornerAngleOffset * 2f < contour.sweepAngle)
    }
}
