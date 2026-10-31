package de.mitauri.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
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

    @Test fun chatEmpty() = show { chat(emptyList()) }
    @Test fun chatConversation() = show { chat(conversation, draft = "Danke!", waiting = true) }

    @Test fun settings() = show {
        SettingsScreen(
            listOf(
                SetupItem("Power button", "Make Mitauri your default assistant", true) {},
                SetupItem("Microphone", "For voice mode", true) {},
                SetupItem("Background location", "Choose Allow all the time", false, required = false) {},
                SetupItem("Autostart", "On Xiaomi: turn on Autostart for Mitauri", null) {},
            ),
            {}, {}, {}, {}, false, {}, {},
        )
    }
}
