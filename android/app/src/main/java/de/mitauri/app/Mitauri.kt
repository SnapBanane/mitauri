package de.mitauri.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Memory(val id: Int, val text: String)

data class ModuleInfo(val name: String, val label: String, val description: String, val required: Boolean, val enabled: Boolean)

data class ChatMessage(val text: String, val fromUser: Boolean)

enum class Status { DISCONNECTED, CONNECTING, CONNECTED, REJECTED }

/** The connection to the server. Lives as long as the process, independent of any screen. */
object Mitauri {
    private const val MIN_RETRY_MILLIS = 5_000L
    private const val MAX_RETRY_MILLIS = 300_000L
    private const val HISTORY_LIMIT = 100
    private const val PORT = 8766
    private val TAILSCALE_ADDRESS =
        Regex("""100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}""")
    private val MAPS_LINK = Regex("""https://www\.google\.com/maps/\S+""")

    // A rare ping, because each one wakes the phone's radio.
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.MINUTES)
        // Only this server may be talked to, so it can't send the app anywhere else.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val slowClient = client.newBuilder().readTimeout(330, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { connect() }
    private var socket: WebSocket? = null
    private var retryDelay = MIN_RETRY_MILLIS
    private lateinit var app: Context

    val messages = mutableStateListOf<ChatMessage>()
    var status by mutableStateOf(Status.DISCONNECTED)
        private set
    var waiting by mutableStateOf(false)
        private set
    var server by mutableStateOf("")
        private set
    var paired by mutableStateOf(false)
        private set
    private lateinit var history: EncryptedFile

    val isPaired get() = server.isNotBlank() && paired

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val preferences = app.getSharedPreferences("mitauri", Context.MODE_PRIVATE)
        server = preferences.getString("server", "") ?: ""
        paired = preferences.getBoolean("paired", false)
        history = EncryptedFile(app, "chat.enc")
        loadHistory()
    }

    /** Registers this phone's key using a one-time code. Call off the main thread. */
    fun pair(address: String, code: String): String? {
        val host = address.trim()
        if (!TAILSCALE_ADDRESS.matches(host)) return "That isn't a Tailscale address (100.x.x.x)."
        val url = "ws://$host:$PORT/ws"
        val body = JSONObject()
            .put("code", code.trim())
            .put("public_key", DeviceKey.publicKeyBase64())
            .put("name", Build.MODEL)
            .toString()
        val request = Request.Builder()
            .url("http://$host:$PORT/pair")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        main.post {
                            server = url
                            paired = true
                            savePairing()
                            retryDelay = MIN_RETRY_MILLIS
                            connect()
                        }
                        null
                    }
                    response.code == 429 -> "Too many attempts. Try again in 15 minutes."
                    else -> "That code is wrong or expired. Run mitauri pair for a new one."
                }
            }
        } catch (_: IOException) {
            "Can't reach the server. Is it running, and is Tailscale on?"
        }
    }

    /** Forgets this server, here and on the server, and throws away this phone's key. */
    fun unpair() {
        Thread {
            try {
                client.newCall(signedRequest("device", "DELETE", ByteArray(0)).build()).execute().close()
            } catch (_: Exception) {
                // Offline, so it can also be removed with `mitauri unpair` on the server.
            }
            DeviceKey.delete()
        }.start()
        paired = false
        savePairing()
        disconnect()
    }

    private fun savePairing() {
        app.getSharedPreferences("mitauri", Context.MODE_PRIVATE).edit()
            .putString("server", server).putBoolean("paired", paired).remove("token").apply()
    }

    private fun signedRequest(path: String, method: String, body: ByteArray): Request.Builder {
        val builder = Request.Builder()
            .url(httpUrl(path))
            .header("X-Mitauri-Auth", DeviceKey.authHeader(method, "/$path", body))
        return when (method) {
            "GET" -> builder
            "DELETE" -> builder.delete()
            else -> builder.post(body.toRequestBody("application/octet-stream".toMediaType()))
        }
    }

    /** Call off the main thread. */
    fun fetchMemories(): List<Memory> {
        slowClient.newCall(signedRequest("memories", "GET", ByteArray(0)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            val items = JSONArray(response.body!!.string())
            return (0 until items.length()).map {
                val item = items.getJSONObject(it)
                Memory(item.getInt("id"), item.getString("text"))
            }
        }
    }

    /** Call off the main thread. */
    fun deleteMemory(id: Int) {
        slowClient.newCall(signedRequest("memories/$id", "DELETE", ByteArray(0)).build()).execute().close()
    }

    /** Call off the main thread. */
    fun fetchModules(): List<ModuleInfo> {
        slowClient.newCall(signedRequest("modules", "GET", ByteArray(0)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            return parseModules(response.body!!.string())
        }
    }

    /** Call off the main thread. */
    fun setModule(name: String, enabled: Boolean): List<ModuleInfo> {
        val body = JSONObject().put("name", name).put("enabled", enabled).toString().toByteArray()
        slowClient.newCall(signedRequest("modules", "POST", body).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            return parseModules(response.body!!.string())
        }
    }

    private fun parseModules(text: String): List<ModuleInfo> {
        val items = JSONArray(text)
        return (0 until items.length()).map {
            val item = items.getJSONObject(it)
            ModuleInfo(item.getString("name"), item.getString("label"), item.getString("description"), item.getBoolean("required"), item.getBoolean("enabled"))
        }
    }

    /** Call off the main thread. */
    fun clearLongTermMemory() {
        slowClient.newCall(signedRequest("long-term-memory", "DELETE", ByteArray(0)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
        }
    }

    /** Call off the main thread. */
    fun resetConversation() {
        slowClient.newCall(signedRequest("conversation/reset", "POST", ByteArray(0)).build()).execute().close()
    }

    /** Forgets the chat on this phone and, when reachable, the conversation on the server. */
    fun startNewChat() {
        messages.clear()
        waiting = false
        history.delete()
        Thread {
            try {
                resetConversation()
            } catch (_: Exception) {
                // Offline: the server's conversation carries on, but this phone's copy is gone.
            }
        }.start()
    }

    fun connectIfNeeded() {
        if (socket == null || status == Status.DISCONNECTED || status == Status.REJECTED) {
            retryDelay = MIN_RETRY_MILLIS
            connect()
        }
    }

    fun disconnect() {
        main.removeCallbacks(reconnect)
        socket?.cancel()
        socket = null
        status = Status.DISCONNECTED
    }

    /** Returns false when the message couldn't be sent. */
    fun send(text: String): Boolean {
        val id = UUID.randomUUID().toString()
        val sent = socket?.send(
            JSONObject().put("type", "message").put("id", id).put("text", text).toString()
        ) ?: false
        if (sent) {
            addMessage(ChatMessage(text, fromUser = true))
            waiting = true
        }
        return sent
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        socket?.cancel()
        socket = null
        if (!isPaired) {
            status = Status.DISCONNECTED
            return
        }
        status = Status.CONNECTING
        val request = try {
            Request.Builder().url(server).header("X-Mitauri-Auth", DeviceKey.authHeader("GET", "/ws")).build()
        } catch (_: Exception) {
            status = Status.REJECTED
            return
        }
        socket = client.newWebSocket(request, Listener())
    }

    /** Sends a recording and passes on each event as it arrives. Call off the main thread. */
    fun askByVoice(pcm: ByteArray, onEvent: (JSONObject) -> Unit) {
        val request = signedRequest("voice", "POST", pcm).build()
        slowClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            val source = response.body!!.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                onEvent(JSONObject(line))
            }
        }
    }

    /** Sends a photo and returns the reply. Call off the main thread. */
    fun askAboutPhoto(jpeg: ByteArray, text: String): String {
        val body = JSONObject()
            .put("text", text)
            .put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .toString()
            .toByteArray()
        slowClient.newCall(signedRequest("photo", "POST", body).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            return JSONObject(response.body!!.string()).getString("reply")
        }
    }

    fun photoStarted(text: String) {
        addMessage(ChatMessage("📷 $text".trim(), fromUser = true))
        waiting = true
    }

    fun photoAnswered(reply: String) {
        addMessage(ChatMessage(reply, fromUser = false))
        waiting = false
    }

    /** Returns WAV audio of the text. Call off the main thread. */
    fun fetchSpeech(text: String, language: String): ByteArray {
        val body = JSONObject().put("text", text).put("language", language).toString().toByteArray()
        val request = signedRequest("speak", "POST", body).build()
        slowClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            return response.body!!.bytes()
        }
    }

    fun addHeard(text: String) = addMessage(ChatMessage(text, fromUser = true))

    fun addVoiceReply(text: String, openLinks: Boolean) {
        addMessage(ChatMessage(text, fromUser = false))
        if (openLinks) openMapsLink(text)
    }

    private fun httpUrl(path: String) = server.replaceFirst("ws://", "http://").removeSuffix("/ws") + "/" + path

    private fun onReply(text: String) {
        addMessage(ChatMessage(text, fromUser = false))
        waiting = false
        if (MainActivity.isVisible) openMapsLink(text)
    }

    // Permission to draw over apps also lets an app open a screen from the background.
    private fun openMapsLink(text: String) {
        val link = MAPS_LINK.find(text) ?: return
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            // The link is still in the chat if it can't be opened.
        }
    }

    private class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (webSocket === socket) {
                    status = Status.CONNECTED
                    retryDelay = MIN_RETRY_MILLIS
                    if (!PresenceBeacon.hasKey(app)) fetchPresenceKey()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Nothing the server sends should be able to crash the app.
            val data = try {
                JSONObject(text)
            } catch (_: org.json.JSONException) {
                return
            }
            when (data.optString("type")) {
                "reply" -> main.post { onReply(data.getString("text")) }
                "door_command" -> main.post { DoorOpener.command(app) }
                "open_door", "read_notifications", "ask_to_reply", "add_place_reminder", "list_place_reminders", "remove_place_reminder" -> main.post { answerRequest(webSocket, data) }
                "ring", "notify", "alarm", "alarm_over", "live_show", "live_clear" -> {
                    // Tells the server it arrived, so it can fall back to Discord if it didn't.
                    webSocket.send(JSONObject().put("type", "ack").put("id", data.getString("id")).toString())
                    main.post {
                        when (data.getString("type")) {
                            "ring" -> Calls.ring(app, data.getString("text"))
                            "alarm" -> Calls.alarmStarted(app, data.getString("text"))
                            "alarm_over" -> Calls.alarmOver(app)
                            "live_show" -> Island.setLive(
                                app,
                                LiveCountdown(data.getString("text"), data.getLong("until") * 1000, data.optString("detail")),
                            )
                            "live_clear" -> Island.clearLive()
                            else -> Calls.notify(app, "Reminder", data.getString("text"))
                        }
                    }
                }
                "get_location" -> main.post { sendLocation(webSocket, data.getString("id")) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Refused can be temporary, such as a wrong clock or a lockout, so keep trying slowly.
            retry(webSocket, if (response?.code == 401 || response?.code == 429) Status.REJECTED else Status.DISCONNECTED)
        }

        private fun retry(webSocket: WebSocket, newStatus: Status = Status.DISCONNECTED) {
            main.post {
                if (webSocket === socket) {
                    status = newStatus
                    waiting = false
                    main.postDelayed(reconnect, retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
                }
            }
        }
    }

    private fun fetchPresenceKey() {
        val request = signedRequest("presence-key", "GET", ByteArray(0)).build()
        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val key = JSONObject(response.body!!.string()).getString("key")
                        main.post { PresenceBeacon.saveKey(app, key) }
                    }
                }
            } catch (_: IOException) {
                // Tried again the next time it connects.
            } catch (_: org.json.JSONException) {
                // Likewise, if the answer was unreadable.
            }
        }.start()
    }

    /** So a countdown the user dismissed doesn't come back on the next reconnect. */
    fun dismissLive() {
        socket?.send(JSONObject().put("type", "live_dismissed").toString())
    }

    fun stopAlarm() {
        socket?.send(JSONObject().put("type", "stop_alarm").toString())
    }

    private fun answerRequest(webSocket: WebSocket, data: JSONObject) {
        val answer = when (data.getString("type")) {
            "open_door" -> JSONObject().put("result", Door.open(app))
            "read_notifications" -> NotificationAccess.read(app)
            "add_place_reminder" -> JSONObject().put(
                "result",
                PlaceReminders.add(app, data.getString("text"), data.getDouble("latitude"), data.getDouble("longitude"), data.getDouble("radius").toFloat()),
            )
            "list_place_reminders" -> JSONObject().put("items", PlaceReminders.list(app))
            "remove_place_reminder" -> JSONObject().put("removed", PlaceReminders.remove(app, data.getString("reminder")))
            else -> JSONObject().put("result", NotificationAccess.askToReply(app, data.getString("notification"), data.getString("text")))
        }
        webSocket.send(answer.put("type", "answer").put("id", data.getString("id")).toString())
    }

    @SuppressLint("MissingPermission")
    private fun sendLocation(webSocket: WebSocket, id: String) {
        val manager = app.getSystemService(LocationManager::class.java)
        val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!allowed) {
            webSocket.send(locationError(id, "The app doesn't have location permission."))
            return
        }
        // GPS can stay silent indoors, so prefer the network provider when it's available.
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) }
        if (provider == null) {
            webSocket.send(locationError(id, "Location is turned off on the phone."))
            return
        }
        manager.getCurrentLocation(provider, null, app.mainExecutor) { location ->
            val known = location ?: manager.getLastKnownLocation(provider)
            webSocket.send(
                if (known != null) locationReply(id, known)
                else locationError(id, "The phone couldn't get a location fix. Background location may need 'Allow all the time'.")
            )
        }
    }

    private fun locationReply(id: String, location: Location) = JSONObject()
        .put("type", "location")
        .put("id", id)
        .put("latitude", location.latitude)
        .put("longitude", location.longitude)
        .put("accuracy", location.accuracy.toDouble())
        .toString()

    private fun locationError(id: String, error: String) = JSONObject()
        .put("type", "location")
        .put("id", id)
        .put("error", error)
        .toString()

    private fun addMessage(message: ChatMessage) {
        messages.add(message)
        while (messages.size > HISTORY_LIMIT) messages.removeAt(0)
        val saved = JSONArray()
        messages.forEach { saved.put(JSONObject().put("text", it.text).put("user", it.fromUser)) }
        history.write(saved.toString())
    }

    private fun loadHistory() {
        File(app.filesDir, "chat.json").delete() // from before history was encrypted
        val saved = JSONArray(history.read() ?: return)
        for (i in 0 until saved.length()) {
            val item = saved.getJSONObject(i)
            messages.add(ChatMessage(item.getString("text"), item.getBoolean("user")))
        }
    }
}
