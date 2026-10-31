package de.mitauri.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Opens the door app. Its own fingerprint check still decides whether the door opens. */
object Door {
    private const val PACKAGE = "com.example.doorapp"

    fun open(context: Context): String {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            return "The phone is locked, so the door app wasn't opened."
        }
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return "The door app isn't installed."
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "The door app is open and waiting for the user's fingerprint. The door is not open yet."
        } catch (_: RuntimeException) {
            "The door app couldn't be opened."
        }
    }
}

/** Only sees notifications. Nothing they contain is stored, and only allowed apps are ever read. */
class NotificationWatcher : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationAccess.rememberApp(this, sbn.packageName)
    }

    companion object {
        var instance: NotificationWatcher? = null
            private set
    }
}

object NotificationAccess {
    private const val MAX_ITEMS = 30
    private const val PREFERENCES = "notification_access"
    private val random = SecureRandom()
    private var handles: Map<String, String> = emptyMap() // Short-lived ids, not reused across reads.

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, NotificationWatcher::class.java).flattenToString()
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(component) == true
    }

    fun rememberApp(context: Context, packageName: String) {
        if (packageName == context.packageName || NotificationFilter.isProtectedApp(packageName)) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val seen = preferences.getStringSet("seen", emptySet()).orEmpty()
        if (packageName !in seen) preferences.edit().putStringSet("seen", seen + packageName).apply()
    }

    fun seenApps(context: Context): List<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getStringSet("seen", emptySet()).orEmpty().sorted()

    fun allowedApps(context: Context): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getStringSet("allowed", emptySet()).orEmpty()

    fun setAllowed(context: Context, packageName: String, allowed: Boolean) {
        val current = allowedApps(context)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet("allowed", if (allowed) current + packageName else current - packageName).apply()
    }

    fun appLabel(context: Context, packageName: String): String = try {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    /** The current notifications from allowed apps, for Mitauri to read when you ask. */
    fun read(context: Context): JSONObject {
        val watcher = NotificationWatcher.instance
            ?: return JSONObject().put("error", "Notification access isn't turned on in the Mitauri app's setup.")
        val allowed = allowedApps(context)
        if (allowed.isEmpty()) return JSONObject().put("error", "No apps are allowed yet. The user can choose them in the app's setup.")
        val ids = mutableMapOf<String, String>()
        val items = JSONArray()
        watcher.activeNotifications.orEmpty()
            .filter { isReadable(it, allowed) }
            .sortedByDescending { it.postTime }
            .take(MAX_ITEMS)
            .forEach { sbn ->
                val id = "n" + random.nextInt(Int.MAX_VALUE).toString(36)
                ids[id] = sbn.key
                val extras = sbn.notification.extras
                items.put(
                    JSONObject()
                        .put("id", id)
                        .put("app", appLabel(context, sbn.packageName))
                        .put("from", NotificationFilter.clean(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()))
                        .put("text", NotificationFilter.clean(textOf(extras)))
                        .put("time", sbn.postTime / 1000)
                        .put("can_reply", replyAction(sbn) != null)
                )
            }
        handles = ids
        return JSONObject().put("items", items)
    }

    /** Opens the confirmation screen. Sending always needs your fingerprint or screen lock there. */
    fun askToReply(context: Context, id: String, text: String): String {
        val key = handles[id] ?: return "That notification is no longer available. Read the notifications again."
        val sbn = find(key) ?: return "That notification is gone."
        if (sbn.packageName !in allowedApps(context) || NotificationFilter.isProtectedApp(sbn.packageName)) {
            return "That app isn't allowed."
        }
        replyAction(sbn) ?: return "That notification doesn't support replies."
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            return "The phone is locked, so nothing was sent. Ask the user to unlock it first."
        }
        context.startActivity(
            Intent(context, ReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("key", key)
                .putExtra("app", appLabel(context, sbn.packageName))
                .putExtra("to", NotificationFilter.clean(sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()))
                .putExtra("text", text)
        )
        return "Waiting for the user to check and confirm on the phone. Nothing has been sent yet."
    }

    /** Sends through the notification's own reply button. Only ReplyActivity calls this. */
    fun sendReply(context: Context, key: String, text: String): Boolean {
        val action = find(key)?.let(::replyAction) ?: return false
        val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    private fun find(key: String) = NotificationWatcher.instance?.activeNotifications?.firstOrNull { it.key == key }

    private fun isReadable(sbn: StatusBarNotification, allowed: Set<String>): Boolean {
        val notification = sbn.notification
        return sbn.packageName in allowed &&
            !NotificationFilter.isProtectedApp(sbn.packageName) &&
            !sbn.isOngoing &&
            notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            notification.visibility != Notification.VISIBILITY_SECRET
    }

    private fun textOf(extras: Bundle): String =
        (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
}
