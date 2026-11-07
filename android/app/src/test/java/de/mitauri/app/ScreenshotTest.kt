package de.mitauri.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders screens on this computer, with made-up data, so the design can be checked without a phone. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:Theme.Material.NoActionBar")

    private fun show(content: @Composable () -> Unit) = paparazzi.snapshot {
        MitauriTheme {
            Surface(Modifier.fillMaxSize(), color = MitauriColors.Background, contentColor = MitauriColors.Ink) { content() }
        }
    }

    private val conversation = listOf(
        ChatMessage("Wann fährt der nächste Bus an der Bahnhofstraße?", true),
        ChatMessage("Die 146 fährt in 4 Minuten Richtung Hauptbahnhof, heute mit 2 Minuten Verspätung. Die nächste kommt um 15:42.", false),
        ChatMessage("Erinner mich dann fünf Minuten vorher", true),
        ChatMessage("Mach ich.", false),
    )

    @Composable
    private fun chat(messages: List<ChatMessage>, draft: String = "", waiting: Boolean = false) = ChatContent(
        messages, Status.CONNECTED, waiting, needsSetup = true, draft = draft,
        onDraft = {}, onDoor = {}, onNewChat = {}, onSettings = {}, onVoice = {}, onPhoto = {}, onSend = {},
    )

    @Test fun chatEmpty() {
        currentHour = { 12 }
        show { chat(emptyList()) }
    }
    @Test fun chatConversation() = show { chat(conversation, draft = "Danke!", waiting = true) }

    @Test fun modules() = show {
        ModulesContent(
            listOf(
                ModuleInfo("core", "Core", "Memory, reminders and deeper thinking", true, true),
                ModuleInfo("door", "Door", "Opening the door", false, true),
                ModuleInfo("stocks", "Stocks", "Share prices", false, false),
                ModuleInfo("school", "School", "WebUntis timetable and the wake-up alarm", false, true),
            ),
            null, {}, { _, _ -> },
        )
    }

    // The user's phone: a hole 26 dp down, with the pill starting 9 dp down. Drawn in grey to check the alignment.
    private val camera = CameraHole(29.dp, 26.dp)

    private fun onScreen(pill: @Composable () -> Unit) = show {
        Box(Modifier.padding(top = 9.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) { pill() }
        Box(Modifier.padding(top = 11.5.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.size(29.dp)) { drawCircle(Color.DarkGray, radius = size.minDimension / 4) }
        }
    }

    private fun pill(live: LiveCountdown, expanded: Boolean, seconds: Long) = onScreen {
        Pill(IslandMode.LIVE, "", true, live, expanded, clock = { live.untilMillis - seconds * 1000 }, camera = camera) {}
    }

    private val song = NowPlaying("Bohemian Rhapsody - Remastered 2011", "Queen", null, true)

    @Test fun islandMusic() = onScreen { Pill(IslandMode.MUSIC, "", true, music = song, camera = camera) {} }
    @Test fun islandMusicExpanded() = onScreen {
        val paused = song.copy(playing = false, accent = Color(0xFFE79AD8), positionMillis = 98_000, durationMillis = 354_000)
        Pill(IslandMode.MUSIC, "", true, expanded = true, music = paused, camera = camera) {}
    }
    @Test fun islandListening() = onScreen { Pill(IslandMode.LISTENING, "Listening", true, camera = camera) {} }
    @Test fun islandThinking() = onScreen { Pill(IslandMode.THINKING, "Thinking", true, camera = camera) {} }
    @Test fun islandSpeaking() = onScreen { Pill(IslandMode.SPEAKING, "Speaking", true, camera = camera) {} }
    @Test fun islandMessage() = onScreen { Pill(IslandMode.MESSAGE, "Opening door, tap to cancel", true, camera = camera) {} }

    @Test fun islandMusicNarrow() = onScreen { Pill(IslandMode.MUSIC, "", true, music = song, camera = camera.copy(side = MIN_SIDE)) {} }
    @Test fun islandMusicWide() = onScreen { Pill(IslandMode.MUSIC, "", true, music = song, camera = camera.copy(side = MAX_SIDE)) {} }

    private val answered = OngoingActivity(ActivityKind.CALL, "a", "Max Mustermann", "Mobil", 10_000L)
    private val routeStep = OngoingActivity(ActivityKind.NAVIGATION, "b", "In 200 m rechts", "Hauptstraße, noch 12 min")

    @Test fun islandCall() = onScreen { Pill(IslandMode.CALL, "", true, clock = { 75_000L }, camera = camera, activity = answered) {} }
    @Test fun islandIncoming() = onScreen { Pill(IslandMode.CALL, "", true, camera = camera, activity = answered.copy(startedAt = null, incoming = true)) {} }
    @Test fun islandCalling() = onScreen { Pill(IslandMode.CALL, "", true, camera = camera, activity = answered.copy(startedAt = null)) {} }

    private fun button(title: String) = ActivityAction(title, null)

    @Test fun islandIncomingExpanded() = onScreen {
        val ringing = answered.copy(startedAt = null, incoming = true, actions = listOf(button("Decline"), button("Answer")))
        Pill(IslandMode.CALL, "", true, expanded = true, camera = camera, activity = ringing) {}
    }
    @Test fun islandCallExpandedWithButtons() = onScreen {
        val talking = answered.copy(actions = listOf(button("Speaker"), button("Mute"), button("Hang up")))
        Pill(IslandMode.CALL, "", true, expanded = true, clock = { 75_000L }, camera = camera, activity = talking) {}
    }
    @Test fun islandRouteExpanded() = onScreen { Pill(IslandMode.NAVIGATION, "", true, expanded = true, camera = camera, activity = routeStep) {} }
    @Test fun islandCallExpanded() = onScreen { Pill(IslandMode.CALL, "", true, expanded = true, clock = { 75_000L }, camera = camera, activity = answered) {} }
    @Test fun islandRoute() = onScreen { Pill(IslandMode.NAVIGATION, "", true, camera = camera, activity = routeStep) {} }

    private fun alert(kind: AlertKind, text: String) = onScreen { Pill(IslandMode.ALERT, "", true, camera = camera, alert = IslandAlert(kind, text)) {} }

    @Test fun islandIdle() = onScreen { Pill(IslandMode.IDLE, "", true, camera = camera) {} }
    @Test fun islandIdleMic() = onScreen { Pill(IslandMode.IDLE, "", true, camera = camera, indicator = MitauriColors.Warning) {} }
    @Test fun islandStopwatch() = onScreen {
        Pill(IslandMode.RUNNING, "", true, clock = { 75_000L }, camera = camera, activity = OngoingActivity(ActivityKind.ELAPSED, "c", "Stopwatch", "", 10_000L)) {}
    }
    @Test fun islandProgress() = onScreen {
        Pill(IslandMode.RUNNING, "", true, camera = camera, activity = OngoingActivity(ActivityKind.PROGRESS, "d", "Update", "", progress = 0.62f)) {}
    }
    @Test fun islandHotspot() = alert(AlertKind.HOTSPOT, "Hotspot")
    @Test fun islandCharging() = alert(AlertKind.CHARGING, "73%")
    @Test fun islandLowBattery() = alert(AlertKind.LOW_BATTERY, "12%")
    @Test fun islandSilent() = alert(AlertKind.SILENT, "Silent")
    @Test fun islandHeadphones() = alert(AlertKind.HEADPHONES, "Sony WH-1000XM4")
    @Test fun islandUnlocked() = alert(AlertKind.UNLOCKED, "Unlocked")

    private val timer = LiveCountdown("Pizza", 10_000_000L, "")
    private val leave = LiveCountdown("Losgehen", 10_000_000L, "S 1 ab Pasing um 07:42")

    @Test fun islandCompact() = pill(timer, false, 754)
    @Test fun islandExpanded() = pill(leave, true, 322)
    @Test fun islandFinished() = pill(timer, false, 0)

    @Test fun aBeatStandsOutFromSteadySound() {
        val meter = BandMeter()
        repeat(200) { meter.level(20.0) }
        val steady = meter.level(20.0)
        val beat = meter.level(40.0)
        assert(steady < 0.2f) { "steady was $steady" }
        assert(beat > 0.8f) { "beat was $beat" }
        repeat(600) { meter.level(0.0) }
        assertEquals(0f, meter.level(0.0), 0.01f)
    }

    @Test fun songTimes() {
        assertEquals("0:00", songTime(-5))
        assertEquals("5:54", songTime(354_000))
    }

    @Test fun countdownFormats() {
        assertEquals("Now", countdownText(0))
        assertEquals("Now", countdownText(-5000))
        assertEquals("0:01", countdownText(1))
        assertEquals("12:34", countdownText(754_000))
        assertEquals("1 h 05", countdownText(3_900_000))
    }

    @Test fun settings() = show {
        SettingsScreen(
            listOf(
                SetupItem("Power button", "Make Mitauri your default assistant", true) {},
                SetupItem("Microphone", "For voice mode", true) {},
                SetupItem("Background location", "Choose Allow all the time", false, required = false) {},
                SetupItem("Autostart", "On Xiaomi: turn on Autostart for Mitauri", null) {},
            ),
            {}, {}, {}, {}, {}, {}, {}, false, {}, {},
        )
    }
}
