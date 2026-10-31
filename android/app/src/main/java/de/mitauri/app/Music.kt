package de.mitauri.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    val accent: Color? = null,
    val positionMillis: Long = 0,
    val positionAt: Long = 0,
    val durationMillis: Long = 0,
)

enum class MusicAction { PREVIOUS, TOGGLE, NEXT, HIDE, OPEN }

/**
 * Follows how loud one band of the music is relative to its own recent range. Android scales what it hands over to
 * full strength, so absolute values barely move and only the recent range shows the beat.
 */
class BandMeter {
    private var floor = 40.0
    private var ceiling = 0.0

    fun level(decibels: Double): Float {
        floor = min(decibels, floor + FLOOR_RISE)
        ceiling = max(decibels, ceiling - CEILING_FALL)
        val range = max(ceiling - floor, MIN_RANGE)
        return ((decibels - floor) / range).coerceIn(0.0, 1.0).pow(1.5).toFloat()
    }

    private companion object {
        const val FLOOR_RISE = 0.05
        const val CEILING_FALL = 0.06
        const val MIN_RANGE = 8.0
    }
}

/**
 * How strong the bass to the treble of what's playing are right now, for the island's bars. Only those strengths are
 * read, and they're never kept. If Android refuses this, the bars move on their own instead.
 */
class MusicSound {
    private var visualizer: Visualizer? = null
    private var fft = ByteArray(0)
    private val meters = List(BANDS) { BandMeter() }
    private val levels = FloatArray(BANDS) { RESTING }

    @Volatile
    private var live = false

    fun open() {
        try {
            val created = Visualizer(0)
            created.captureSize = 256.coerceIn(Visualizer.getCaptureSizeRange()[0], Visualizer.getCaptureSizeRange()[1])
            created.enabled = true
            fft = ByteArray(created.captureSize)
            visualizer = created
            Log.i(TAG, "Music analysis opened")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Music analysis refused: ${error.message}")
        } catch (error: LinkageError) {
            Log.w(TAG, "Music analysis unavailable: ${error.message}")
        }
    }

    fun read() {
        val current = visualizer ?: return
        if (current.getFft(fft) != Visualizer.SUCCESS) return
        if (!live && fft.any { it != 0.toByte() }) {
            live = true
            Log.i(TAG, "Music analysis hears sound")
        }
        if (!live) return
        BAND_EDGES.forEachIndexed { band, (from, to) ->
            val level = meters[band].level(decibels(from, to))
            levels[band] = RESTING + (1f - RESTING) * level
        }
    }

    fun level(band: Int): Float = if (live) levels[band] else moving(band)

    fun close() {
        visualizer?.release()
        visualizer = null
    }

    private fun decibels(from: Int, to: Int): Double {
        val last = to.coerceAtMost(fft.size / 2 - 1)
        val peak = (from..last).maxOfOrNull { bin -> hypot(fft[bin * 2].toDouble(), fft[bin * 2 + 1].toDouble()) } ?: 0.0
        return if (peak < 1) 0.0 else 20 * log10(peak)
    }

    private fun moving(band: Int): Float {
        val time = System.currentTimeMillis() / 1000.0
        val wave = sin(time * (5.1 + band * 1.7) + band) * 0.6 + sin(time * (8.3 - band) + band * 2) * 0.4
        return (0.55 + 0.4 * wave).toFloat().coerceIn(RESTING, 1f)
    }

    companion object {
        const val BANDS = 4
        const val RESTING = 0.2f
        private const val TAG = "Mitauri"
        private val BAND_EDGES = listOf(1 to 2, 3 to 8, 9 to 24, 25 to 80)
    }
}

/** Follows Spotify on this phone for the island. Nothing about the song is stored or leaves the phone. */
object Music {
    const val PACKAGE = "com.spotify.music"
    private const val PAUSED_HOLD_MILLIS = 60_000L
    private const val ART_PIXELS = 96

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var controller: MediaController? = null
    private var wasPlaying = false
    private var pausedTooLong = true // Spotify sitting idle in the background shouldn't show anything.
    private var hidden = false
    private var artSource: Bitmap? = null
    private var art: ImageBitmap? = null
    private var accent: Color? = null

    private val pauseOver = Runnable {
        pausedTooLong = true
        refresh()
    }
    private val changes = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = follow(null)
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { follow(pick(it)) }

    fun start(context: Context) {
        stop()
        val owner = context.applicationContext
        app = owner
        val manager = owner.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(owner, NotificationWatcher::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessions, listener, main)
            follow(pick(manager.getActiveSessions(listener)))
        } catch (_: SecurityException) {
            // Notification access was just taken away, so there's nothing to follow.
        }
    }

    fun stop() {
        val owner = app ?: return
        owner.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions)
        follow(null)
        main.removeCallbacks(pauseOver)
        wasPlaying = false
        pausedTooLong = true
        app = null
    }

    fun act(action: MusicAction) {
        val controls = controller?.transportControls ?: return
        when (action) {
            MusicAction.PREVIOUS -> controls.skipToPrevious()
            MusicAction.NEXT -> controls.skipToNext()
            MusicAction.TOGGLE -> if (isPlayingNow()) controls.pause() else controls.play()
            MusicAction.HIDE -> {
                hidden = true
                refresh()
            }
            MusicAction.OPEN -> try {
                controller?.sessionActivity?.send()
            } catch (_: PendingIntent.CanceledException) {
                // Spotify was closed in the same moment, so there's nothing to open.
            }
        }
    }

    val isPlaying get() = isPlayingNow()

    /** Returns false if Spotify has no session to tell. */
    fun play(): Boolean {
        val controls = controller?.transportControls ?: return false
        controls.play()
        return true
    }

    fun seek(millis: Long) {
        controller?.transportControls?.seekTo(millis)
    }

    private fun pick(active: List<MediaController>?) = active?.firstOrNull { it.packageName == PACKAGE }

    private fun follow(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(changes)
        controller = next
        next?.registerCallback(changes, main)
        refresh()
    }

    private fun isPlayingNow() = when (controller?.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
        else -> false
    }

    private fun refresh() {
        val context = app ?: return
        val playing = isPlayingNow()
        if (playing) {
            main.removeCallbacks(pauseOver)
            pausedTooLong = false
        } else if (hidden) {
            // Hide lasts until the next pause, so the next listen shows up again.
            hidden = false
            pausedTooLong = true
        } else if (wasPlaying) {
            main.postDelayed(pauseOver, PAUSED_HOLD_MILLIS)
        }
        wasPlaying = playing
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        Island.setMusic(
            context,
            if (title.isBlank() || hidden || pausedTooLong) {
                null
            } else {
                val state = controller?.playbackState
                NowPlaying(
                    title,
                    metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                    artOf(metadata),
                    playing,
                    accent,
                    state?.position ?: 0,
                    state?.lastPositionUpdateTime ?: 0,
                    metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                )
            },
        )
    }

    /** Spotify's art can be a large picture, and only a small corner of the pill shows it. */
    private fun artOf(metadata: MediaMetadata?): ImageBitmap? {
        val source = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (source !== artSource) {
            artSource = source
            art = source?.let { Bitmap.createScaledBitmap(it, ART_PIXELS, ART_PIXELS, true).asImageBitmap() }
            accent = source?.let(::accentOf)
        }
        return art
    }

    /** The cover's average colour, made bright enough to read on black, for the bars and the progress line. */
    private fun accentOf(source: Bitmap): Color {
        val hsv = FloatArray(3)
        val pixel = try {
            Bitmap.createScaledBitmap(source, 1, 1, true).getPixel(0, 0)
        } catch (_: RuntimeException) {
            return Color.White
        }
        android.graphics.Color.colorToHSV(pixel, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceAtLeast(0.9f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}
