package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerBufferedInputReleaseTest {
    private val down = CornerStreamIdentity(6, 0, 0, 100L, 40f, 1900f)

    @Test fun candidateCanReleaseBeforeNativeTimeoutButOnlyOnce() {
        val release = CornerBufferedInputRelease()
        release.begin(down)
        assertTrue(release.request(down))
        assertFalse(release.request(down))
    }

    @Test fun reinjectedAliasDoesNotRearmAnAlreadyRequestedRelease() {
        val release = CornerBufferedInputRelease()
        release.begin(down)
        assertTrue(release.request(down))
        release.begin(down.copy())
        assertFalse(release.request(down))
    }

    @Test fun oldAndOtherDisplayEventsCannotReleaseCurrentStream() {
        val release = CornerBufferedInputRelease()
        release.begin(down)
        assertFalse(release.request(down.copy(displayId = 1)))
        val next = down.copy(eventTimeMillis = 200L)
        release.begin(next)
        assertFalse(release.request(down))
        assertTrue(release.request(next))
    }

    @Test fun terminalDropsBufferedOwnership() {
        val release = CornerBufferedInputRelease()
        release.begin(down)
        release.finish()
        assertFalse(release.matches(down))
        assertFalse(release.request(down))
    }
}
