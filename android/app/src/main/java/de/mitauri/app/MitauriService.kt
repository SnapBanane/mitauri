package de.mitauri.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder

/** Holds one idle connection after the app is closed. It runs no timers or sensors of its own. */
class MitauriService : Service() {
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainExecutor.execute { Mitauri.connectIfNeeded() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Mitauri.init(this)
        val channel = NotificationChannel(CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mitauri is ready")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Mitauri.connectIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        Mitauri.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "connection"

        fun start(context: Context) {
            Mitauri.init(context)
            if (Mitauri.isPaired) {
                context.startForegroundService(Intent(context, MitauriService::class.java))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MitauriService.start(context)
    }
}
