package de.mitauri.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.time.LocalTime

/** Nothing is ever sent without you seeing the exact text and proving it's you. */
class ReplyActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val key = intent.getStringExtra("key") ?: return finish()
        val app = intent.getStringExtra("app").orEmpty()
        val to = intent.getStringExtra("to").orEmpty()
        val draft = intent.getStringExtra("text").orEmpty()
        setContent {
            MitauriTheme {
                Surface(Modifier.fillMaxSize(), color = MitauriColors.Background, contentColor = MitauriColors.Ink) {
                    var text by remember { mutableStateOf(draft) }
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Send this reply?", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "To $to · $app",
                            Modifier
                                .fillMaxWidth()
                                .background(MitauriColors.Surface, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                        )
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Message") })
                        if (LocalTime.now().hour in LATE_NIGHT_HOURS) {
                            Text(
                                "It's the middle of the night. Do you really want to send this now? It may look different in the morning.",
                                Modifier
                                    .fillMaxWidth()
                                    .background(MitauriColors.Surface, RoundedCornerShape(14.dp))
                                    .padding(14.dp),
                                color = MitauriColors.Warning,
                            )
                        }
                        Text("Mitauri suggested this. Nothing is sent until you confirm.", color = MitauriColors.Muted, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = ::finish) { Text("Cancel", color = MitauriColors.AccentSoft) }
                            Button(onClick = { confirm(key, text) }, enabled = text.isNotBlank()) { Text("Confirm and send") }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val LATE_NIGHT_HOURS = 1..5
    }

    private fun confirm(key: String, text: String) {
        BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val sent = NotificationAccess.sendReply(this@ReplyActivity, key, text)
                    Toast.makeText(this@ReplyActivity, if (sent) "Sent" else "Couldn't be sent", Toast.LENGTH_LONG).show()
                    finish()
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Send reply")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
        )
    }
}
