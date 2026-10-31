package de.mitauri.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reminders for when you reach a place. They live only on this phone, so the server never learns when
 * you arrive. Android does the watching, which costs little battery while you're far away.
 */
object PlaceReminders {
    private const val MAX_REMINDERS = 20
    private const val EXTRA_ID = "id"

    private fun store(context: Context) = EncryptedFile(context.applicationContext, "places.enc")

    private fun all(context: Context): JSONArray = store(context).read()?.let(::JSONArray) ?: JSONArray()

    private fun canWatch(context: Context) = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun add(context: Context, text: String, latitude: Double, longitude: Double, radius: Float): String {
        if (!canWatch(context)) return "Not set: the user has to choose 'Allow all the time' for location in the app's Settings."
        val reminders = all(context)
        if (reminders.length() >= MAX_REMINDERS) return "Not set: too many place reminders are waiting."
        val reminder = JSONObject()
            .put("id", UUID.randomUUID().toString().take(8))
            .put("text", text)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("radius", radius.toDouble())
        store(context).write(reminders.put(reminder).toString())
        watch(context, reminder)
        return "Set."
    }

    fun list(context: Context): JSONArray {
        val visible = JSONArray()
        val reminders = all(context)
        for (i in 0 until reminders.length()) {
            val item = reminders.getJSONObject(i)
            visible.put(JSONObject().put("id", item.getString("id")).put("text", item.getString("text")))
        }
        return visible
    }

    fun remove(context: Context, id: String): Boolean {
        val reminders = all(context)
        val kept = JSONArray()
        var found = false
        for (i in 0 until reminders.length()) {
            val item = reminders.getJSONObject(i)
            if (item.getString("id") == id) found = true else kept.put(item)
        }
        if (found) {
            store(context).write(kept.toString())
            context.getSystemService(LocationManager::class.java).removeProximityAlert(intent(context, id))
        }
        return found
    }

    /** Android forgets these on restart, so the service sets them again when it starts. */
    fun watchAll(context: Context) {
        if (!canWatch(context)) return
        val reminders = all(context)
        for (i in 0 until reminders.length()) watch(context, reminders.getJSONObject(i))
    }

    fun onArrived(context: Context, id: String) {
        val reminders = all(context)
        for (i in 0 until reminders.length()) {
            val item = reminders.getJSONObject(i)
            if (item.getString("id") == id) {
                Calls.notify(context, "Reminder", item.getString("text"))
                remove(context, id)
                return
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun watch(context: Context, reminder: JSONObject) {
        context.getSystemService(LocationManager::class.java).addProximityAlert(
            reminder.getDouble("latitude"), reminder.getDouble("longitude"),
            reminder.getDouble("radius").toFloat(), -1, intent(context, reminder.getString("id")),
        )
    }

    // Mutable, because Android adds whether you're entering or leaving when it fires.
    private fun intent(context: Context, id: String) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, PlaceReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    class PlaceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            if (intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) onArrived(context, id)
        }
    }
}
