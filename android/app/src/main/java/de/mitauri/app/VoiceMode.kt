package de.mitauri.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** One spoken exchange. The microphone and player exist only during it, then are released. */
object VoiceMode {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = 480 // 30 ms
    private const val END_SILENCE_FRAMES = 20 // 0.6 s
    private const val PREPARE_SILENCE_FRAMES = 8 // 0.24 s: the rest of the wait hides the time hearing takes
    private const val NO_SPEECH_FRAMES = 200 // 6 s
    private const val MAX_FRAMES = 1000 // 30 s
    private const val SPEECH_START_FRAMES = 3
    private const val NOISE_FRAMES = 10
    private const val MIN_THRESHOLD = 300.0
    private const val MESSAGE_HOLD_MILLIS = 3_000L

    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")
    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { Island.hide() }
    private val network = Executors.newSingleThreadExecutor()
    private val voices = Executors.newSingleThreadExecutor()
    private var session = 0 // Work from an earlier session checks this and stops.
    private var player: MediaPlayer? = null
    private var appContext: Context? = null
    private var focus: AudioFocusRequest? = null

    /** Set during a call: runs after each exchange, with false when nobody spoke or it failed. */
    var afterExchange: ((Boolean) -> Unit)? = null

    @Volatile
    var isListening = false
        private set

    /** Must be called from a visible screen, because Android only lends the microphone to those. */
    fun listen(context: Context, onFinished: () -> Unit = {}) {
        val app = context.applicationContext
        appContext = app
        Mitauri.init(app)
        stop()
        Mitauri.connectIfNeeded()
        val current = session
        Island.show(app, IslandMode.LISTENING, "Listening")
        isListening = true
        Thread {
            val recording = try {
                record(current)
            } catch (_: RuntimeException) {
                main.post { if (current == session) fail("Microphone unavailable") }
                null
            }
            main.post {
                isListening = false
                onFinished()
                if (current == session) {
                    when {
                        recording != null -> send(app, recording, current)
                        failing -> Unit
                        else -> {
                            Island.hide()
                            afterExchange?.invoke(false)
                        }
                    }
                }
            }
        }.start()
    }

    /** Says something, then runs the next step, even if it couldn't be spoken. */
    fun speakThen(context: Context, text: String, then: () -> Unit) {
        val app = context.applicationContext
        appContext = app
        Mitauri.init(app)
        stop()
        Island.show(app, IslandMode.THINKING, "Thinking")
        val playback = Playback(app, session, then)
        text.split(SENTENCE_END).filter { it.isNotBlank() }.forEach { playback.add(it, "de") }
        playback.finishAdding()
    }

    fun fail(message: String) {
        failing = true
        Island.update(IslandMode.MESSAGE, message)
        main.removeCallbacks(hide)
        main.postDelayed(hide, MESSAGE_HOLD_MILLIS)
        afterExchange?.let { next -> main.postDelayed({ next(false) }, MESSAGE_HOLD_MILLIS) }
    }

    fun stop() {
        session++
        deleteSpeechFiles()
        failing = false
        isListening = false
        main.removeCallbacks(hide)
        releasePlayer()
        Island.hide()
    }

    private var failing = false

    /** Returns the speech, or null if nothing was said or the session ended. */
    @SuppressLint("MissingPermission")
    private fun record(current: Int): ByteArray? {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, FRAME_SAMPLES * 8),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED)
        val output = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        val bytes = ByteArray(FRAME_SAMPLES * 2)
        var noise = 0.0
        var frames = 0
        var loudStreak = 0
        var silence = 0
        var speaking = false
        try {
            recorder.startRecording()
            while (current == session && frames < MAX_FRAMES) {
                val read = recorder.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) break
                frames++
                var total = 0.0
                for (i in 0 until read) {
                    total += frame[i].toDouble() * frame[i]
                    bytes[i * 2] = frame[i].toByte()
                    bytes[i * 2 + 1] = (frame[i].toInt() shr 8).toByte()
                }
                output.write(bytes, 0, read * 2)
                val level = sqrt(total / read)
                Island.level = (sqrt(level) / 60).toFloat().coerceIn(0f, 1f)
                if (frames <= NOISE_FRAMES) {
                    noise = maxOf(noise, level)
                    continue
                }
                val loud = level > maxOf(noise * 2.5, MIN_THRESHOLD)
                loudStreak = if (loud) loudStreak + 1 else 0
                if (loudStreak >= SPEECH_START_FRAMES) speaking = true
                silence = if (loud) 0 else silence + 1
                if (speaking && silence >= END_SILENCE_FRAMES) break
                if (speaking && silence == PREPARE_SILENCE_FRAMES) prepare(output.toByteArray())
                if (!speaking && frames >= NO_SPEECH_FRAMES) return null
            }
        } finally {
            recorder.release()
            Island.level = 0f
        }
        return if (speaking && current == session) output.toByteArray() else null
    }

    // On the same thread as the recording itself, so the server always gets them in this order.
    private fun prepare(pcm: ByteArray) = network.execute {
        try {
            Mitauri.prepareVoice(pcm)
        } catch (_: Exception) {
            // Only saves time. The recording that follows still gets heard.
        }
    }

    private fun send(app: Context, recording: ByteArray, current: Int) {
        val ended = SystemClock.elapsedRealtime()
        Island.update(IslandMode.THINKING, "Thinking")
        val conversation = afterExchange
        val playback = Playback(app, current, conversation?.let { next -> { next(true) } }, ended)
        network.execute {
            try {
                var language = "en"
                var answered = false
                var firstVoice = false
                Mitauri.askByVoice(recording) { event ->
                    when {
                        event.has("transcript") -> {
                            Log.i("Mitauri", "Voice timing: heard ${SystemClock.elapsedRealtime() - ended} ms after recording")
                            language = event.getString("language")
                            val heard = event.getString("transcript")
                            main.post {
                                if (heard.isNotBlank()) Mitauri.addHeard(heard)
                                else if (current == session && conversation == null) fail("I didn't catch that")
                            }
                        }
                        event.has("door") -> main.post { DoorOpener.command(app) }
                        event.has("sentence") -> {
                            if (!firstVoice) {
                                firstVoice = true
                                Log.i("Mitauri", "Voice timing: first voice arrived ${SystemClock.elapsedRealtime() - ended} ms after recording")
                            }
                            playback.add(
                                event.getString("sentence"),
                                language,
                                event.optString("audio").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) },
                            )
                        }
                        event.has("reply") -> {
                            answered = true
                            val reply = event.getString("reply")
                            main.post {
                                // A reply you dismissed still lands in the chat, but opens nothing.
                                if (reply.isNotBlank()) Mitauri.addVoiceReply(reply, current == session)
                                playback.finishAdding()
                            }
                        }
                    }
                }
                // Whatever the server sent, never stay stuck on Thinking.
                if (!answered) main.post { if (current == session) fail("The server needs a restart") }
            } catch (_: Exception) {
                main.post { if (current == session) fail("Mitauri is offline") }
            }
        }
    }

    /** Plays sentences in order, each as soon as its voice is here. */
    private class Playback(
        private val app: Context,
        private val current: Int,
        private val then: (() -> Unit)? = null,
        private val ended: Long? = null,
    ) {
        private var loggedStart = false
        private val ready = ArrayDeque<File>()
        private var added = 0
        private var played = 0
        private var playing = false
        private var complete = false
        private var failed = false
        private val isCurrent get() = current == session

        /** audio is the voice if the server already sent it. Otherwise it gets fetched. */
        fun add(sentence: String, language: String, audio: ByteArray? = null) {
            val index = added++
            voices.execute {
                if (current != session) return@execute
                try {
                    val file = File(app.cacheDir, "speech-$current-$index.wav")
                    file.writeBytes(audio ?: Mitauri.fetchSpeech(sentence, language))
                    main.post {
                        ready.addLast(file)
                        pump()
                    }
                } catch (_: Exception) {
                    main.post {
                        failed = true
                        played++
                        pump()
                    }
                }
            }
        }

        fun finishAdding() {
            complete = true
            pump()
        }

        private fun pump() {
            if (!isCurrent || playing) return
            val file = ready.removeFirstOrNull()
            if (file != null) {
                play(file)
            } else if (complete && played >= added) {
                when {
                    then != null -> then.invoke()
                    failed -> fail("The answer is in the chat")
                    added > 0 -> finishSoon()
                }
            }
        }

        private fun play(file: File) {
            playing = true
            Island.update(IslandMode.SPEAKING, "Speaking")
            requestFocus(app)
            releaseMediaPlayer()
            val done = {
                file.delete()
                playing = false
                played++
                pump()
            }
            player = MediaPlayer().apply {
                setAudioAttributes(speechAttributes)
                setOnCompletionListener { done() }
                setOnErrorListener { _, _, _ ->
                    failed = true
                    done()
                    true
                }
                try {
                    setDataSource(file.path)
                    prepare()
                    start()
                    if (ended != null && !loggedStart) {
                        loggedStart = true
                        Log.i("Mitauri", "Voice timing: speaking ${SystemClock.elapsedRealtime() - ended} ms after recording")
                    }
                } catch (_: Exception) {
                    // Audio that can't be played shouldn't crash the app. The answer is still in the chat.
                    failed = true
                    main.post(done)
                }
            }
        }
    }

    // Spoken replies are private, so they don't stay on disk after they've played.
    private fun deleteSpeechFiles() {
        appContext?.cacheDir?.listFiles { file -> file.name.startsWith("speech-") }?.forEach { it.delete() }
    }

    private fun finishSoon() {
        deleteSpeechFiles()
        releasePlayer()
        Island.hide()
    }

    private val speechAttributes = AudioAttributes.Builder()
        // Not USAGE_ASSISTANT: that has its own volume, which the phone's buttons don't change.
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audio: AudioManager? = null

    private fun requestFocus(app: Context) {
        if (focus != null) return
        val manager = app.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        manager.requestAudioFocus(request)
        audio = manager
        focus = request
    }

    private fun releaseMediaPlayer() {
        player?.release()
        player = null
    }

    private fun releasePlayer() {
        releaseMediaPlayer()
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }
}
