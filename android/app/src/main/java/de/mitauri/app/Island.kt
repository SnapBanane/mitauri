package de.mitauri.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class IslandMode { LISTENING, THINKING, SPEAKING, MESSAGE, LIVE, MUSIC }

data class LiveCountdown(val title: String, val untilMillis: Long, val detail: String)

/** Where the camera sits, as seen from the top of the screen. A width of zero means it isn't on the top edge. */
data class CameraHole(val width: Dp, val centerY: Dp) {
    val pillTop get() = (centerY - ROW_HEIGHT / 2 - PILL_EDGE).coerceAtLeast(PILL_MIN_TOP)

    companion object {
        val NONE = CameraHole(0.dp, 15.dp)
    }
}

private val PILL_MIN_TOP = 2.dp
private val PILL_EDGE = 6.dp
private val ROW_HEIGHT = 22.dp
private val PILL_MAX_WIDTH = 340.dp
private val PILL_MAX_RADIUS = 30.dp
private val MUSIC_TITLE_WIDTH = 58.dp // Leaves the status bar icons on both sides uncovered.
private val CAMERA_MARGIN = 2.dp
private val BELOW_CAMERA = 40.dp // Expanded content starts here, clear of the camera.
private val SWIPE_DISTANCE = 18.dp
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

fun songTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Lets the island draw above the status bar, which is the only way taps on it reach it, and spares the phone's
 * "displayed over other apps" notice. It asks for no events and no window content, so it reads nothing on the screen.
 */
class IslandService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Island.hostChanged(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Island.hostChanged(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        var instance: IslandService? = null
            private set
    }
}

/** A status pill around the camera. It only exists while active, so an idle phone draws nothing. */
object Island {
    private const val EXIT_MILLIS = 480L
    private const val COLLAPSE_MILLIS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private val remove = Runnable { removeView() }
    private val collapse = Runnable { expanded = false }
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var owner: OverlayOwner? = null
    private var app: Context? = null
    private var watching = false

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
        attach(context)
        main.removeCallbacks(remove)
        update(newMode, newText)
        shown = true
        sync()
    }

    fun update(newMode: IslandMode, newText: String) {
        if (newMode != mode) changeExpanded(false)
        mode = newMode
        text = newText
    }

    /** A countdown stays in the pill. Voice takes over for a moment, then hands it back. */
    fun setLive(context: Context, countdown: LiveCountdown) {
        attach(context)
        live = countdown
        changeExpanded(false)
        main.removeCallbacks(expire)
        main.postDelayed(expire, countdown.untilMillis - System.currentTimeMillis() + NOW_HOLD_MILLIS)
        if (!embedded && (!shown || mode == IslandMode.LIVE || mode == IslandMode.MUSIC)) showLive()
    }

    /** The song stays in the pill while nothing more urgent is there. */
    fun setMusic(context: Context, playing: NowPlaying?) {
        attach(context)
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
        changeExpanded(false)
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
        changeExpanded(false)
        main.removeCallbacks(remove)
        main.postDelayed(remove, EXIT_MILLIS)
    }

    /** The accessibility service came or went, so the pill moves to the window type that's now possible. */
    fun hostChanged(context: Context) {
        attach(context)
        removeView()
        sync()
    }

    private fun attach(context: Context) {
        val owner = context.applicationContext
        app = owner
        if (watching) return
        watching = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = sync()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            owner.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            owner.registerReceiver(receiver, filter)
        }
    }

    /** Never over the lock screen or a dark screen, and music keeps out of the way of a sideways video or game. */
    private fun allowedNow(context: Context): Boolean {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return !embedded &&
            context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            !(landscape && mode == IslandMode.MUSIC)
    }

    private fun sync() {
        val context = app ?: return
        when {
            !allowedNow(context) -> removeView()
            shown && view == null -> addView()
            view != null -> {
                val current = params ?: return
                current.x = locateCamera(context)
                windowManager()?.updateViewLayout(view, current)
            }
        }
    }

    private fun windowManager(): WindowManager? =
        (IslandService.instance ?: app)?.getSystemService(WindowManager::class.java)

    private fun addView() {
        val host: Context = IslandService.instance ?: app ?: return
        val overService = IslandService.instance != null
        if (!overService && !Settings.canDrawOverlays(host)) return
        val overlayOwner = OverlayOwner()
        val composeView = ComposeView(host).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                Box(Modifier.fillMaxSize().padding(top = camera.pillTop), contentAlignment = Alignment.TopCenter) {
                    Pill(
                        mode, text, shown, live, expanded,
                        music = music,
                        camera = camera,
                        animated = true,
                        onBounds = ::fitWindow,
                        onExpired = ::clearLive,
                        onDismiss = {
                            clearLive()
                            Mitauri.dismissLive()
                        },
                        onMusic = {
                            keepOpen()
                            Music.act(it)
                        },
                        onSeek = {
                            keepOpen()
                            Music.seek(it)
                        },
                        onSwipe = ::onSwipe,
                    ) { onTap(host) }
                }
            }
        }
        val root = object : FrameLayout(host) {
            // Checked here, before Compose can claim the event for itself.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (mode == IslandMode.LIVE || mode == IslandMode.MUSIC) {
                        changeExpanded(false) // A tap elsewhere never stops a countdown or the music.
                    } else if (!DoorOpener.cancel() && mode != IslandMode.LISTENING) {
                        VoiceMode.stop()
                    } else if (mode == IslandMode.MESSAGE) {
                        hide()
                    }
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            addView(composeView)
        }
        val density = host.resources.displayMetrics.density
        val start = (ROW_HEIGHT.value * 2 * density).toInt()
        val layout = WindowManager.LayoutParams(
            start,
            start,
            if (overService) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = locateCamera(host)
            y = 0
        }
        windowManager()?.addView(root, layout)
        view = root
        params = layout
        owner = overlayOwner
    }

    /**
     * The window only changes size around an animation, never during one, because resizing it each frame is what
     * stutters. It covers just the pill once settled, so touches beside the pill still reach what's below.
     */
    private fun fitWindow(size: IntSize) {
        val current = params ?: return
        val attached = view ?: return
        val density = attached.resources.displayMetrics.density
        val width = size.width + size.width % 2 // Even, so the pill stays exactly on the camera.
        val height = size.height + (camera.pillTop.value * density).roundToInt()
        if (current.width == width && current.height == height) return
        current.width = width
        current.height = height
        windowManager()?.updateViewLayout(attached, current)
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
        windowManager()?.removeViewImmediate(current)
        owner?.destroy()
        view = null
        params = null
        owner = null
    }

    private fun changeExpanded(value: Boolean) {
        main.removeCallbacks(collapse)
        expanded = value
        if (value) main.postDelayed(collapse, COLLAPSE_MILLIS)
    }

    private fun keepOpen() {
        if (expanded) changeExpanded(true)
    }

    private fun canExpand() = mode == IslandMode.LIVE || mode == IslandMode.MUSIC

    private fun onSwipe(down: Boolean) {
        if (canExpand() && down != expanded) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            changeExpanded(down)
        }
    }

    private fun onTap(context: Context) {
        if (canExpand()) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            changeExpanded(!expanded)
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

private val MORPH = spring<Float>(dampingRatio = 0.76f, stiffness = 340f, visibilityThreshold = 1f)

/**
 * One black shape that grows out of the camera and morphs between its states. Only the shape's size animates, so the
 * content inside isn't laid out again each frame.
 */
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
    animated: Boolean = false,
    onBounds: (IntSize) -> Unit = {},
    onExpired: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onMusic: (MusicAction) -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSwipe: (Boolean) -> Unit = {},
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val resting = with(density) { ROW_HEIGHT.toPx() }
    val width = remember { Animatable(resting, visibilityThreshold = 1f) }
    val height = remember { Animatable(resting, visibilityThreshold = 1f) }
    val fade = remember { Animatable(if (animated) 0f else 1f) }
    var target by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(density) { 14.dp.toPx() }

    if (animated) {
        LaunchedEffect(target, shown) {
            if (target == IntSize.Zero) return@LaunchedEffect
            val goalWidth = if (shown) target.width.toFloat() else resting
            val goalHeight = if (shown) target.height.toFloat() else resting
            // Room for the whole movement first, including the slight overshoot of the spring.
            onBounds(
                IntSize(
                    (max(width.value, goalWidth) * 1.06f + margin).roundToInt(),
                    (max(height.value, goalHeight) * 1.06f + margin).roundToInt(),
                ),
            )
            coroutineScope {
                launch { width.animateTo(goalWidth, MORPH) }
                launch { height.animateTo(goalHeight, MORPH) }
                launch { fade.animateTo(if (shown) 1f else 0f, if (shown) tween(120) else tween(200, 200)) }
            }
            onBounds(IntSize(goalWidth.roundToInt(), goalHeight.roundToInt()))
        }
    }

    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val swipeDistance = with(density) { SWIPE_DISTANCE.toPx() }
    val maxRadius = with(density) { PILL_MAX_RADIUS.toPx() }
    val sound = rememberBarLevels(mode == IslandMode.MUSIC && music?.playing == true)

    Layout(
        content = {
            Box(Modifier.onSizeChanged { target = it }) {
                AnimatedContent(
                    targetState = mode to expanded,
                    transitionSpec = {
                        (fadeIn(tween(200, 70)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 380f), initialScale = 0.9f)) togetherWith
                            fadeOut(tween(80)) using SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "content",
                ) { (shownMode, open) ->
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = PILL_EDGE),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when {
                            shownMode == IslandMode.LIVE && live != null -> LiveContent(live, open, camera, clock, onExpired, onDismiss)
                            shownMode == IslandMode.MUSIC && music != null ->
                                if (open) MusicExpanded(music, sound, onMusic, onSeek) else MusicCompact(music, sound, camera)
                            shownMode == IslandMode.MESSAGE -> {
                                AroundCamera(camera, { Bars(shownMode) }, { Label("Mitauri", MitauriColors.Muted) })
                                Text(
                                    text,
                                    Modifier.padding(top = 8.dp, bottom = 6.dp),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            else -> AroundCamera(camera, { Bars(shownMode) }, {
                                Crossfade(text, animationSpec = tween(160), label = "text") { Label(it) }
                            })
                        }
                    }
                }
            }
        },
        modifier = Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0f)
                scaleX = press
                scaleY = press
                alpha = fade.value
                shape = RoundedCornerShape(min(size.height / 2, maxRadius))
                clip = true
            }
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap() },
                )
            }
            .pointerInput(Unit) {
                var moved = 0f
                detectVerticalDragGestures(
                    onDragStart = { moved = 0f },
                    onDragEnd = { if (kotlin.math.abs(moved) > swipeDistance) swipe(moved > 0) },
                ) { change, amount ->
                    change.consume()
                    moved += amount
                }
            },
    ) { measurables, _ ->
        val content = measurables[0].measure(Constraints(maxWidth = PILL_MAX_WIDTH.roundToPx()))
        val shapeWidth = if (animated) width.value.roundToInt() else content.width
        val shapeHeight = if (animated) height.value.roundToInt() else content.height
        layout(shapeWidth, shapeHeight) {
            content.place((shapeWidth - content.width) / 2, 0)
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
        Text(live.title, Modifier.padding(top = 10.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            Modifier.padding(top = 6.dp, bottom = 4.dp).clickable(onClick = onDismiss).padding(vertical = 6.dp, horizontal = 12.dp),
            color = MitauriColors.AccentSoft,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** What the bars and the cover move to. Read while drawing, so only drawing repeats each frame, not the layout. */
private class BarLevels {
    val shown = FloatArray(MusicSound.BANDS) { MusicSound.RESTING }
    var frame by mutableIntStateOf(0)
}

@Composable
private fun rememberBarLevels(playing: Boolean): BarLevels {
    val levels = remember { BarLevels() }
    LaunchedEffect(playing) {
        val sound = MusicSound()
        if (playing) {
            launch(Dispatchers.Default) {
                try {
                    sound.open()
                    while (isActive) {
                        sound.read()
                        delay(33)
                    }
                } finally {
                    sound.close()
                }
            }
        }
        var last = 0L
        while (isActive) {
            var settled = true
            androidx.compose.runtime.withFrameMillis { now ->
                val step = if (last == 0L) 16f else (now - last).toFloat()
                last = now
                for (band in levels.shown.indices) {
                    val goal = if (playing) sound.level(band) else MusicSound.RESTING
                    val shown = levels.shown[band]
                    // Quick up and slower down, as a real level meter moves, at any refresh rate.
                    val time = if (goal > shown) 35f else 150f
                    levels.shown[band] = shown + (goal - shown) * (1 - exp(-step / time))
                    if (kotlin.math.abs(goal - levels.shown[band]) > 0.005f) settled = false
                }
                levels.frame++
            }
            if (!playing && settled) break
        }
    }
    return levels
}

private fun Modifier.beat(levels: BarLevels, amount: Float) = graphicsLayer {
    levels.frame // Redraws this when the levels change.
    val scale = 1f + amount * ((levels.shown[0] - MusicSound.RESTING) / (1f - MusicSound.RESTING))
    scaleX = scale
    scaleY = scale
}

@Composable
private fun Cover(music: NowPlaying, levels: BarLevels, size: Dp, corner: Dp, amount: Float, modifier: Modifier = Modifier) {
    val look = modifier.beat(levels, amount).size(size).clip(RoundedCornerShape(corner))
    Crossfade(music.art, animationSpec = tween(250), label = "cover") { art ->
        if (art != null) {
            Image(art, null, look, contentScale = ContentScale.Crop)
        } else {
            Box(look.background(MitauriColors.Muted.copy(alpha = 0.35f)))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongTitle(title: String, modifier: Modifier, fontSize: Int, weight: FontWeight = FontWeight.Normal) {
    AnimatedContent(
        title,
        modifier,
        transitionSpec = {
            (fadeIn(tween(220, 60)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 }) togetherWith
                fadeOut(tween(90)) using SizeTransform(clip = true) { _, _ -> snap() }
        },
        label = "title",
    ) {
        Text(
            it,
            Modifier.basicMarquee(iterations = 2, initialDelayMillis = 1500, repeatDelayMillis = 1500, velocity = 28.dp),
            color = Color.White,
            fontSize = fontSize.sp,
            fontWeight = weight,
            maxLines = 1,
        )
    }
}

@Composable
private fun MusicCompact(music: NowPlaying, levels: BarLevels, camera: CameraHole) {
    AroundCamera(
        camera,
        {
            MusicBars(levels, music.accent, Modifier.padding(end = 6.dp).size(width = 19.dp, height = 16.dp))
            Cover(music, levels, 20.dp, 5.dp, 0.1f)
        },
        { SongTitle(music.title, Modifier.widthIn(max = MUSIC_TITLE_WIDTH), 14) },
    )
}

@Composable
private fun MusicExpanded(music: NowPlaying, levels: BarLevels, onAction: (MusicAction) -> Unit, onSeek: (Long) -> Unit) {
    Column(Modifier.width(PILL_MAX_WIDTH - 24.dp).padding(start = 8.dp, end = 8.dp, top = BELOW_CAMERA, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(music, levels, 56.dp, 12.dp, 0.04f, Modifier.clickable { onAction(MusicAction.OPEN) })
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                SongTitle(music.title, Modifier.fillMaxWidth(), 16, FontWeight.SemiBold)
                Text(music.artist, Modifier.padding(top = 2.dp), color = MitauriColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MusicBars(levels, music.accent, Modifier.size(width = 24.dp, height = 22.dp))
        }
        Progress(music, onSeek)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(40.dp))
            MusicButton(MusicAction.PREVIOUS, music.playing, 24.dp, onAction)
            MusicButton(MusicAction.TOGGLE, music.playing, 30.dp, onAction)
            MusicButton(MusicAction.NEXT, music.playing, 24.dp, onAction)
            MusicButton(MusicAction.HIDE, music.playing, 14.dp, onAction)
        }
    }
}

@Composable
private fun Progress(music: NowPlaying, onSeek: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var dragged by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(music.positionMillis, music.positionAt) { pending = null }
    LaunchedEffect(music.playing) {
        while (music.playing) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    if (music.durationMillis <= 0) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val duration = music.durationMillis
    val played = if (music.playing) music.positionMillis + (now - music.positionAt).coerceAtLeast(0) else music.positionMillis
    val fraction = dragged ?: pending ?: (played.toFloat() / duration).coerceIn(0f, 1f)
    val seek by rememberUpdatedState(onSeek)
    val thick by animateFloatAsState(if (dragged != null) 6f else 4f, spring(stiffness = Spring.StiffnessMedium), label = "thick")
    val colour = music.accent ?: Color.White
    Column(Modifier.padding(top = 10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        val point = (offset.x / size.width).coerceIn(0f, 1f)
                        pending = point
                        seek((point * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            dragged?.let {
                                pending = it
                                seek((it * duration).toLong())
                            }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    ) { change, _ ->
                        change.consume()
                        dragged = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val y = size.height / 2
            val stroke = thick.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.22f), Offset(stroke / 2, y), Offset(size.width - stroke / 2, y), stroke, StrokeCap.Round)
            drawLine(colour, Offset(stroke / 2, y), Offset(stroke / 2 + (size.width - stroke) * fraction, y), stroke, StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownMillis = (fraction * duration).toLong()
            val style = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum", color = MitauriColors.Muted)
            Text(songTime(shownMillis), style = style)
            Text("-" + songTime(duration - shownMillis), style = style)
        }
    }
}

@Composable
private fun MusicBars(levels: BarLevels, accent: Color?, modifier: Modifier) {
    val colour = accent ?: MitauriColors.Success
    Canvas(modifier) {
        levels.frame // Redraws this when the levels change.
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * levels.shown.size) / (levels.shown.size - 1)
        levels.shown.forEachIndexed { index, energy ->
            val height = (size.height * energy).coerceAtLeast(barWidth)
            drawRoundRect(
                colour,
                Offset(index * (barWidth + gap), (size.height - height) / 2),
                Size(barWidth, height),
                CornerRadius(barWidth / 2),
            )
        }
    }
}

@Composable
private fun MusicButton(action: MusicAction, playing: Boolean, iconSize: Dp, onAction: (MusicAction) -> Unit) {
    val touches = remember { MutableInteractionSource() }
    val pressed by touches.collectIsPressedAsState()
    val bounce by animateFloatAsState(
        if (pressed) 0.8f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "bounce",
    )
    Box(
        Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            }
            .clickable(touches, null) { onAction(action) },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(playing, animationSpec = tween(140), label = "icon") { nowPlaying ->
            MusicIcon(action, nowPlaying, iconSize)
        }
    }
}

@Composable
private fun MusicIcon(action: MusicAction, playing: Boolean, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
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
                drawRoundRect(Color.White, Offset(w * 0.2f, 0f), Size(w * 0.2f, h), CornerRadius(w * 0.05f))
                drawRoundRect(Color.White, Offset(w * 0.6f, 0f), Size(w * 0.2f, h), CornerRadius(w * 0.05f))
            } else {
                drawPath(triangle(w * 0.15f, w * 0.95f), Color.White)
            }
            MusicAction.NEXT -> {
                drawPath(triangle(0f, w - bar), Color.White)
                drawRect(Color.White, Offset(w - bar, 0f), Size(bar, h))
            }
            MusicAction.PREVIOUS -> {
                drawPath(triangle(w, bar), Color.White)
                drawRect(Color.White, Offset.Zero, Size(bar, h))
            }
            else -> {
                val soft = MitauriColors.Muted
                drawLine(soft, Offset.Zero, Offset(w, h), 2.dp.toPx(), StrokeCap.Round)
                drawLine(soft, Offset(w, 0f), Offset(0f, h), 2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Bars(mode: IslandMode) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    val voice by animateFloatAsState(Island.level, spring(dampingRatio = 0.8f, stiffness = 500f), label = "voice")
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
                IslandMode.LISTENING -> 0.25f + 0.75f * voice
                IslandMode.THINKING, IslandMode.SPEAKING -> 0.55f + 0.45f * sin(pulse + index * 1.1f)
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
