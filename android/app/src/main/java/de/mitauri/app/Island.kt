package de.mitauri.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

enum class IslandMode { LISTENING, THINKING, SPEAKING, MESSAGE, LIVE, MUSIC }

data class LiveCountdown(val title: String, val untilMillis: Long, val detail: String)

/** Where the camera sits, as seen from the top of the screen. A width of zero means it isn't on the top edge. */
data class CameraHole(val width: Dp, val centerY: Dp) {
    companion object {
        val NONE = CameraHole(0.dp, 15.dp)
    }
}

private val PILL_MIN_TOP = 2.dp
private val PILL_EDGE = 6.dp
private val ROW_HEIGHT = 22.dp
private val MUSIC_TITLE_WIDTH = 58.dp // Leaves the status bar icons on both sides uncovered.
private val CAMERA_MARGIN = 2.dp
private const val NOW_HOLD_MILLIS = 60_000L

// Android's box for the camera on this phone ends above the real hole, which is level with the status bar icons.
private const val HOLE_BELOW_BOX_PIXELS = 32

fun countdownText(millisLeft: Long): String {
    val seconds = ((millisLeft + 999) / 1000).coerceAtLeast(0)
    return when {
        seconds == 0L -> "Now"
        seconds >= 3600 -> "%d h %02d".format(seconds / 3600, seconds / 60 % 60)
        else -> "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

/** A status pill around the camera. It only exists while active, so an idle phone draws nothing. */
object Island {
    private const val EXIT_MILLIS = 220L
    private val main = Handler(Looper.getMainLooper())
    private val remove = Runnable { removeView() }
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var app: Context? = null

    private var mode by mutableStateOf(IslandMode.MESSAGE)
    private var text by mutableStateOf("")
    val currentMode get() = mode
    val currentText get() = text

    private val expire = Runnable { clearLive() }
    private var live by mutableStateOf<LiveCountdown?>(null)
    private var music by mutableStateOf<NowPlaying?>(null)
    private var expanded by mutableStateOf(false)
    private var camera by mutableStateOf(CameraHole.NONE)

    /** While a screen shows this state itself, the pill stays away. */
    var embedded = false
        set(value) {
            field = value
            if (!value) showBackground()
        }
    private var shown by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)

    fun show(context: Context, newMode: IslandMode, newText: String) {
        app = context.applicationContext
        main.removeCallbacks(remove)
        update(newMode, newText)
        if (embedded) removeView() else if (view == null) addView()
        shown = true
    }

    fun update(newMode: IslandMode, newText: String) {
        if (newMode != mode) expanded = false
        mode = newMode
        text = newText
    }

    /** A countdown stays in the pill. Voice takes over for a moment, then hands it back. */
    fun setLive(context: Context, countdown: LiveCountdown) {
        app = context.applicationContext
        live = countdown
        expanded = false
        main.removeCallbacks(expire)
        main.postDelayed(expire, countdown.untilMillis - System.currentTimeMillis() + NOW_HOLD_MILLIS)
        if (!embedded && (!shown || mode == IslandMode.LIVE || mode == IslandMode.MUSIC)) showLive()
    }

    /** The song stays in the pill while nothing more urgent is there. */
    fun setMusic(context: Context, playing: NowPlaying?) {
        app = context.applicationContext
        music = playing
        if (playing == null) {
            if (mode == IslandMode.MUSIC) hide()
        } else if (live == null && !embedded && (!shown || mode == IslandMode.MUSIC)) {
            show(context, IslandMode.MUSIC, "")
        }
    }

    fun clearLive() {
        main.removeCallbacks(expire)
        live = null
        expanded = false
        if (mode == IslandMode.LIVE) hide()
    }

    private fun showLive() {
        val context = app ?: return
        show(context, IslandMode.LIVE, "")
    }

    /** Puts back what was there before voice or a message took over. Returns false if nothing was. */
    private fun showBackground(): Boolean {
        val context = app ?: return false
        when {
            live != null -> show(context, IslandMode.LIVE, "")
            music != null -> show(context, IslandMode.MUSIC, "")
            else -> return false
        }
        return true
    }

    fun hide() {
        if (!embedded && showBackground()) return
        shown = false
        main.removeCallbacks(remove)
        main.postDelayed(remove, EXIT_MILLIS)
    }

    private fun addView() {
        val context = app ?: return
        if (!Settings.canDrawOverlays(context)) return
        val overlayOwner = OverlayOwner()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                Pill(
                    mode, text, shown, live, expanded,
                    music = music,
                    camera = camera,
                    onExpired = ::clearLive,
                    onDismiss = {
                        clearLive()
                        Mitauri.dismissLive()
                    },
                    onMusic = Music::act,
                ) { onTap(context) }
            }
        }
        val root = object : FrameLayout(context) {
            // Checked here, before Compose can claim the event for itself.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (mode == IslandMode.LIVE || mode == IslandMode.MUSIC) {
                        expanded = false // A tap elsewhere never stops a countdown or the music.
                    } else if (!DoorOpener.cancel() && mode != IslandMode.LISTENING) {
                        VoiceMode.stop()
                    } else if (mode == IslandMode.MESSAGE) {
                        hide()
                    }
                    return true
                }
                return super.dispatchTouchEvent(event)
            }

            // The camera moves to another edge when the phone turns.
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                val params = layoutParams as? WindowManager.LayoutParams ?: return
                placeOnCamera(context, params)
                context.getSystemService(WindowManager::class.java).updateViewLayout(this, params)
            }
        }.apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            addView(composeView)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            placeOnCamera(context, this)
        }
        context.getSystemService(WindowManager::class.java).addView(root, params)
        view = root
        owner = overlayOwner
    }

    private fun placeOnCamera(context: Context, params: WindowManager.LayoutParams) {
        params.x = locateCamera(context)
        val top = (camera.centerY - ROW_HEIGHT / 2 - PILL_EDGE).coerceAtLeast(PILL_MIN_TOP)
        params.y = (top.value * context.resources.displayMetrics.density).toInt()
    }

    /** Remembers where the camera is and returns how far the pill must move sideways to sit on it. */
    private fun locateCamera(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hole = display?.cutout?.boundingRectTop
        if (hole == null || hole.isEmpty) {
            camera = CameraHole.NONE
            return 0
        }
        camera = CameraHole((hole.width() / density).dp, ((hole.centerY() + HOLE_BELOW_BOX_PIXELS) / density).dp)
        return hole.centerX() - Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }.x / 2
    }

    private fun removeView() {
        val current = view ?: return
        app?.getSystemService(WindowManager::class.java)?.removeView(current)
        owner?.destroy()
        view = null
        owner = null
    }

    private fun onTap(context: Context) {
        if (mode == IslandMode.LIVE || mode == IslandMode.MUSIC) {
            expanded = !expanded
            return
        }
        if (DoorOpener.cancel()) {
            hide()
            return
        }
        VoiceMode.stop()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun Pill(
    mode: IslandMode,
    text: String,
    shown: Boolean,
    live: LiveCountdown? = null,
    expanded: Boolean = false,
    clock: () -> Long = System::currentTimeMillis,
    music: NowPlaying? = null,
    camera: CameraHole = CameraHole.NONE,
    onExpired: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onMusic: (MusicAction) -> Unit = {},
    onTap: () -> Unit,
) {
    val scale by animateFloatAsState(
        if (shown) 1f else 0.6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "alpha")
    // The row sits level with the camera, and anything longer goes below it.
    val below = 6.dp
    Column(
        Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0f)
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(Color.Black, RoundedCornerShape(26.dp))
            .clickable(onClick = onTap)
            .animateContentSize(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            .widthIn(min = 132.dp, max = 300.dp)
            .padding(start = 12.dp, end = 12.dp, top = PILL_EDGE, bottom = PILL_EDGE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            mode == IslandMode.LIVE && live != null -> LiveContent(live, expanded, camera, below, clock, onExpired, onDismiss)
            mode == IslandMode.MUSIC && music != null -> MusicContent(music, expanded, camera, below, onMusic)
            mode == IslandMode.MESSAGE -> {
                AroundCamera(camera, { Bars(mode) }, { Label("Mitauri", MitauriColors.Muted) })
                Text(
                    text,
                    Modifier.padding(top = below, bottom = 8.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            else -> AroundCamera(camera, { Bars(mode) }, {
                AnimatedContent(text, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "text") {
                    Label(it)
                }
            })
        }
    }
}

/** Two sides of equal width with the camera's width free between them, so the camera stays in the middle. */
@Composable
private fun AroundCamera(camera: CameraHole, start: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) { start() }
            Row(verticalAlignment = Alignment.CenterVertically) { end() }
        },
        modifier = Modifier.heightIn(min = ROW_HEIGHT),
    ) { (first, second), constraints ->
        val gap = (camera.width + CAMERA_MARGIN * 2).roundToPx()
        val room = Constraints(maxWidth = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0))
        val left = first.measure(room)
        val right = second.measure(room)
        val side = maxOf(left.width, right.width)
        val height = maxOf(left.height, right.height, constraints.minHeight)
        layout(side * 2 + gap, height) {
            left.place(side - left.width, (height - left.height) / 2)
            right.place(side + gap, (height - right.height) / 2)
        }
    }
}

@Composable
private fun Label(text: String, color: Color = Color.White) = Text(
    text,
    color = color,
    fontSize = 14.sp,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

/** Updates once a second at most, and only while on screen, so it stays cheap on the battery. */
@Composable
private fun LiveContent(
    live: LiveCountdown,
    expanded: Boolean,
    camera: CameraHole,
    below: Dp,
    clock: () -> Long,
    onExpired: () -> Unit,
    onDismiss: () -> Unit,
) {
    var now by remember(live) { mutableLongStateOf(clock()) }
    LaunchedEffect(live) {
        // Delays stop while the phone sleeps, so the clock decides when this is over.
        while (now < live.untilMillis + NOW_HOLD_MILLIS) {
            delay(1000 - (now % 1000).coerceAtMost(999))
            now = clock()
        }
        onExpired()
    }
    val left = live.untilMillis - now
    val colour = if (left <= 0) MitauriColors.Warning else MitauriColors.Accent
    AroundCamera(
        camera,
        {
            Canvas(Modifier.padding(end = 8.dp).size(9.dp)) { drawCircle(colour) }
            Label(live.title)
        },
        {
            Text(
                countdownText(left),
                color = colour,
                maxLines = 1,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
        },
    )
    if (expanded) {
        Text(live.title, Modifier.padding(top = below), color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (live.detail.isNotBlank()) {
            Text(
                live.detail,
                Modifier.padding(top = 4.dp),
                color = MitauriColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Dismiss",
            Modifier.padding(top = 6.dp, bottom = 8.dp).clickable(onClick = onDismiss).padding(vertical = 6.dp, horizontal = 12.dp),
            color = MitauriColors.AccentSoft,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MusicContent(music: NowPlaying, expanded: Boolean, camera: CameraHole, below: Dp, onAction: (MusicAction) -> Unit) {
    AroundCamera(
        camera,
        {
            music.art?.let {
                Image(it, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
            }
        },
        {
            if (expanded) {
                MusicBars(music.playing)
            } else {
                Box(Modifier.widthIn(max = MUSIC_TITLE_WIDTH)) { Label(music.title) }
            }
        },
    )
    if (expanded) {
        Text(music.title, Modifier.padding(top = below), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (music.artist.isNotBlank()) {
            Text(music.artist, Modifier.padding(top = 2.dp), color = MitauriColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicButton(MusicAction.PREVIOUS, music.playing, onAction)
            MusicButton(MusicAction.TOGGLE, music.playing, onAction)
            MusicButton(MusicAction.NEXT, music.playing, onAction)
        }
        Text(
            "Hide",
            Modifier.padding(bottom = 8.dp).clickable { onAction(MusicAction.HIDE) }.padding(vertical = 6.dp, horizontal = 12.dp),
            color = MitauriColors.AccentSoft,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Follows the music, and rests while the screen is off, because music can play for hours. */
@Composable
private fun MusicBars(playing: Boolean) {
    val context = LocalContext.current
    var levels by remember { mutableStateOf(List(MusicSound.BANDS) { MusicSound.RESTING }) }
    LaunchedEffect(playing) {
        val sound = MusicSound()
        try {
            while (true) {
                val awake = context.getSystemService(PowerManager::class.java)?.isInteractive != false
                sound.setActive(playing && awake)
                if (!awake) {
                    delay(1000)
                    continue
                }
                val targets = if (playing) sound.levels() else List(MusicSound.BANDS) { MusicSound.RESTING }
                levels = levels.zip(targets) { shown, target -> shown + (target - shown) * if (target > shown) 0.7f else 0.3f }
                delay(50)
            }
        } finally {
            sound.release()
        }
    }
    Canvas(Modifier.padding(end = 8.dp).size(width = 19.dp, height = 16.dp)) {
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * levels.size) / (levels.size - 1)
        levels.forEachIndexed { index, energy ->
            val height = (size.height * energy).coerceAtLeast(barWidth)
            drawRoundRect(
                MitauriColors.Success,
                Offset(index * (barWidth + gap), (size.height - height) / 2),
                Size(barWidth, height),
                CornerRadius(barWidth / 2),
            )
        }
    }
}

@Composable
private fun MusicButton(action: MusicAction, playing: Boolean, onAction: (MusicAction) -> Unit) {
    Canvas(Modifier.clickable { onAction(action) }.padding(14.dp).size(20.dp)) {
        val w = size.width
        val h = size.height
        fun triangle(fromX: Float, toX: Float) = Path().apply {
            moveTo(fromX, 0f)
            lineTo(toX, h / 2)
            lineTo(fromX, h)
            close()
        }
        val bar = w * 0.14f
        when (action) {
            MusicAction.TOGGLE -> if (playing) {
                drawRect(Color.White, Offset(w * 0.2f, 0f), Size(w * 0.2f, h))
                drawRect(Color.White, Offset(w * 0.6f, 0f), Size(w * 0.2f, h))
            } else {
                drawPath(triangle(w * 0.15f, w * 0.95f), Color.White)
            }
            MusicAction.NEXT -> {
                drawPath(triangle(0f, w - bar), Color.White)
                drawRect(Color.White, Offset(w - bar, 0f), Size(bar, h))
            }
            else -> {
                drawPath(triangle(w, bar), Color.White)
                drawRect(Color.White, Offset.Zero, Size(bar, h))
            }
        }
    }
}

@Composable
private fun Bars(mode: IslandMode) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    val color = when (mode) {
        IslandMode.LISTENING -> MitauriColors.Success
        IslandMode.THINKING -> MitauriColors.Accent
        IslandMode.SPEAKING -> MitauriColors.AccentSoft
        IslandMode.MESSAGE, IslandMode.LIVE, IslandMode.MUSIC -> MitauriColors.Warning
    }
    Canvas(Modifier.size(width = 26.dp, height = 22.dp)) {
        val shapes = listOf(0.55f, 1f, 0.7f, 0.9f)
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * shapes.size) / (shapes.size - 1)
        shapes.forEachIndexed { index, shape ->
            val energy = when (mode) {
                IslandMode.LISTENING -> 0.25f + 0.75f * Island.level
                IslandMode.THINKING, IslandMode.SPEAKING -> 0.3f + 0.7f * ((pulse + index * 0.3f) % 1f)
                IslandMode.MESSAGE, IslandMode.LIVE, IslandMode.MUSIC -> 0.45f
            }
            val height = (size.height * shape * energy).coerceAtLeast(barWidth)
            drawRoundRect(
                color,
                Offset(index * (barWidth + gap), (size.height - height) / 2),
                Size(barWidth, height),
                CornerRadius(barWidth / 2),
            )
        }
    }
}

private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
