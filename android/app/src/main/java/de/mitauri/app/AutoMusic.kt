package de.mitauri.app

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private val BLOCKING_APPS = setOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * Never over a call or music that's already on, or while WhatsApp is open. With the phone in use and no way to see
 * which app is open, it doesn't start either, because that could be WhatsApp.
 */
fun autoMusicMayStart(phoneInUse: Boolean, canSeeOpenApp: Boolean, openApp: String?, inCall: Boolean, soundPlaying: Boolean): Boolean {
    if (inCall || soundPlaying) return false
    if (!phoneInUse) return true
    return canSeeOpenApp && openApp !in BLOCKING_APPS
}

/** Starts Spotify when headphones the user chose connect. It needs no Bluetooth permission, and nothing leaves the phone. */
object AutoMusic {
    private const val PREFERENCES = "auto_music"
    private const val ROUTE_MILLIS = 1_500L // Android needs a moment to send sound to new headphones.
    private const val CHECK_MILLIS = 4_000L
    private const val BRIEF_DROP_MILLIS = 60_000L
    private const val HISTORY_MILLIS = 6 * 3600 * 1000L
    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val connected = mutableSetOf<String>()
    private val leftAt = mutableMapOf<String, Long>()
    private val play = Runnable { startIfAllowed() }
    private val check = Runnable { openSpotifyIfSilent() }
    private val pressPlay = Runnable { startPlaying() }

    var seen by mutableStateOf(emptySet<String>())
        private set
    var chosen by mutableStateOf(emptySet<String>())
        private set

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            for (name in names(added)) {
                remember(name)
                // Android also lists what's already connected when this starts. Only a new connection counts.
                if (connected.add(name) && name in chosen && !cameBackQuickly(name)) {
                    main.removeCallbacks(play)
                    main.postDelayed(play, ROUTE_MILLIS)
                }
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            for (name in names(removed)) {
                connected.remove(name)
                leftAt[name] = SystemClock.elapsedRealtime()
            }
            if (connected.none { it in chosen }) cancelPending()
        }
    }

    fun start(context: Context) {
        stop()
        val owner = context.applicationContext
        app = owner
        val saved = owner.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        seen = saved.getStringSet("seen", emptySet()).orEmpty()
        chosen = saved.getStringSet("chosen", emptySet()).orEmpty()
        val audio = owner.getSystemService(AudioManager::class.java)
        names(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).forEach {
            remember(it)
            connected.add(it)
        }
        audio.registerAudioDeviceCallback(devices, main)
    }

    fun stop() {
        val owner = app ?: return
        owner.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(devices)
        cancelPending()
        connected.clear()
        app = null
    }

    fun setChosen(context: Context, name: String, on: Boolean) {
        chosen = if (on) chosen + name else chosen - name
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putStringSet("chosen", chosen).apply()
    }

    fun canSeeOpenApp(context: Context): Boolean =
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED

    private fun names(list: Array<out AudioDeviceInfo>) =
        list.filter { it.isSink && it.type in HEADPHONES }.map { it.productName.toString() }.filter { it.isNotBlank() }.distinct()

    private fun remember(name: String) {
        if (name in seen) return
        seen = seen + name
        app?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)?.edit()?.putStringSet("seen", seen)?.apply()
    }

    /** A short dropout shouldn't start music again that the user may have just paused. */
    private fun cameBackQuickly(name: String): Boolean {
        val left = leftAt[name] ?: return false
        return SystemClock.elapsedRealtime() - left < BRIEF_DROP_MILLIS
    }

    private fun cancelPending() {
        main.removeCallbacks(play)
        main.removeCallbacks(check)
        main.removeCallbacks(pressPlay)
    }

    private fun startIfAllowed() {
        val context = app ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
        val inUse = context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val canSee = canSeeOpenApp(context)
        if (!autoMusicMayStart(inUse, canSee, if (inUse && canSee) openApp(context) else null, inCall, audio.isMusicActive)) return
        startPlaying()
        main.postDelayed(check, CHECK_MILLIS)
    }

    private fun startPlaying() {
        val context = app ?: return
        if (Music.play()) return
        // Spotify has no session yet, so wake it the way a headset's play button does.
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setPackage(Music.PACKAGE)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)),
            )
        }
    }

    /** Spotify usually starts unseen. Only if it stayed silent does it open on screen, then gets told to play. */
    private fun openSpotifyIfSilent() {
        val context = app ?: return
        if (Music.isPlaying || context.getSystemService(AudioManager::class.java).isMusicActive) return
        val open = context.packageManager.getLaunchIntentForPackage(Music.PACKAGE) ?: return
        context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main.postDelayed(pressPlay, CHECK_MILLIS)
    }

    private fun openApp(context: Context): String? {
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - HISTORY_MILLIS, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.getNextEvent(event)) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }
}
