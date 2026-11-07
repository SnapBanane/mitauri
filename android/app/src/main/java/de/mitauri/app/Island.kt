package de.mitauri.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
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

enum class IslandMode {
    LISTENING, THINKING, SPEAKING, MESSAGE, LIVE, MUSIC, CALL, NAVIGATION, RUNNING, ALERT, IDLE;

    val isVoice get() = this == LISTENING || this == THINKING || this == SPEAKING

    /** Stays for as long as it's going on, and steps aside for anything more urgent. */
    val isBackground get() = this == LIVE || this == MUSIC || this == CALL || this == NAVIGATION || this == RUNNING
}

data class LiveCountdown(val title: String, val untilMillis: Long, val detail: String)

/** Where the camera sits, as seen from the top of the screen. A width of zero means it isn't on the top edge. */
data class CameraHole(val width: Dp, val centerY: Dp, val side: Dp = DEFAULT_SIDE) { // side: the room on each side of it
    val pillTop get() = (centerY - ROW_HEIGHT / 2 - PILL_EDGE).coerceAtLeast(PILL_MIN_TOP)

    companion object {
        val NONE = CameraHole(0.dp, 15.dp)
    }
}

private val PILL_MIN_TOP = 2.dp
private val PILL_EDGE = 6.dp
private val ROW_HEIGHT = 22.dp
private val PILL_MAX_WIDTH = 340.dp
private val DRAWING_HEIGHT = 320.dp
private val TEXT_FADE = 12.dp
private val VOICE_TEXT_EXTRA = 6.dp
private val PILL_MAX_RADIUS = 30.dp
private val CAMERA_MARGIN = 2.dp
private val BELOW_CAMERA = 40.dp // Expanded content starts here, clear of the camera.
private val SWIPE_DISTANCE = 18.dp
// Idle, the pill is only a little larger than the camera, about the height of the status bar's icons.
private val IDLE_WIDTH = 50.dp
private val IDLE_HEIGHT = 24.dp
private val EXPANDED_WIDTH = PILL_MAX_WIDTH - 24.dp
private val ROUND_BUTTON = 44.dp
private val LIVE_DOT_ROOM = 17.dp
private val MIN_LIVE_TITLE = 34.dp
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
 * "displayed over other apps" notice. It also finds where the status bar's icons sit, so the pill can end before them.
 * Only their positions are ever read, not their text or descriptions, and no other window is looked into.
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Island.statusBarChanged()
    override fun onInterrupt() = Unit

    /** The left and right edge of each visible item in the status bar, or null if the status bar can't be seen. */
    fun statusBarItems(): List<IntRange>? {
        val barHeight = resources.getDimensionPixelSize(
            resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 } ?: return null,
        )
        val box = Rect()
        val window = windows.firstOrNull {
            it.getBoundsInScreen(box)
            box.top == 0 && box.height() in 1..barHeight * 2 && it.root?.packageName == SYSTEM_UI
        } ?: return null
        window.getBoundsInScreen(box)
        val barBottom = box.bottom
        val barWidth = box.width()
        val items = mutableListOf<IntRange>()
        val waiting = ArrayDeque(listOfNotNull(window.root))
        var looked = 0
        while (waiting.isNotEmpty() && looked++ < MAX_NODES) {
            val node = waiting.removeLast()
            if (node.childCount == 0) {
                node.getBoundsInScreen(box)
                if (node.isVisibleToUser && box.width() in 1 until barWidth / 2 && box.top >= 0 && box.bottom <= barBottom) {
                    items += box.left..box.right
                }
            } else {
                for (index in 0 until node.childCount) node.getChild(index)?.let(waiting::add)
            }
        }
        return items
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_NODES = 200
        var instance: IslandService? = null
            private set
    }
}

/**
 * A pill around the camera that's there whenever the screen is on, as on the iPhone. With nothing going on it's a still
 * black shape that ignores touches, so the status bar can still be pulled down through it. Only an accessibility
 * overlay can sit above the status bar, so it stays away without that service.
 */
object Island {
    private const val EXIT_MILLIS = 480L
    private const val COLLAPSE_MILLIS = 8_000L
    private const val ALERT_MILLIS = 2_500L
    private const val UNLOCK_MILLIS = 1_000L
    private const val MEASURE_DELAY_MILLIS = 300L
    private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val WIFI_AP_DISABLED = 11
    private const val WIFI_AP_ENABLED = 13
    private val main = Handler(Looper.getMainLooper())
    private val remove = Runnable { removeView() }
    private val collapse = Runnable { expanded = false }
    private var view: View? = null
    private var viewParams: WindowManager.LayoutParams? = null
    private var touch: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
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
    private var call by mutableStateOf<OngoingActivity?>(null)
    private var route by mutableStateOf<OngoingActivity?>(null)
    private var running by mutableStateOf(emptyList<OngoingActivity>()) // Alarms, stopwatches, recordings and progress.
    private var locked by mutableStateOf(false)
    private var micInUse by mutableStateOf(false)
    private var camerasInUse by mutableStateOf(emptySet<String>())
    private var alert by mutableStateOf<IslandAlert?>(null)
    private var picked: IslandMode? = null // What a sideways swipe chose to see first.
    private var cameraCenter = 0
    private var ringer: Int? = null
    private var quiet = false
    private val alertOver = Runnable { if (mode == IslandMode.ALERT) hide() }
    private val measure = Runnable { measureRoom() }
    private var expanded by mutableStateOf(false)
    private var camera by mutableStateOf(CameraHole.NONE)

    /** While a screen shows this state itself, the pill stays away. */
    var embedded = false
        set(value) {
            field = value
            if (!value) hide()
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
        backgroundChanged()
    }

    /** A timer from another app repeats its notification, which shouldn't close the pill each time. */
    fun setCountdownIfNew(context: Context, countdown: LiveCountdown) {
        if (live?.untilMillis != countdown.untilMillis || live?.title != countdown.title) setLive(context, countdown)
    }

    fun setActivity(context: Context, activity: OngoingActivity) {
        attach(context)
        val ringing = activity.kind == ActivityKind.CALL && activity.incoming && call?.key != activity.key
        when (activity.kind) {
            ActivityKind.CALL -> call = activity
            ActivityKind.NAVIGATION -> route = activity
            else -> running = running.filter { it.key != activity.key } + activity
        }
        backgroundChanged()
        if (ringing && mode == IslandMode.CALL) changeExpanded(true) // As on the iPhone, its buttons come right away.
    }

    fun clearActivity(key: String) {
        if (call?.key == key) call = null
        if (route?.key == key) route = null
        running = running.filter { it.key != key }
        val gone = (mode == IslandMode.CALL && call == null) || (mode == IslandMode.NAVIGATION && route == null) ||
            (mode == IslandMode.RUNNING && running.isEmpty())
        if (gone) hide()
    }

    /** Says something for a moment, as the iPhone does when it's plugged in. Never over voice or a message. */
    fun flash(newAlert: IslandAlert, millis: Long = ALERT_MILLIS) {
        val context = app ?: return
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val busy = shown && (mode.isVoice || mode == IslandMode.MESSAGE)
        val awake = context.getSystemService(PowerManager::class.java).isInteractive
        // A headphone's name is the only one of these that says anything personal, so it waits for an unlock.
        val private = newAlert.kind == AlertKind.HEADPHONES && isLocked(context)
        if (embedded || !awake || landscape || busy || private) return
        alert = newAlert
        Log.i("Mitauri", "Island alert: ${newAlert.kind}") // The kind only, as a headphone's name is personal.
        show(context, IslandMode.ALERT, "")
        main.removeCallbacks(alertOver)
        main.postDelayed(alertOver, millis)
    }

    private fun backgroundChanged() {
        if (!embedded && (!shown || mode.isBackground || mode == IslandMode.IDLE)) showBackground()
    }

    private fun onSystemEvent(intent: Intent, sticky: Boolean) {
        val context = app ?: return
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> Music.recheck()
            Intent.ACTION_POWER_CONNECTED -> flash(IslandAlert(AlertKind.CHARGING, batteryText(context)))
            Intent.ACTION_BATTERY_LOW -> flash(IslandAlert(AlertKind.LOW_BATTERY, batteryText(context)))
            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                val now = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                val changed = ringer != null && ringer != now
                ringer = now
                if (changed && !sticky) {
                    flash(
                        when (now) {
                            AudioManager.RINGER_MODE_SILENT -> IslandAlert(AlertKind.SILENT, "Silent")
                            AudioManager.RINGER_MODE_VIBRATE -> IslandAlert(AlertKind.VIBRATE, "Vibrate")
                            else -> IslandAlert(AlertKind.RING, "Ring")
                        },
                    )
                }
            }
            Intent.ACTION_USER_PRESENT -> flash(IslandAlert(AlertKind.UNLOCKED, "Unlocked"), UNLOCK_MILLIS)
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                // MIUI's silent switch is Do Not Disturb underneath, so both read as silent. It can say so twice.
                val on = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
                    NotificationManager.INTERRUPTION_FILTER_ALL
                if (on != quiet) flash(if (on) IslandAlert(AlertKind.SILENT, "Silent") else IslandAlert(AlertKind.RING, "Ring"))
                quiet = on
            }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val on = context.getSystemService(PowerManager::class.java).isPowerSaveMode
                flash(IslandAlert(AlertKind.SAVER, if (on) "Saver" else "Saver off"))
            }
            WIFI_AP_STATE_CHANGED -> if (!sticky) {
                when (intent.getIntExtra("wifi_state", 0)) {
                    WIFI_AP_ENABLED -> flash(IslandAlert(AlertKind.HOTSPOT, "Hotspot"))
                    WIFI_AP_DISABLED -> flash(IslandAlert(AlertKind.HOTSPOT, "Hotspot off"))
                }
            }
        }
        sync()
    }

    private fun batteryText(context: Context) =
        "${context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"

    /** The status bar changed. Waits for it to settle, and doesn't look at all while there's no pill to fit. */
    fun statusBarChanged() {
        if (view == null) return
        main.removeCallbacks(measure)
        main.postDelayed(measure, MEASURE_DELAY_MILLIS)
    }

    private fun measureRoom() {
        val service = IslandService.instance ?: return
        if (view == null) return
        val items = try {
            service.statusBarItems()
        } catch (error: RuntimeException) {
            Log.w("Mitauri", "The status bar couldn't be measured: ${error.message}")
            null
        } ?: return
        val clear = clearSpace(cameraCenter, items) ?: return
        val density = service.resources.displayMetrics.density
        val side = sideFor((clear / density).dp, camera.width, CAMERA_MARGIN)
        Log.i("Mitauri", "Island room: ${items.size} items, nearest ${clear}px, each side ${side.value}dp")
        if (kotlin.math.abs((side - camera.side).value) >= 2) camera = camera.copy(side = side)
    }

    /** The song stays in the pill while nothing more urgent is there. */
    fun setMusic(context: Context, playing: NowPlaying?) {
        attach(context)
        music = playing
        if (playing == null) {
            if (mode == IslandMode.MUSIC) hide()
        } else {
            backgroundChanged()
        }
    }

    fun clearLive() {
        main.removeCallbacks(expire)
        live = null
        changeExpanded(false)
        if (mode == IslandMode.LIVE) hide()
    }

    private fun backgrounds() = listOfNotNull(
        IslandMode.CALL.takeIf { call != null },
        IslandMode.NAVIGATION.takeIf { route != null },
        IslandMode.RUNNING.takeIf { running.isNotEmpty() },
        IslandMode.LIVE.takeIf { live != null },
        IslandMode.MUSIC.takeIf { music != null },
    )

    /** Puts back what was there before voice or a message took over. Returns false if nothing was. */
    private fun showBackground(): Boolean {
        val context = app ?: return false
        val going = backgrounds()
        val next = picked?.takeIf { it in going } ?: going.firstOrNull() ?: return false
        show(context, next, "")
        return true
    }

    val isOnScreen get() = view != null

    /** For a message's timer: by then voice may have taken over, and that must stay. */
    fun hideMessage() {
        if (mode == IslandMode.MESSAGE) hide()
    }

    fun hide() {
        if (!embedded) {
            if (showBackground()) return
            val context = app
            if (context != null && IslandService.instance != null) {
                show(context, IslandMode.IDLE, "")
                return
            }
        }
        shown = false
        changeExpanded(false)
        main.removeCallbacks(remove)
        main.postDelayed(remove, EXIT_MILLIS)
    }

    /** The accessibility service came or went, so the pill moves to the window type that's now possible. */
    fun hostChanged(context: Context) {
        attach(context)
        removeView()
        if (!shown || mode == IslandMode.IDLE) hide() else sync()
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
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(WIFI_AP_STATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onSystemEvent(intent, isInitialStickyBroadcast)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            owner.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            owner.registerReceiver(receiver, filter)
        }
        quiet = owner.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        watchRecording(owner)
    }

    /** Only whether the microphone or a camera is in use, for the iPhone's small dot. Nothing is recorded or read. */
    private fun watchRecording(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        micInUse = audio.activeRecordingConfigurations.isNotEmpty()
        audio.registerAudioRecordingCallback(
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    micInUse = configs.isNotEmpty()
                }
            },
            main,
        )
        context.getSystemService(CameraManager::class.java).registerAvailabilityCallback(
            object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(id: String) {
                    camerasInUse = camerasInUse - id
                }

                override fun onCameraUnavailable(id: String) {
                    camerasInUse = camerasInUse + id
                }
            },
            main,
        )
    }

    private fun isLocked(context: Context) = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Never on a dark screen, and nothing idle or musical gets in the way of a sideways video or game. */
    private fun allowedNow(context: Context): Boolean {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return !embedded &&
            context.getSystemService(PowerManager::class.java).isInteractive &&
            !(landscape && (mode == IslandMode.MUSIC || mode == IslandMode.IDLE))
    }

    /** On the lock screen the pill stays empty, so nothing about songs, calls or countdowns shows there. */
    private val showing get() = if (locked && mode != IslandMode.ALERT) IslandMode.IDLE else mode

    private fun sync() {
        val context = app ?: return
        locked = isLocked(context)
        when {
            !allowedNow(context) -> removeView()
            shown && view == null -> addView()
            view != null -> {
                val x = locateCamera(context)
                val manager = windowManager() ?: return
                viewParams?.let {
                    it.x = x
                    manager.updateViewLayout(view, it)
                }
                touchParams?.let {
                    it.x = x
                    setTouchable(it)
                    manager.updateViewLayout(touch, it)
                }
            }
        }
    }

    private fun setTouchable(params: WindowManager.LayoutParams) {
        val off = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (showing == IslandMode.IDLE) params.flags or off else params.flags and off.inv()
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
                        showing, text, shown, live, expanded && showing != IslandMode.IDLE,
                        music = music,
                        camera = camera,
                        activity = when (mode) {
                            IslandMode.CALL -> call
                            IslandMode.NAVIGATION -> route
                            else -> running.lastOrNull()
                        },
                        alert = alert,
                        indicator = when {
                            camerasInUse.isNotEmpty() -> MitauriColors.Success
                            micInUse -> MitauriColors.Warning
                            else -> null
                        },
                        animated = true,
                        onBounds = ::fitWindow,
                        onExpired = ::clearLive,
                        onDismiss = {
                            clearLive()
                            Mitauri.dismissLive()
                        },
                        onMusic = {
                            if (it == MusicAction.OPEN) changeExpanded(false) else keepOpen()
                            Music.act(it)
                        },
                        onSeek = {
                            keepOpen()
                            Music.seek(it)
                        },
                        onOpen = { send(it.open) },
                        onAction = { send(it.intent) },
                        onSwipe = ::onSwipe,
                        onSwipeSideways = ::switchBackground,
                    ) { onTap(host) }
                }
            }
        }
        val root = FrameLayout(host).apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            addView(composeView)
        }
        val touchRoot = object : FrameLayout(host) {
            // Checked here, before Compose can claim the event for itself.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (mode.isBackground || mode == IslandMode.ALERT) {
                        changeExpanded(false) // A tap elsewhere never stops a countdown or the music.
                    } else if (!DoorOpener.cancel() && !ReplySender.cancel() && mode != IslandMode.LISTENING) {
                        VoiceMode.stop()
                    } else if (mode == IslandMode.MESSAGE) {
                        hide()
                    }
                    return true
                }
                val here = IntArray(2).also(::getLocationOnScreen)
                val there = IntArray(2).also(root::getLocationOnScreen)
                val moved = MotionEvent.obtain(event)
                moved.offsetLocation((here[0] - there[0]).toFloat(), (here[1] - there[1]).toFloat())
                root.dispatchTouchEvent(moved)
                moved.recycle()
                return true
            }
        }
        val density = host.resources.displayMetrics.density
        val type = if (overService) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val shared = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val offset = locateCamera(host)
        fun layout(width: Int, height: Int, flags: Int) =
            WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                x = offset
                y = 0
            }
        // The drawing window never changes size, so nothing can jump, and it lets every touch through. A second
        // empty window takes touches only over the pill and hands them on, so it can resize without being seen.
        val drawing = layout(
            ((PILL_MAX_WIDTH + 28.dp).value * density).toInt(),
            (DRAWING_HEIGHT.value * density).toInt(),
            shared or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        val start = (ROW_HEIGHT.value * 2 * density).toInt()
        val touching = layout(start, start, shared or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        setTouchable(touching)
        // Android blocks touches through another app's solid overlay, but not through a slightly see-through one.
        if (!overService) drawing.alpha = 0.8f
        val manager = windowManager() ?: return
        try {
            manager.addView(root, drawing)
            manager.addView(touchRoot, touching)
        } catch (error: RuntimeException) {
            // Permission just taken away or the service went. No island is better than a crash.
            Log.w("Mitauri", "The island couldn't be shown: ${error.message}")
            if (root.isAttachedToWindow) manager.removeViewImmediate(root)
            overlayOwner.destroy()
            return
        }
        view = root
        viewParams = drawing
        touch = touchRoot
        touchParams = touching
        owner = overlayOwner
        statusBarChanged()
    }

    /** The touch window follows the pill. It's a little larger during a movement, and exact once settled. */
    private fun fitWindow(size: IntSize) {
        val current = touchParams ?: return
        val attached = touch ?: return
        val density = attached.resources.displayMetrics.density
        val width = size.width + size.width % 2
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
        val screenWidth = Point().also { @Suppress("DEPRECATION") display?.getRealSize(it) }.x
        if (hole == null || hole.isEmpty) {
            camera = CameraHole.NONE.copy(side = camera.side)
            cameraCenter = screenWidth / 2
            return 0
        }
        camera = CameraHole((hole.width() / density).dp, ((hole.centerY() + HOLE_BELOW_BOX_PIXELS) / density).dp, camera.side)
        cameraCenter = hole.centerX()
        return hole.centerX() - screenWidth / 2
    }

    private fun removeView() {
        val current = view ?: return
        val manager = windowManager()
        touch?.let { manager?.removeViewImmediate(it) }
        manager?.removeViewImmediate(current)
        owner?.destroy()
        view = null
        viewParams = null
        touch = null
        touchParams = null
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

    private fun canExpand() = mode.isBackground

    private fun switchBackground(forward: Boolean) {
        val going = backgrounds()
        if (!mode.isBackground || expanded || going.size < 2) return
        val here = going.indexOf(mode).coerceAtLeast(0)
        picked = going[(here + if (forward) 1 else going.size - 1) % going.size]
        view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        showBackground()
    }

    private fun send(intent: PendingIntent?) {
        changeExpanded(false)
        try {
            intent?.send()
        } catch (_: PendingIntent.CanceledException) {
            // The call or route ended in the same moment.
        }
    }

    private fun onSwipe(down: Boolean) {
        if (canExpand() && down != expanded) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            changeExpanded(down)
        }
    }

    private fun onTap(context: Context) {
        if (mode == IslandMode.ALERT) {
            hide()
            return
        }
        if (canExpand()) {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            changeExpanded(!expanded)
            return
        }
        if (DoorOpener.cancel() || ReplySender.cancel()) {
            hide()
            return
        }
        VoiceMode.stop()
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private val GROW = spring<Float>(dampingRatio = 0.8f, stiffness = 320f, visibilityThreshold = 1f)
private val SHRINK = spring<Float>(dampingRatio = 0.95f, stiffness = 280f, visibilityThreshold = 1f) // No bounce on closing.

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
    activity: OngoingActivity? = null,
    alert: IslandAlert? = null,
    indicator: Color? = null,
    onOpen: (OngoingActivity) -> Unit = {},
    onAction: (ActivityAction) -> Unit = {},
    onSwipe: (Boolean) -> Unit = {},
    onSwipeSideways: (Boolean) -> Unit = {},
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
                // One spring for both, so the shape doesn't change its proportions on the way.
                val movement = if (goalHeight < height.value) SHRINK else GROW
                launch { width.animateTo(goalWidth, movement) }
                launch { height.animateTo(goalHeight, movement) }
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
    val swipeSideways by rememberUpdatedState(onSwipeSideways)
    val canHold by rememberUpdatedState(mode.isBackground && !expanded)
    val swipeDistance = with(density) { SWIPE_DISTANCE.toPx() }
    val maxRadius = with(density) { PILL_MAX_RADIUS.toPx() }
    val sound = rememberBarLevels(mode == IslandMode.MUSIC && music?.playing == true)
    // The window puts the pill's top where a full-height row is centred on the camera. The smaller idle pill moves
    // down by half the difference, so it's centred on the camera too.
    val idleDrop = with(density) { ((ROW_HEIGHT + PILL_EDGE * 2 - IDLE_HEIGHT) / 2).toPx() }
    val drop by animateFloatAsState(
        if (mode == IslandMode.IDLE && shown) idleDrop else 0f,
        spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "drop",
    )

    Layout(
        content = {
            Box(Modifier.onSizeChanged { target = it }) {
                AnimatedContent(
                    // Voice's steps share one scene, so moving between them changes the pill's insides, not the pill.
                    targetState = (if (mode.isVoice) IslandMode.LISTENING else mode) to expanded,
                    transitionSpec = {
                        (fadeIn(tween(240, 90)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 380f), initialScale = 0.9f)) togetherWith
                            fadeOut(tween(160)) using SizeTransform(clip = false) { _, _ -> snap() }
                    },
                    contentAlignment = Alignment.TopCenter,
                    label = "content",
                ) { (shownMode, open) ->
                    Column(
                        if (shownMode == IslandMode.IDLE) Modifier else Modifier.padding(horizontal = 12.dp, vertical = PILL_EDGE),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when {
                            shownMode == IslandMode.LIVE && live != null -> LiveContent(live, open, camera, clock, onExpired, onDismiss)
                            shownMode == IslandMode.MUSIC && music != null ->
                                if (open) MusicExpanded(music, sound, onMusic, onSeek) else MusicCompact(music, sound, camera)
                            shownMode == IslandMode.IDLE -> Spacer(Modifier.size(IDLE_WIDTH, IDLE_HEIGHT))
                            (shownMode == IslandMode.CALL || shownMode == IslandMode.NAVIGATION || shownMode == IslandMode.RUNNING) && activity != null ->
                                ActivityContent(activity, open, camera, clock, onOpen, onAction)
                            shownMode == IslandMode.ALERT && alert != null -> AlertContent(alert, camera)
                            shownMode == IslandMode.MESSAGE -> MessageContent(text, camera)
                            else -> VoiceContent(mode, text, camera)
                        }
                    }
                }
            }
        },
        modifier = Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0f)
                translationY = drop
                scaleX = press
                scaleY = press
                alpha = fade.value
                shape = RoundedCornerShape(min(size.height / 2, maxRadius))
                clip = true
            }
            .background(Color.Black)
            .drawWithContent {
                drawContent()
                val dot = indicator ?: return@drawWithContent
                val radius = 2.5.dp.toPx()
                val middle = min((ROW_HEIGHT / 2 + PILL_EDGE).toPx(), size.height / 2) // The small idle pill's middle.
                drawCircle(dot, radius, Offset(size.width - min(9.dp.toPx(), middle), middle))
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { tap() },
                    onLongPress = { if (canHold) tap() }, // As on the iPhone.
                )
            }
            .pointerInput(Unit) {
                var moved = 0f
                detectHorizontalDragGestures(
                    onDragStart = { moved = 0f },
                    onDragEnd = { if (kotlin.math.abs(moved) > swipeDistance) swipeSideways(moved < 0) },
                ) { change, amount ->
                    change.consume()
                    moved += amount
                }
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

/**
 * Two sides of equal width with the camera's width free between them, so the camera stays in the middle. As on the
 * iPhone, the start sits at the pill's left end and the end at its right end, never in a gap beside the camera.
 */
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
            left.place(0, (height - left.height) / 2)
            right.place(side * 2 + gap - right.width, (height - right.height) / 2)
        }
    }
}

@Composable
private fun Label(text: String, color: Color = Color.White, modifier: Modifier = Modifier) = Text(
    text,
    modifier,
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
    if (expanded) {
        ExpandedCard(
            leading = { Canvas(Modifier.size(12.dp)) { drawCircle(colour) } },
            title = live.title,
            status = { StatusLine(countdownText(left), colour, live.detail) },
            buttons = { Chip("Dismiss", onDismiss, primary = true) },
        )
        return
    }
    AroundCamera(
        camera,
        {
            Canvas(Modifier.padding(start = 2.dp, end = 8.dp).size(9.dp)) { drawCircle(colour) }
            Label(live.title, modifier = Modifier.widthIn(max = (camera.side - LIVE_DOT_ROOM).coerceAtLeast(MIN_LIVE_TITLE)))
        },
        { Figure(countdownText(left), colour) },
    )
}

/** A number in the pill, like a countdown, a call's time or a percentage. Its digits keep their width as it counts. */
@Composable
private fun Figure(text: String, colour: Color, modifier: Modifier = Modifier) = Text(
    text,
    modifier,
    color = colour,
    maxLines = 1,
    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
)

/**
 * The open pill, laid out like the open song: below the camera, the thing's icon on the left with what it is beside it,
 * any round buttons on the right, and the rest of its buttons underneath. Tapping the words opens its app.
 */
@Composable
private fun ExpandedCard(
    leading: @Composable () -> Unit,
    title: String,
    status: @Composable () -> Unit,
    onOpen: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    buttons: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.width(EXPANDED_WIDTH).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = BELOW_CAMERA), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).then(
                    if (onOpen == null) Modifier else Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onOpen),
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) { leading() }
                Column(Modifier.padding(start = 12.dp, end = 8.dp)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    status()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
        if (buttons != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { buttons() }
        }
    }
}

/** The line under an open pill's title: how far along it is, in its colour, then any detail from the app. */
@Composable
private fun StatusLine(state: String, colour: Color, detail: String = "") {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.isNotEmpty()) {
            Text(state, color = colour, maxLines = 1, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"))
        }
        if (detail.isNotBlank()) {
            Text(
                if (state.isEmpty()) detail else " · $detail",
                color = MitauriColors.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Chip(title: String, onClick: () -> Unit, primary: Boolean = false) = Text(
    title,
    Modifier
        .clip(RoundedCornerShape(50))
        .background(if (primary) MitauriColors.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f))
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp),
    color = if (primary) MitauriColors.AccentSoft else Color.White,
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    maxLines = 1,
)

/** Green to pick up and red to end, as on every phone. The receiver tips over for ending. */
@Composable
private fun CallButton(role: ActionRole, title: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(ROUND_BUTTON)
            .clip(RoundedCornerShape(50))
            .background(if (role == ActionRole.ANSWER) MitauriColors.Success else MitauriColors.Danger)
            .clickable(onClickLabel = title, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_call),
            title,
            Modifier.size(22.dp).graphicsLayer { rotationZ = if (role == ActionRole.END) 135f else 0f },
            colorFilter = ColorFilter.tint(Color.White),
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

/** The text melts away at the edges instead of being cut off. The right edge only does so if there's more to read. */
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
        var room by remember { mutableIntStateOf(0) }
        var textWidth by remember { mutableIntStateOf(0) }
        Text(
            it,
            Modifier
                .onSizeChanged { size -> room = size.width }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = TEXT_FADE.toPx()
                    val longer = textWidth > room
                    val end = if (longer) (size.width - fade).coerceAtLeast(fade) / size.width else 1f
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            (fade / 2 / size.width) to Color.Black,
                            end to Color.Black,
                            1f to if (longer) Color.Transparent else Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .basicMarquee(iterations = 2, initialDelayMillis = 1500, repeatDelayMillis = 1500, velocity = 28.dp)
                .padding(start = TEXT_FADE / 2),
            color = Color.White,
            fontSize = fontSize.sp,
            fontWeight = weight,
            maxLines = 1,
            onTextLayout = { layout -> textWidth = layout.size.width },
        )
    }
}

@Composable
private fun MusicCompact(music: NowPlaying, levels: BarLevels, camera: CameraHole) {
    AroundCamera(
        camera,
        {
            Row(
                Modifier.width(camera.side),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sits in the rounded end of the pill, the same distance from its edge all round.
                Cover(music, levels, ROW_HEIGHT, ROW_HEIGHT / 2, 0.08f, Modifier.offset(x = -PILL_EDGE))
                MusicBars(levels, music.accent, Modifier.padding(end = 2.dp).size(width = 19.dp, height = 16.dp))
            }
        },
        { SongTitle(music.title, Modifier.widthIn(max = camera.side), 14) },
    )
}

@Composable
private fun MusicExpanded(music: NowPlaying, levels: BarLevels, onAction: (MusicAction) -> Unit, onSeek: (Long) -> Unit) {
    Column(Modifier.width(PILL_MAX_WIDTH - 24.dp).padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
        // The whole top, up to the camera, opens the app that's playing.
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { onAction(MusicAction.OPEN) }.padding(top = BELOW_CAMERA),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(music, levels, 56.dp, 12.dp, 0.04f)
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

/** A call shows its time and a route its next step. Opening one goes to the app that's running it. */
@Composable
private fun ActivityContent(
    activity: OngoingActivity,
    expanded: Boolean,
    camera: CameraHole,
    clock: () -> Long,
    onOpen: (OngoingActivity) -> Unit,
    onAction: (ActivityAction) -> Unit,
) {
    val isCall = activity.kind == ActivityKind.CALL
    val colour = when (activity.kind) {
        ActivityKind.CALL -> MitauriColors.Success
        ActivityKind.ELAPSED -> MitauriColors.Danger
        ActivityKind.ALARM -> MitauriColors.Warning
        else -> MitauriColors.AccentSoft
    }
    val progress = "${((activity.progress ?: 0f) * 100).roundToInt()}%"
    if (expanded) {
        // A call's answer and end buttons are round and on the right. Speaker, mute and the like go underneath.
        val (round, others) = activity.actions.partition { isCall && actionRole(it.title) != ActionRole.OTHER }
        val buttons: @Composable () -> Unit = {
            others.forEach { action -> Chip(action.title, { onAction(action) }) }
            if (!isCall) Chip("Open", { onOpen(activity) }, primary = true)
        }
        ExpandedCard(
            leading = { ActivityIcon(activity, colour, 20.dp) },
            title = activity.title,
            status = {
                when {
                    isCall || activity.kind == ActivityKind.ELAPSED -> StatusLine(elapsed(activity, clock), colour, activity.detail)
                    activity.kind == ActivityKind.PROGRESS -> StatusLine(progress, colour, activity.detail)
                    else -> StatusLine("", colour, activity.detail)
                }
            },
            onOpen = { onOpen(activity) },
            trailing = { round.forEach { action -> CallButton(actionRole(action.title), action.title) { onAction(action) } } },
            buttons = buttons.takeIf { others.isNotEmpty() || !isCall },
        )
        return
    }
    AroundCamera(
        camera,
        { ActivityIcon(activity, colour, 16.dp) },
        {
            when {
                isCall || activity.kind == ActivityKind.ELAPSED -> Figure(elapsed(activity, clock), colour, Modifier.widthIn(max = camera.side + VOICE_TEXT_EXTRA))
                activity.kind == ActivityKind.PROGRESS -> Figure(progress, colour)
                else -> SongTitle(activity.title, Modifier.widthIn(max = camera.side), 14)
            }
        },
    )
}

@Composable
private fun ActivityIcon(activity: OngoingActivity, colour: Color, iconSize: Dp) {
    when (activity.kind) {
        ActivityKind.CALL -> TintedIcon(R.drawable.ic_call, colour, iconSize)
        ActivityKind.ALARM -> TintedIcon(R.drawable.ic_bell, colour, iconSize)
        ActivityKind.ELAPSED -> Canvas(Modifier.padding(start = 2.dp).size(iconSize * 0.6f)) { drawCircle(colour) }
        ActivityKind.PROGRESS -> Canvas(Modifier.size(iconSize)) {
            val stroke = Stroke(size.width * 0.16f, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.22f), style = stroke)
            drawArc(colour, -90f, 360f * (activity.progress ?: 0f), false, style = stroke)
        }
        ActivityKind.NAVIGATION -> Canvas(Modifier.size(iconSize)) {
            val w = size.width
            val h = size.height
            drawPath(
                Path().apply {
                    moveTo(w / 2, 0f)
                    lineTo(w, h)
                    lineTo(w / 2, h * 0.72f)
                    lineTo(0f, h)
                    close()
                },
                colour,
            )
        }
    }
}

/**
 * A call's time once answered, or which way it's still going: ringing in, or this phone calling out. Updates once a
 * second at most, like the countdown.
 */
@Composable
private fun elapsed(activity: OngoingActivity, clock: () -> Long): String {
    val started = activity.startedAt ?: return if (activity.incoming) "Incoming" else "Calling"
    var now by remember(started) { mutableLongStateOf(clock()) }
    LaunchedEffect(started) {
        while (isActive) {
            delay(1000 - (now - started).mod(1000L))
            now = clock()
        }
    }
    return songTime(now - started)
}

@Composable
private fun TintedIcon(icon: Int, colour: Color, size: Dp = 16.dp) = Image(
    painterResource(icon),
    null,
    Modifier.size(size),
    colorFilter = ColorFilter.tint(colour),
)

@Composable
private fun AlertContent(alert: IslandAlert, camera: CameraHole) {
    val colour = when (alert.kind) {
        AlertKind.CHARGING, AlertKind.UNLOCKED -> MitauriColors.Success
        AlertKind.LOW_BATTERY -> MitauriColors.Danger
        AlertKind.SILENT, AlertKind.VIBRATE, AlertKind.SAVER -> MitauriColors.Warning
        AlertKind.RING, AlertKind.HEADPHONES -> MitauriColors.Ink
        AlertKind.HOTSPOT -> MitauriColors.Success
    }
    AroundCamera(
        camera,
        {
            when (alert.kind) {
                AlertKind.SILENT, AlertKind.VIBRATE, AlertKind.RING -> TintedIcon(R.drawable.ic_bell, colour)
                AlertKind.UNLOCKED -> TintedIcon(R.drawable.ic_lock, colour)
                AlertKind.HOTSPOT -> Canvas(Modifier.size(16.dp)) {
                    val middle = Offset(size.width / 2, size.height / 2)
                    drawCircle(colour, 2.dp.toPx(), middle)
                    drawCircle(colour, size.width * 0.28f, middle, style = Stroke(1.5.dp.toPx()))
                    drawCircle(colour, size.width * 0.48f, middle, style = Stroke(1.5.dp.toPx()))
                }
                AlertKind.CHARGING, AlertKind.SAVER -> Canvas(Modifier.size(16.dp)) {
                    val w = size.width
                    val h = size.height
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.6f, 0f)
                            lineTo(w * 0.15f, h * 0.58f)
                            lineTo(w * 0.47f, h * 0.58f)
                            lineTo(w * 0.4f, h)
                            lineTo(w * 0.85f, h * 0.42f)
                            lineTo(w * 0.53f, h * 0.42f)
                            close()
                        },
                        colour,
                    )
                }
                AlertKind.LOW_BATTERY -> Canvas(Modifier.size(width = 22.dp, height = 11.dp)) {
                    val edge = 1.5.dp.toPx()
                    drawRoundRect(colour, style = Stroke(edge), cornerRadius = CornerRadius(3.dp.toPx()))
                    drawRect(colour, Offset(edge * 2, edge * 2), Size(size.width * 0.14f, size.height - edge * 4))
                }
                AlertKind.HEADPHONES -> Canvas(Modifier.size(16.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = Stroke(2.dp.toPx())
                    drawArc(colour, 180f, 180f, false, Offset(w * 0.1f, h * 0.08f), Size(w * 0.8f, h * 0.9f), style = stroke)
                    drawRoundRect(colour, Offset(0f, h * 0.55f), Size(w * 0.24f, h * 0.42f), CornerRadius(w * 0.08f))
                    drawRoundRect(colour, Offset(w * 0.76f, h * 0.55f), Size(w * 0.24f, h * 0.42f), CornerRadius(w * 0.08f))
                }
            }
        },
        // Only there for a moment, so it may reach past the status bar's icons rather than cut its words short.
        { Label(alert.text, colour, Modifier.widthIn(max = MAX_SIDE)) },
    )
}

/** Mitauri's face where a song has its cover, then the bars beside the camera, so every state has the same shape. */
@Composable
private fun VoiceLeading(mode: IslandMode, side: Dp) {
    Row(
        Modifier.width(side),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(R.drawable.logo_mark),
            null,
            Modifier.offset(x = -PILL_EDGE).size(ROW_HEIGHT),
        )
        Bars(mode)
    }
}

@Composable
private fun VoiceContent(mode: IslandMode, text: String, camera: CameraHole) {
    // While fading out into the next state, this keeps showing what it was showing.
    var shownMode by remember { mutableStateOf(IslandMode.LISTENING) }
    var shownText by remember { mutableStateOf(text) }
    if (mode.isVoice) {
        shownMode = mode
        shownText = text
    }
    AroundCamera(camera, { VoiceLeading(shownMode, camera.side) }, {
        Crossfade(shownText, Modifier.widthIn(max = camera.side + VOICE_TEXT_EXTRA), animationSpec = tween(180), label = "text") { Label(it) }
    })
}

@Composable
private fun MessageContent(text: String, camera: CameraHole) {
    var shownText by remember { mutableStateOf(text) }
    if (text.isNotEmpty()) shownText = text
    AroundCamera(camera, { VoiceLeading(IslandMode.MESSAGE, camera.side) }, { Label("Mitauri", MitauriColors.Muted) })
    Text(
        shownText,
        Modifier.padding(top = 8.dp, bottom = 6.dp),
        color = Color.White,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Listening follows your voice, thinking breathes and speaking moves more. Each change blends into the next. */
@Composable
private fun Bars(mode: IslandMode) {
    val phase by rememberInfiniteTransition(label = "phase").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val blend = spring<Float>(dampingRatio = 1f, stiffness = 200f)
    val voice by animateFloatAsState(Island.level, spring(dampingRatio = 0.85f, stiffness = 600f), label = "voice")
    val hearing by animateFloatAsState(if (mode == IslandMode.LISTENING) 1f else 0f, blend, label = "hearing")
    val wave by animateFloatAsState(
        when (mode) {
            IslandMode.THINKING -> 0.3f
            IslandMode.SPEAKING -> 1f
            else -> 0f
        },
        blend,
        label = "wave",
    )
    val color by animateColorAsState(
        when (mode) {
            IslandMode.LISTENING -> MitauriColors.Success
            IslandMode.THINKING -> MitauriColors.Accent
            IslandMode.SPEAKING -> MitauriColors.AccentSoft
            else -> MitauriColors.Warning
        },
        tween(220),
        label = "colour",
    )
    Canvas(Modifier.padding(end = 2.dp).size(width = 19.dp, height = 16.dp)) {
        val shapes = listOf(0.6f, 1f, 0.75f, 0.9f)
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * shapes.size) / (shapes.size - 1)
        shapes.forEachIndexed { index, shape ->
            val resting = 0.5f + 0.42f * wave * sin(phase + index * 1.1f)
            val energy = resting + (0.25f + 0.75f * voice - resting) * hearing
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
