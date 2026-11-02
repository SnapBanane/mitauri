package de.mitauri.app

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ActivityKind { CALL, NAVIGATION, ALARM, ELAPSED, PROGRESS }

/** A button the app itself put on its notification, such as Answer, Hang up or Snooze. */
data class ActivityAction(val title: String, val intent: PendingIntent)

/** A call or a route another app has going. Shown on the phone only, and nothing about it is kept. */
data class OngoingActivity(
    val kind: ActivityKind,
    val key: String,
    val title: String,
    val detail: String,
    val startedAt: Long? = null, // A call that's still ringing has no start.
    val open: PendingIntent? = null,
    val progress: Float? = null,
    val actions: List<ActivityAction> = emptyList(),
)

enum class AlertKind { CHARGING, LOW_BATTERY, SILENT, VIBRATE, RING, HEADPHONES, UNLOCKED, SAVER, HOTSPOT }

data class IslandAlert(val kind: AlertKind, val text: String)

fun activityFrom(
    key: String,
    category: String?,
    ongoing: Boolean,
    showsTimer: Boolean,
    whenMillis: Long,
    title: String,
    detail: String,
    open: PendingIntent? = null,
    progress: Float? = null,
    actions: List<ActivityAction> = emptyList(),
): OngoingActivity? {
    if (!ongoing || title.isBlank()) return null
    val started = whenMillis.takeIf { showsTimer }
    val kind = when {
        category == Notification.CATEGORY_CALL -> ActivityKind.CALL
        category == Notification.CATEGORY_NAVIGATION -> ActivityKind.NAVIGATION
        category == Notification.CATEGORY_ALARM -> ActivityKind.ALARM
        category == Notification.CATEGORY_TRANSPORT -> return null // Music has its own place.
        showsTimer -> ActivityKind.ELAPSED // A stopwatch, or a screen or voice recording.
        progress != null -> ActivityKind.PROGRESS
        else -> return null
    }
    return OngoingActivity(kind, key, title, detail, started, open, progress, actions)
}

/** How far along a notification's bar is, or null if it has no bar with a real value. */
fun progressFrom(value: Int, max: Int, indeterminate: Boolean): Float? =
    if (max > 0 && !indeterminate) (value.toFloat() / max).coerceIn(0f, 1f) else null

/** A running countdown from the clock app or similar. */
fun countdownFrom(ongoing: Boolean, countsDown: Boolean, whenMillis: Long, now: Long, title: String, detail: String): LiveCountdown? =
    if (ongoing && countsDown && whenMillis > now) LiveCountdown(title.ifBlank { "Timer" }, whenMillis, detail) else null

/** Passes other apps' calls, routes and timers to the island. Mitauri's own and protected apps are left out. */
object IslandNotifications {
    private const val MAX_ACTIONS = 3
    private var countdownKey: String? = null

    fun posted(watcher: NotificationWatcher, sbn: StatusBarNotification) {
        if (sbn.packageName == watcher.packageName || NotificationFilter.isProtectedApp(sbn.packageName)) return
        val note = sbn.notification
        val title = note.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val detail = note.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val showsTimer = note.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countsDown = showsTimer && note.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val progress = progressFrom(
            note.extras.getInt(Notification.EXTRA_PROGRESS),
            note.extras.getInt(Notification.EXTRA_PROGRESS_MAX),
            note.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
        )
        val actions = note.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() && it.actionIntent != null }
            .take(MAX_ACTIONS)
            .map { ActivityAction(it.title.toString(), it.actionIntent) }
        val activity = if (countsDown && note.category != Notification.CATEGORY_CALL) {
            null
        } else {
            activityFrom(sbn.key, note.category, sbn.isOngoing, showsTimer, note.`when`, title, detail, note.contentIntent, progress, actions)
        }
        if (activity != null) {
            Island.setActivity(watcher, activity)
            return
        }
        Island.clearActivity(sbn.key) // A ringing call can turn into a missed one under the same key.
        val countdown = countdownFrom(sbn.isOngoing, countsDown, note.`when`, System.currentTimeMillis(), title, detail)
        if (countdown != null) {
            countdownKey = sbn.key
            Island.setCountdownIfNew(watcher, countdown)
        } else if (sbn.key == countdownKey) {
            removed(sbn.key)
        }
    }

    fun removed(key: String) {
        Island.clearActivity(key)
        if (key == countdownKey) {
            countdownKey = null
            Island.clearLive()
        }
    }
}

/**
 * How far the nearest status bar item is from the camera, on the closer side. Takes each item's left and right edge,
 * so only positions are ever needed. Null if there are no items, which also means there's nothing to measure against.
 */
fun clearSpace(center: Int, items: List<IntRange>): Int? {
    val beside = items.filter { it.last <= center || it.first >= center }
    if (beside.isEmpty()) return null
    return beside.minOf { if (it.last <= center) center - it.last else it.first - center }
}

val DEFAULT_SIDE = 58.dp
val MIN_SIDE = 40.dp // The cover and the bars still fit.
val MAX_SIDE = 96.dp
private val PILL_PADDING = 12.dp
private val ICON_MARGIN = 4.dp

/** How wide each side of the pill may be, so it ends just before the nearest icon. */
fun sideFor(clear: Dp, cameraWidth: Dp, cameraMargin: Dp): Dp =
    (clear - cameraWidth / 2 - cameraMargin - PILL_PADDING - ICON_MARGIN).coerceIn(MIN_SIDE, MAX_SIDE)

/**
 * When a song belongs in the island. Only sound really playing on this phone brings it up, not a session waking up,
 * loading or playing on another device. A pause keeps it for a while, timed by a clock that runs through sleep.
 */
class MusicPresence(private val holdMillis: Long) {
    private var active = false
    private var pausedAt: Long? = null

    fun update(playingHere: Boolean, now: Long): Boolean {
        if (playingHere) {
            active = true
            pausedAt = null
            return true
        }
        if (!active) return false
        val since = pausedAt ?: now.also { pausedAt = it }
        if (now - since >= holdMillis) reset()
        return active
    }

    fun millisLeft(now: Long): Long? = pausedAt?.let { holdMillis - (now - it) }

    fun reset() {
        active = false
        pausedAt = null
    }
}
