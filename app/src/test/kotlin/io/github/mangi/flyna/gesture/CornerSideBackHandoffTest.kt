package io.github.mangi.flyna.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerSideBackHandoffTest {
    private val region = CornerTriggerRegion.create(
        1000f, 2000f, CornerArc(200f, 1800f, 200f), CornerArc(800f, 1800f, 200f),
        rangeDp = 160, density = 2f, widthDp = 20,
    )
    private val leftDown = CornerStreamIdentity(6, 0, 0, 100L, 60f, 1940f)

    @Test fun cornerDownReleasesBeforeAnyDirectionOrMovementIsKnown() {
        val handoff = CornerSideBackHandoff()
        assertTrue(handoff.request(leftDown, region, true, true, true))
        assertFalse(handoff.request(leftDown, region, true, true, true))
    }

    @Test fun reinjectedDownCannotCancelTheNativeDetectorAgain() {
        val handoff = CornerSideBackHandoff()
        assertTrue(handoff.request(leftDown, region, true, true, true))
        assertFalse(handoff.request(leftDown.copy(), region, true, true, true))
        assertTrue(handoff.request(leftDown.copy(eventTimeMillis = 200L), region, true, true, true))
    }

    @Test fun unavailableOwnerCannotTakeOverLaterInTheSamePhysicalStream() {
        val handoff = CornerSideBackHandoff()
        assertFalse(handoff.request(leftDown, null, true, true, true))
        assertFalse(handoff.request(leftDown, region, true, true, true))
        assertTrue(handoff.request(leftDown.copy(eventTimeMillis = 200L), region, true, true, true))
    }

    @Test fun normalSideBackAndRectangleOutsideArcRemainNative() {
        for (point in listOf(5f to 1000f, 195f to 1805f, 0f to 2000f)) {
            assertFalse(CornerSideBackHandoff().request(
                leftDown.copy(x = point.first, y = point.second), region, true, true, true,
            ))
        }
    }

    @Test fun disabledCornerOtherDisplayAndAlreadyReleasedDownRemainNative() {
        assertFalse(CornerSideBackHandoff().request(leftDown, region, false, true, true))
        assertFalse(CornerSideBackHandoff().request(leftDown.copy(displayId = 1), region, true, true, true))
        assertFalse(CornerSideBackHandoff().request(leftDown, region, true, true, false))
    }

    @Test fun rightCornerUsesTheSameDownOnlyRuleWithIndependentEnablement() {
        val rightDown = leftDown.copy(x = 940f)
        assertTrue(CornerSideBackHandoff().request(rightDown, region, false, true, true))
        assertFalse(CornerSideBackHandoff().request(rightDown, region, true, false, true))
    }
}
