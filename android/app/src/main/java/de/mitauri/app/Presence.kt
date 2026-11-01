package de.mitauri.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PresenceToken {
    fun of(key: ByteArray, window: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(ByteBuffer.allocate(8).putLong(window).array()).copyOf(8)
    }
}

/** A low-power Bluetooth beacon, so a sensor can tell you're near. Its token changes every 5 minutes. */
object PresenceBeacon {
    private const val WINDOW_SECONDS = 300L
    private const val COMPANY_ID = 0xFFFF
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte())
    private val main = Handler(Looper.getMainLooper())
    private val callback = object : AdvertiseCallback() {}
    private val rotate = Runnable { app?.let(::restart) }
    private var app: Context? = null
    private var running = false

    fun canRun(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    fun saveKey(context: Context, hexKey: String) {
        EncryptedFile(context.applicationContext, "presence.enc").write(hexKey)
        start(context)
    }

    fun hasKey(context: Context) = EncryptedFile(context.applicationContext, "presence.enc").read() != null

    fun start(context: Context) {
        app = context.applicationContext
        restart(context.applicationContext)
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        main.removeCallbacks(rotate)
        if (running && canRun(context)) advertiser(context)?.stopAdvertising(callback)
        running = false
    }

    private fun advertiser(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser

    @SuppressLint("MissingPermission")
    private fun restart(context: Context) {
        stop(context)
        if (!canRun(context)) return
        val hexKey = EncryptedFile(context, "presence.enc").read() ?: return
        val key = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val now = System.currentTimeMillis() / 1000
        val advertiser = advertiser(context) ?: return
        // No name or device details: the rotating token is all anyone nearby can see.
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(false)
                .build(),
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(COMPANY_ID, MAGIC + PresenceToken.of(key, now / WINDOW_SECONDS))
                .build(),
            callback,
        )
        running = true
        main.postDelayed(rotate, (WINDOW_SECONDS - now % WINDOW_SECONDS) * 1000)
    }
}

/** The bytes a sensor on plain power broadcasts. The phone holds no key for these: the server checks them. */
object SensorBeacon {
    const val COMPANY_ID = 0xFFFF
    const val PAYLOAD_BYTES = 14
    val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte())

    fun isOurs(data: ByteArray?) = data != null && data.size == PAYLOAD_BYTES && data[0] == MAGIC[0] && data[1] == MAGIC[1]
}

/** Listens for sensors and tells the server once a minute what it heard. Nothing is kept on the phone. */
object SensorListener {
    private const val REPORT_MILLIS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val heard = HashMap<String, Int>()
    private var app: Context? = null
    private var scanning = false
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(SensorBeacon.COMPANY_ID)
            if (SensorBeacon.isOurs(data) && heard.size < 16) {
                heard[data!!.joinToString("") { "%02x".format(it) }] = result.rssi
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            val context = app ?: return
            ensureScanning(context)
            Mitauri.sendPresenceReport(scanning, heard.toMap())
            heard.clear()
            main.postDelayed(this, REPORT_MILLIS)
        }
    }

    fun canRun(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        app = context.applicationContext
        main.removeCallbacks(tick)
        main.post(tick)
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        main.removeCallbacks(tick)
        if (scanning && canRun(context)) scanner(context)?.stopScan(callback)
        scanning = false
        heard.clear()
    }

    private fun adapter(context: Context) = context.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    private fun scanner(context: Context) = adapter(context)?.takeIf { it.isEnabled }?.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    private fun ensureScanning(context: Context) {
        if (!canRun(context) || adapter(context)?.isEnabled != true) {
            scanning = false
            return
        }
        if (scanning) return
        val scanner = scanner(context) ?: return
        // A scan with a filter is the kind Android keeps delivering while the screen is off.
        val mask = ByteArray(SensorBeacon.PAYLOAD_BYTES).also { it[0] = -1; it[1] = -1 }
        val filter = ScanFilter.Builder()
            .setManufacturerData(SensorBeacon.COMPANY_ID, SensorBeacon.MAGIC + ByteArray(SensorBeacon.PAYLOAD_BYTES - 2), mask)
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanner.startScan(listOf(filter), settings, callback)
        scanning = true
    }
}
