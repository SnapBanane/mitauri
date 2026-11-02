package de.mitauri.islandtest

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log

/** adb shell am start -n de.mitauri.islandtest/.Post --es kind call|ringing|route|timer|stopwatch|progress|alarm|clear */
class Post : Activity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_LOW))
        val kind = intent.getStringExtra("kind").orEmpty()
        val now = System.currentTimeMillis()
        val note = Notification.Builder(this, "test").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true)
        when (kind) {
            "ringing" -> note.setCategory(Notification.CATEGORY_CALL).setContentTitle("Test Caller").addAction(action("Decline")).addAction(action("Answer"))
            "call" -> note.setCategory(Notification.CATEGORY_CALL).setContentTitle("Test Caller").setWhen(now - 75_000).setUsesChronometer(true).addAction(action("Hang up"))
            "route" -> note.setCategory(Notification.CATEGORY_NAVIGATION).setContentTitle("In 200 m rechts").setContentText("Hauptstr, noch 12 min")
            "timer" -> note.setContentTitle("Test timer").setWhen(now + 600_000).setUsesChronometer(true).setChronometerCountDown(true)
            "stopwatch" -> note.setContentTitle("Stopwatch").setWhen(now - 75_000).setUsesChronometer(true)
            "progress" -> note.setContentTitle("Download").setProgress(100, 62, false)
            "alarm" -> note.setCategory(Notification.CATEGORY_ALARM).setContentTitle("07:30 Alarm").addAction(action("Snooze"))
            else -> manager.cancelAll()
        }
        if (kind != "clear") {
            note.setContentIntent(PendingIntent.getBroadcast(this, 99, Intent(this, Tapped::class.java).putExtra("what", "opened"), PendingIntent.FLAG_IMMUTABLE))
            manager.notify(kind.hashCode(), note.build())
        }
        finish()
    }

    private fun action(title: String) = Notification.Action.Builder(
        null as Icon?,
        title,
        PendingIntent.getBroadcast(this, title.hashCode(), Intent(this, Tapped::class.java).putExtra("what", title), PendingIntent.FLAG_IMMUTABLE),
    ).build()
}

class Tapped : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("IslandTest", "Tapped ${intent.getStringExtra("what")}")
    }
}
