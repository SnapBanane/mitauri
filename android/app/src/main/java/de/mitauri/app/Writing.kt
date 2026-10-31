package de.mitauri.app

import android.app.Notification
import android.app.Person
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.util.zip.ZipInputStream

/** Sends a reply the user commanded, after a moment to see it and stop it. */
object ReplySender {
    private const val CANCEL_MILLIS = 4_000L
    private const val RESULT_MILLIS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var waiting: ((String) -> Unit)? = null

    fun start(context: Context, key: String, to: String, text: String, answer: (String) -> Unit) {
        val app = context.applicationContext
        if (pending != null) return answer("Another reply is still waiting to go out, so this one wasn't sent.")
        Island.show(app, IslandMode.MESSAGE, "An $to: $text")
        // Sending unseen would take away the only chance to stop it, so the confirmation screen takes over.
        if (!Island.isOnScreen) {
            Island.hideMessage()
            return answer(NotificationAccess.askToConfirm(context, key, to, text))
        }
        val send = Runnable {
            pending = null
            waiting = null
            val sent = NotificationAccess.sendReply(app, key, text)
            Island.show(app, IslandMode.MESSAGE, if (sent) "Gesendet" else "Nicht gesendet")
            main.postDelayed({ Island.hideMessage() }, RESULT_MILLIS)
            answer(if (sent) "Sent." else "It couldn't be sent, because the notification is gone or closed.")
        }
        pending = send
        waiting = answer
        main.postDelayed(send, CANCEL_MILLIS)
    }

    /** Called when the user tapped to stop it. Returns whether there was anything to cancel. */
    fun cancel(): Boolean {
        val send = pending ?: return false
        main.removeCallbacks(send)
        pending = null
        waiting?.invoke("The user cancelled it, so nothing was sent.")
        waiting = null
        return true
    }
}

/** Finds what the user themselves wrote. Others' lines are never kept or sent. */
object OwnLines {
    fun isOwn(sender: String?, self: String?): Boolean =
        sender.isNullOrBlank() || (!self.isNullOrBlank() && sender == self)

    fun fromNotification(extras: Bundle): List<String> {
        val self = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
            ?: person(extras, Notification.EXTRA_MESSAGING_PERSON)?.name?.toString()
        return listOf(Notification.EXTRA_MESSAGES, Notification.EXTRA_HISTORIC_MESSAGES).flatMap { name ->
            @Suppress("DEPRECATION")
            extras.getParcelableArray(name).orEmpty().filterIsInstance<Bundle>().mapNotNull { message ->
                val sender = message.getCharSequence("sender")?.toString() ?: person(message, "sender_person")?.name?.toString()
                val text = message.getCharSequence("text")?.toString()
                val clean = text?.let(NotificationFilter::clean)
                clean?.takeIf { isOwn(sender, self) && clean == text }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun person(bundle: Bundle, key: String): Person? = bundle.getParcelable(key)
}

data class ExportLine(val sender: String, val text: String)

/** Reads a WhatsApp chat export, which looks like "31.10.26, 20:44 - Max: Hallo" or "[31.10.26, 20:44:12] Max: Hallo". */
object ChatExport {
    private val LINE = Regex("""^\u200E?\[?\d{1,4}[./-]\d{1,2}[./-]\d{1,4},? \d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?\]?(?: -|:)? ([^:]{1,60}): (.*)$""")
    private val SYSTEM = Regex("""(?i)^\u200E?<.*>$|omitted|ausgelassen|weggelassen|nachricht wurde gelöscht|message was deleted""")

    fun parse(text: String): List<ExportLine> {
        val lines = mutableListOf<ExportLine>()
        var ignoring = true
        for (raw in text.lineSequence()) {
            val match = LINE.find(raw)
            if (match != null) {
                val body = match.groupValues[2]
                ignoring = SYSTEM.containsMatchIn(body)
                if (!ignoring) lines += ExportLine(match.groupValues[1].trim(), body)
            } else if (!ignoring && raw.isNotBlank() && lines.isNotEmpty()) {
                // A message with several lines goes on without a date.
                lines[lines.lastIndex] = lines.last().copy(text = lines.last().text + " " + raw.trim())
            }
        }
        return lines
    }

    /** The senders, most frequent first. */
    fun senders(lines: List<ExportLine>): List<Pair<String, Int>> =
        lines.groupingBy { it.sender }.eachCount().toList().sortedByDescending { it.second }
}

/** Takes a shared chat export. The user picks who they are, and only that sender's lines leave the phone. */
class StyleImportActivity : FragmentActivity() {
    private var lines by mutableStateOf<List<ExportLine>?>(null)
    private var sending by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Mitauri.init(this)
        Thread { read() }.start()
        setContent {
            MitauriTheme {
                Surface(Modifier.fillMaxSize(), color = MitauriColors.Background, contentColor = MitauriColors.Ink) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Which one is you?", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Only that person's lines go to your server to learn how you write. Everyone else's stay out. They're summed up by Claude.",
                            color = MitauriColors.Muted,
                            fontSize = 14.sp,
                        )
                        val found = lines
                        when {
                            found == null -> Text("Reading the chat…", color = MitauriColors.Muted)
                            found.isEmpty() -> Text("This doesn't look like a chat export.", color = MitauriColors.Muted)
                            sending -> Text("Sending…", color = MitauriColors.Muted)
                            else -> ChatExport.senders(found).take(MAX_SENDERS).forEach { (name, count) ->
                                Text(
                                    "$name ($count)",
                                    Modifier.fillMaxWidth().clickable { send(found, name) }.padding(vertical = 14.dp),
                                    fontSize = 17.sp,
                                )
                            }
                        }
                        TextButton(onClick = ::finish) { Text("Cancel", color = MitauriColors.AccentSoft) }
                    }
                }
            }
        }
    }

    private fun read() {
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
        val text = try {
            uri?.let(::readChat) ?: intent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val parsed = ChatExport.parse(text)
        runOnUiThread { lines = parsed }
    }

    private fun readChat(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: return ""
        return input.use { stream ->
            val head = java.io.BufferedInputStream(stream)
            head.mark(4)
            val isZip = head.read() == 'P'.code && head.read() == 'K'.code
            head.reset()
            if (!isZip) return@use head.readNBytesCapped()
            val zip = ZipInputStream(head)
            generateSequence { zip.nextEntry }.firstOrNull { it.name.endsWith(".txt") } ?: return@use ""
            zip.readNBytesCapped()
        }
    }

    private fun java.io.InputStream.readNBytesCapped(): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (buffer.size() < MAX_EXPORT_BYTES) {
            val count = read(chunk)
            if (count < 0) break
            buffer.write(chunk, 0, count)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun send(found: List<ExportLine>, name: String) {
        sending = true
        // The newest ones say most about how you write now, and a whole chat could be too big to send.
        val own = found.filter { it.sender == name }.map { it.text.let(NotificationFilter::clean) }
            .filterNot { it.startsWith("(hidden") }.takeLast(MAX_LINES)
        Thread {
            val sent = try {
                Mitauri.importStyleLines(own)
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                val result = if (sent) "Learning how you write. Check it under Memories in a minute." else "The server can't be reached right now."
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private companion object {
        const val MAX_SENDERS = 12
        const val MAX_LINES = 2_000
        const val MAX_EXPORT_BYTES = 20 * 1024 * 1024
    }
}
