package de.mitauri.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Places where the bag check rests, like home, school or a friend's. They live only on this phone, as place reminders
 * do: the server only hears, with each presence report, whether the phone is at one of them, never which or where.
 */
object QuietPlaces {
    private const val MAX_PLACES = 12
    private const val EXTRA_ID = "id"
    private const val FIX_FRESH_MILLIS = 10 * 60 * 1000L

    private fun store(context: Context) = EncryptedFile(context.applicationContext, "quiet_places.enc")

    private fun all(context: Context): JSONArray = store(context).read()?.let(::JSONArray) ?: JSONArray()

    // Which ones the phone is inside right now. Only ids, kept in memory and set again from the last fix on start.
    private val inside = HashSet<String>()

    fun isInsideAny(): Boolean = synchronized(inside) { inside.isNotEmpty() }

    private fun canWatch(context: Context) = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun add(context: Context, name: String, latitude: Double, longitude: Double, radius: Float): String {
        if (!canWatch(context)) return "Not added: the user has to choose 'Allow all the time' for location in the app's Settings."
        val places = all(context)
        if (places.length() >= MAX_PLACES) return "Not added: there are already $MAX_PLACES quiet places. Remove one first."
        val place = JSONObject()
            .put("id", UUID.randomUUID().toString().take(8))
            .put("name", name)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("radius", radius.toDouble())
        store(context).write(places.put(place).toString())
        checkLastFix(context, place)
        watch(context, place)
        return "Added."
    }

    fun list(context: Context): JSONObject {
        val visible = JSONArray()
        val places = all(context)
        for (i in 0 until places.length()) {
            val item = places.getJSONObject(i)
            visible.put(
                JSONObject().put("id", item.getString("id")).put("name", item.getString("name"))
                    .put("radius_metres", item.getDouble("radius").toInt()),
            )
        }
        return JSONObject().put("items", visible).put("phone_is_at_one_now", isInsideAny())
    }

    fun remove(context: Context, id: String): Boolean {
        val places = all(context)
        val kept = JSONArray()
        var found = false
        for (i in 0 until places.length()) {
            val item = places.getJSONObject(i)
            if (item.getString("id") == id) found = true else kept.put(item)
        }
        if (found) {
            store(context).write(kept.toString())
            context.getSystemService(LocationManager::class.java).removeProximityAlert(intent(context, id))
            synchronized(inside) { inside.remove(id) }
        }
        return found
    }

    /** Android forgets proximity alerts on restart, so the service sets them again when it starts. */
    fun watchAll(context: Context) {
        if (!canWatch(context)) return
        val places = all(context)
        for (i in 0 until places.length()) {
            val place = places.getJSONObject(i)
            checkLastFix(context, place)
            watch(context, place)
        }
    }

    /**
     * Android only says when the phone crosses a place's edge. Started while already inside, the place would count as
     * left until the next crossing, so a recent enough fix decides it right away.
     */
    @SuppressLint("MissingPermission")
    private fun checkLastFix(context: Context, place: JSONObject) {
        val manager = context.getSystemService(LocationManager::class.java)
        val fix = manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < FIX_FRESH_MILLIS * 1_000_000 }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        val distance = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, place.getDouble("latitude"), place.getDouble("longitude"), distance)
        if (distance[0] <= place.getDouble("radius")) synchronized(inside) { inside.add(place.getString("id")) }
    }

    @SuppressLint("MissingPermission")
    private fun watch(context: Context, place: JSONObject) {
        context.getSystemService(LocationManager::class.java).addProximityAlert(
            place.getDouble("latitude"), place.getDouble("longitude"),
            place.getDouble("radius").toFloat(), -1, intent(context, place.getString("id")),
        )
    }

    // Mutable, because Android adds whether you're entering or leaving when it fires. The request code keeps these
    // apart from place reminders' alerts.
    private fun intent(context: Context, id: String) = PendingIntent.getBroadcast(
        context, ("quiet:$id").hashCode(),
        Intent(context, QuietPlaceReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    class QuietPlaceReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
            synchronized(inside) { if (entering) inside.add(id) else inside.remove(id) }
        }
    }
}
