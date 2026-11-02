package de.mitauri.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandExtrasTest {
    @Test fun aSessionThatIsNotPlayingNeverBringsTheSongUp() {
        val presence = MusicPresence(60_000)
        assertFalse(presence.update(false, 1_000))
        assertFalse(presence.update(false, 500_000))
    }

    @Test fun aPauseHoldsForAMinuteEvenIfThePhoneSleptThroughIt() {
        val presence = MusicPresence(60_000)
        assertTrue(presence.update(true, 1_000))
        assertTrue(presence.update(false, 2_000))
        assertTrue(presence.update(false, 61_000))
        // The phone slept all night, so the next check is the first one the app gets to make.
        assertFalse(presence.update(false, 30_000_000))
        assertFalse(presence.update(false, 30_000_001))
    }

    @Test fun playingAgainCancelsThePause() {
        val presence = MusicPresence(60_000)
        presence.update(true, 0)
        presence.update(false, 1_000)
        presence.update(true, 30_000)
        assertNull(presence.millisLeft(30_000))
        assertTrue(presence.update(false, 80_000))
        assertEquals(60_000L, presence.millisLeft(80_000))
    }

    @Test fun clearSpaceIsTheCloserSide() {
        assertEquals(100, clearSpace(500, listOf(100..300, 310..400, 700..900)))
        assertEquals(50, clearSpace(500, listOf(100..300, 550..900)))
    }

    @Test fun clearSpaceIgnoresWhatSpansTheCameraAndEmptyBars() {
        assertNull(clearSpace(500, listOf(0..1000)))
        assertNull(clearSpace(500, emptyList()))
    }

    @Test fun theSideStaysWithinWhatTheContentNeeds() {
        assertEquals(MIN_SIDE, sideFor(20.dp, 29.dp, 2.dp))
        assertEquals(MAX_SIDE, sideFor(400.dp, 29.dp, 2.dp))
        assertEquals(60.dp.value, sideFor(92.5.dp, 29.dp, 2.dp).value, 0.01f)
    }

    private fun activity(category: String?, ongoing: Boolean = true, timer: Boolean = false, title: String = "Max") =
        activityFrom("key", category, ongoing, timer, 5_000, title, "detail")

    @Test fun aCallHasItsStartOnlyOnceItIsAnswered() {
        assertEquals(ActivityKind.CALL, activity("call")?.kind)
        assertNull(activity("call")?.startedAt)
        assertEquals(5_000L, activity("call", timer = true)?.startedAt)
    }

    @Test fun onlyOngoingCallsAndRoutesCount() {
        assertEquals(ActivityKind.NAVIGATION, activity("navigation")?.kind)
        assertNull(activity("call", ongoing = false)) // A missed call.
        assertNull(activity("msg"))
        assertNull(activity(null))
        assertNull(activity("navigation", title = " "))
    }

    @Test fun onlyARunningCountdownCounts() {
        assertEquals("Timer", countdownFrom(true, true, 9_000, 1_000, "", "")?.title)
        assertNull(countdownFrom(true, true, 500, 1_000, "Tea", ""))
        assertNull(countdownFrom(true, false, 9_000, 1_000, "Tea", ""))
        assertNull(countdownFrom(false, true, 9_000, 1_000, "Tea", ""))
    }
}
