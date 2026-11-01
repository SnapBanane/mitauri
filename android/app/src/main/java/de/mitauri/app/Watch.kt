package de.mitauri.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDateTime
import java.util.UUID
import org.json.JSONObject

data class FoundWatch(val address: String, val name: String, val rssi: Int)

/**
 * Talks to the watch directly over Bluetooth, instead of the VeryFitPro app. Only the watch you chose is ever
 * connected to, and only its fixed commands exist here. Nothing queues while it's away, so old messages can't
 * pile up and arrive later. Holding the connection also keeps others off, as the watch takes one at a time.
 */
@SuppressLint("MissingPermission") // canRun() is checked before every entry into Bluetooth
object WatchLink {
    private const val PREFERENCES = "watch"
    private const val ACK_TIMEOUT_MILLIS = 5_000L
    private const val RETRY_MILLIS = 20_000L
    private const val SCAN_MILLIS = 10_000L
    private const val MAX_QUEUE = 20
    const val OLD_APP = "com.veryfit2hr.second"
    private val SERVICE = uuid(0x0AF0)
    private val WRITE = uuid(0x0AF6)
    private val NOTIFY = uuid(0x0AF7)
    private val CCCD = uuid(0x2902)

    private fun uuid(short: Int) = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    private class Job(val frames: List<ByteArray>, val done: (ByteArray?) -> Unit)

    private val handler by lazy { Handler(HandlerThread("watch").apply { start() }.looper) }
    private val rules = WatchForwardRules()
    private val queue = ArrayDeque<Job>()
    private var app: Context? = null
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var ready = false
    private var running = false
    private var job: Job? = null
    private var frameIndex = 0
    private var written = false
    private var acked: ByteArray? = null
    private val timedOut = Runnable { finish(null) }
    private val retry = Runnable { connect() }

    var connected by mutableStateOf(false)
        private set
    var battery by mutableStateOf<Int?>(null)
        private set

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun canRun(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        )

    fun address(context: Context): String? = preferences(context).getString("address", null)

    fun oldAppInstalled(context: Context) = try {
        context.packageManager.getPackageInfo(OLD_APP, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun link(context: Context, address: String) {
        preferences(context).edit().putString("address", address).apply()
        stop()
        start(context)
    }

    fun forget(context: Context) {
        preferences(context).edit().remove("address").apply()
        stop()
    }

    fun allowedApps(context: Context): Set<String> = preferences(context).getStringSet("apps", emptySet()).orEmpty()

    fun setAllowed(context: Context, packageName: String, allowed: Boolean) {
        val current = allowedApps(context)
        preferences(context).edit().putStringSet("apps", if (allowed) current + packageName else current - packageName).apply()
    }

    fun moduleEnabled(context: Context) = preferences(context).getBoolean("module", true)

    fun setModuleEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("module", enabled).apply()
        if (enabled) start(context) else stop()
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        app = appContext
        handler.post {
            if (!running && moduleEnabled(appContext) && address(appContext) != null && canRun(appContext)) {
                running = true
                connect()
            }
        }
    }

    fun stop() = handler.post {
        running = false
        handler.removeCallbacks(retry)
        closeGatt()
    }

    private fun connect() {
        val context = app ?: return
        val address = address(context) ?: return
        if (!running || gatt != null || !canRun(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            handler.postDelayed(retry, RETRY_MILLIS)
            return
        }
        // autoConnect waits cheaply for the watch to come back into range, however long that takes.
        gatt = adapter.getRemoteDevice(address).connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        ready = false
        connected = false
        writer = null
        gatt?.close()
        gatt = null
        finish(null)
        while (queue.isNotEmpty()) queue.removeFirst().done(null)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else {
                    closeGatt()
                    if (running) handler.postDelayed(retry, RETRY_MILLIS)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val service = g.getService(SERVICE)
                val write = service?.getCharacteristic(WRITE)
                val notify = service?.getCharacteristic(NOTIFY)
                val descriptor = notify?.getDescriptor(CCCD)
                if (write == null || descriptor == null || !g.setCharacteristicNotification(notify, true)) {
                    closeGatt() // isn't the watch, or isn't ready
                    if (running) handler.postDelayed(retry, RETRY_MILLIS)
                    return@post
                }
                writer = write
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt() // without acks it can't be used, so start over
                    if (running) handler.postDelayed(retry, RETRY_MILLIS)
                    return@post
                }
                ready = true
                connected = true
                enqueue(listOf(WatchProtocol.setTime(LocalDateTime.now()))) {}
                enqueue(listOf(WatchProtocol.GET_STATUS)) { it?.let(WatchProtocol::battery)?.let { level -> battery = level } }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt || job == null) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) return@post finish(null)
                written = true
                advance()
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != NOTIFY) return
            val value = characteristic.value?.copyOf() ?: return
            handler.post {
                val current = job
                if (g !== gatt || current == null) return@post
                when (WatchProtocol.ackFor(current.frames[frameIndex], value)) {
                    WatchProtocol.Ack.NOT_THIS_FRAME -> Unit
                    WatchProtocol.Ack.REJECTED -> finish(null)
                    WatchProtocol.Ack.ACCEPTED -> {
                        acked = value
                        advance()
                    }
                }
            }
        }
    }

    private fun enqueue(frames: List<ByteArray>, done: (ByteArray?) -> Unit) {
        if (!ready || queue.size >= MAX_QUEUE) return done(null)
        queue.addLast(Job(frames, done))
        next()
    }

    private fun next() {
        if (job != null || queue.isEmpty()) return
        job = queue.removeFirst()
        frameIndex = 0
        writeFrame()
    }

    @Suppress("DEPRECATION")
    private fun writeFrame() {
        val current = job ?: return
        val characteristic = writer
        written = false
        acked = null
        handler.removeCallbacks(timedOut)
        if (characteristic == null) return finish(null)
        characteristic.value = current.frames[frameIndex]
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (gatt?.writeCharacteristic(characteristic) != true) return finish(null)
        handler.postDelayed(timedOut, ACK_TIMEOUT_MILLIS)
    }

    /** The next write has to wait for both: Android allows one at a time, and the ack can arrive first. */
    private fun advance() {
        val current = job ?: return
        val ack = acked ?: return
        if (!written) return
        if (frameIndex == current.frames.lastIndex) return finish(ack)
        frameIndex++
        writeFrame()
    }

    /** One failed packet ends the message, so a dead link doesn't get the rest pushed at it. */
    private fun finish(result: ByteArray?) {
        handler.removeCallbacks(timedOut)
        val finished = job ?: return
        job = null
        finished.done(result)
        next()
    }

    private fun send(frames: List<ByteArray>, done: (ByteArray?) -> Unit) = handler.post { enqueue(frames, done) }

    fun show(title: String, text: String, done: (Boolean) -> Unit = {}) {
        send(WatchProtocol.notification(NotificationFilter.clean(title), NotificationFilter.clean(text))) { done(it != null) }
    }

    /** A notification you allowed for the watch. Banking, password and code apps never get here. */
    fun onNotification(context: Context, sbn: StatusBarNotification) {
        if (address(context) == null) return
        val notification = sbn.notification
        val allowed = rules.mayShow(
            sbn.packageName, context.packageName, allowedApps(context), sbn.isOngoing,
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            notification.visibility == Notification.VISIBILITY_SECRET,
        )
        if (!allowed) return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank()) return
        handler.post { if (rules.take(sbn.key, title + text, System.currentTimeMillis())) show(title, text) }
    }

    /** Requests from Mitauri. Checked here too, as this is the last stop before the radio. */
    fun request(context: Context, data: JSONObject, answer: (JSONObject) -> Unit) {
        fun outcome(sent: Boolean) = answer(
            if (sent) JSONObject().put("result", "done") else JSONObject().put("error", "The watch isn't connected or didn't accept it."),
        )
        when (data.getString("action")) {
            "status" -> statusOf(context, answer)
            "notify" -> show(data.optString("title", "Mitauri"), data.getString("text"), ::outcome)
            "brightness" -> send(listOf(WatchProtocol.setBrightness(data.getInt("value")))) { outcome(it != null) }
            "face" -> {
                val face = data.getInt("value")
                if (face in 1..4) send(listOf(WatchProtocol.setFace(face))) { outcome(it != null) } else outcome(false)
            }
            else -> answer(JSONObject().put("error", "Unknown watch action."))
        }
    }

    private fun statusOf(context: Context, answer: (JSONObject) -> Unit) {
        if (address(context) == null) return answer(JSONObject().put("linked", false).put("connected", false))
        send(listOf(WatchProtocol.GET_STATUS)) { response ->
            response?.let(WatchProtocol::battery)?.let { battery = it }
            val status = JSONObject().put("linked", true).put("connected", response != null)
            if (response != null) battery?.let { status.put("battery_percent", it) }
            answer(status)
        }
    }

    private var scanning: ScanCallback? = null

    /** Lists what looks like a watch. Anything can claim to be one, so you choose, by name and by how close it is. */
    fun scan(context: Context, onResults: (List<FoundWatch>) -> Unit, onFinished: () -> Unit) {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null || !canRun(context)) return onFinished()
        stopScan(context)
        val found = linkedMapOf<String, FoundWatch>()
        val listener = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unnamed"
                found[result.device.address] = FoundWatch(result.device.address, name, result.rssi)
                onResults(found.values.sortedByDescending { it.rssi })
            }
        }
        scanning = listener
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            listener,
        )
        handler.postDelayed({
            stopScan(context)
            onFinished()
        }, SCAN_MILLIS)
    }

    fun stopScan(context: Context) {
        val listener = scanning ?: return
        scanning = null
        if (canRun(context)) context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(listener)
    }
}
