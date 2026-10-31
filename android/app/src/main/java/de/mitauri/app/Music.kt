package de.mitauri.app

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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

data class NowPlaying(val title: String, val artist: String, val art: ImageBitmap?, val playing: Boolean)

enum class MusicAction { PREVIOUS, TOGGLE, NEXT, HIDE }

/**
 * How strong the bass to the treble of what's playing are right now, for the island's bars. Only those strengths are
 * read, and they're never kept. If Android refuses this, the bars move on their own instead.
 */
class MusicSound {
    private var visualizer: Visualizer? = null
    private var failed = false
    private var heardSound = false
    private var fft = ByteArray(0)

    fun setActive(active: Boolean) {
        if (!active) {
            release()
        } else if (visualizer == null && !failed) {
            try {
                visualizer = Visualizer(0).apply {
                    captureSize = 256.coerceIn(Visualizer.getCaptureSizeRange()[0], Visualizer.getCaptureSizeRange()[1])
                    enabled = true
                }
                fft = ByteArray(visualizer!!.captureSize)
            } catch (_: RuntimeException) {
                failed = true
            } catch (_: LinkageError) {
                failed = true
            }
        }
    }

    fun levels(): List<Float> {
        val current = visualizer
        if (current != null && current.getFft(fft) == Visualizer.SUCCESS && fft.any { it != 0.toByte() }) heardSound = true
        if (current == null || !heardSound) return moving()
        return BAND_EDGES.map { (from, to) -> strength(from, to) }
    }

    fun release() {
        visualizer?.release()
        visualizer = null
    }

    private fun strength(from: Int, to: Int): Float {
        val last = to.coerceAtMost(fft.size / 2 - 1)
        val peak = (from..last).maxOfOrNull { bin -> hypot(fft[bin * 2].toDouble(), fft[bin * 2 + 1].toDouble()) } ?: 0.0
        return if (peak < 1) RESTING else (20 * log10(peak) / MAX_DECIBELS).toFloat().coerceIn(RESTING, 1f)
    }

    private fun moving(): List<Float> {
        val time = System.currentTimeMillis() / 1000.0
        return List(BANDS) { band ->
            val wave = sin(time * (5.1 + band * 1.7) + band) * 0.6 + sin(time * (8.3 - band) + band * 2) * 0.4
            (0.55 + 0.4 * wave).toFloat().coerceIn(RESTING, 1f)
        }
    }

    companion object {
        const val BANDS = 4
        const val RESTING = 0.2f
        private const val MAX_DECIBELS = 42.0 // A full-scale reading in the visualizer's 8 bits.
        private val BAND_EDGES = listOf(1 to 2, 3 to 8, 9 to 24, 25 to 80)
    }
}

/** Follows Spotify on this phone for the island. Nothing about the song is stored or leaves the phone. */
object Music {
    private const val PACKAGE = "com.spotify.music"
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
        app = null
    }

    fun act(action: MusicAction) {
        val controls = controller?.transportControls ?: return
        when (action) {
            MusicAction.PREVIOUS -> controls.skipToPrevious()
            MusicAction.NEXT -> controls.skipToNext()
            MusicAction.TOGGLE -> if (isPlaying()) controls.pause() else controls.play()
            MusicAction.HIDE -> {
                hidden = true
                refresh()
            }
        }
    }

    private fun pick(active: List<MediaController>?) = active?.firstOrNull { it.packageName == PACKAGE }

    private fun follow(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(changes)
        controller = next
        next?.registerCallback(changes, main)
        refresh()
    }

    private fun isPlaying() = when (controller?.playbackState?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
        else -> false
    }

    private fun refresh() {
        val context = app ?: return
        val playing = isPlaying()
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
                NowPlaying(title, metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), artOf(metadata), playing)
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
        }
        return art
    }
}
