package de.mitauri.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Fetches newer versions of this app from the paired server and installs them.
 *
 * Android only accepts an update signed with the same key as the installed app, which is what really protects this.
 * The checks here catch broken downloads and wrong files before they get that far.
 */
object Updater {
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7001
    private const val MIN_AUTOMATIC_GAP_MILLIS = 5 * 60_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastAutomatic = 0L
    private var offered = 0L // Asked for once, so saying no doesn't mean being asked at every check.

    var status by mutableStateOf("")
        private set

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0)?.versionName ?: ""

    /** Called when the server says there's something new. */
    fun checkNow(context: Context) = check(context.applicationContext, byHand = false)

    /** Called at quiet moments, so this doesn't need a request every time. */
    fun checkIfDue(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastAutomatic != 0L && now - lastAutomatic < MIN_AUTOMATIC_GAP_MILLIS) return
        lastAutomatic = now
        check(context.applicationContext, byHand = false)
    }

    fun checkByHand(context: Context) = check(context.applicationContext, byHand = true)

    private fun check(app: Context, byHand: Boolean) {
        Mitauri.init(app)
        if (!Mitauri.isPaired) return
        if (byHand) show("Checking...")
        worker.execute {
            try {
                val info = Mitauri.fetchUpdateInfo()
                val code = info.optLong("version_code")
                val current = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                when {
                    code <= current -> if (byHand) show("Up to date")
                    // Installing closes the app, so never in the middle of something.
                    !byHand && (MainActivity.isVisible || VoiceMode.isListening || VoiceMode.afterExchange != null) -> Unit
                    !byHand && offered == code -> Unit
                    else -> {
                        val name = info.getString("version_name")
                        show("Downloading $name...")
                        val apk = download(app, info.getString("sha256"), info.getLong("size"))
                        checkFile(app, apk, code)
                        offered = code
                        show("Installing $name...")
                        install(app, apk)
                    }
                }
            } catch (error: Exception) {
                // It tries again at the next check, so this only matters when someone is watching.
                if (byHand) show("Update failed: ${error.message}")
            }
        }
    }

    private fun show(text: String) = main.post { status = text }

    private fun download(app: Context, sha256: String, size: Long): File {
        val file = File(app.cacheDir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        Mitauri.downloadUpdate { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (file.length() != size || actual != sha256) {
            file.delete()
            throw IOException("The download was damaged")
        }
        return file
    }

    private fun checkFile(app: Context, apk: File, code: Long) {
        val found = app.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (found?.packageName != app.packageName || found.longVersionCode != code) {
            apk.delete()
            throw IOException("That isn't the expected app version")
        }
    }

    private fun install(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Without a tap if Android allows it. If not, it asks, and UpdateReceiver passes that on.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("mitauri", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val done = PendingIntent.getBroadcast(
                    app, id, Intent(app, UpdateReceiver::class.java),
                    // Mutable because Android adds the result to it. The explicit target keeps it from going elsewhere.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(done.intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(id)
            throw error
        } finally {
            apk.delete()
        }
    }

    internal fun finished(context: Context, intent: Intent) {
        when (val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.isVisible) context.startActivity(confirm) else notifyToConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // This process ends now, and BootReceiver starts the new one.
            else -> {
                offered = 0
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $result"
                show("Update failed: $detail")
            }
        }
    }

    private fun notifyToConfirm(context: Context, confirm: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Mitauri update ready")
                .setContentText("Tap to install")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.finished(context.applicationContext, intent)
}
