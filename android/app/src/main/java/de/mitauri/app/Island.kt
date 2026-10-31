package de.mitauri.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

enum class IslandMode { LISTENING, THINKING, SPEAKING, MESSAGE }

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

    /** While a screen shows this state itself, the pill stays away. */
    var embedded = false
    private var shown by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)

    fun show(context: Context, newMode: IslandMode, newText: String) {
        app = context.applicationContext
        main.removeCallbacks(remove)
        update(newMode, newText)
        if (view == null && !embedded) addView()
        shown = true
    }

    fun update(newMode: IslandMode, newText: String) {
        mode = newMode
        text = newText
    }

    fun hide() {
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
            setContent { Pill(mode, text, shown) { onTap(context) } }
        }
        val root = object : FrameLayout(context) {
            // Checked here, before Compose can claim the event for itself.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (!DoorOpener.cancel() && mode != IslandMode.LISTENING) VoiceMode.stop()
                    else if (mode == IslandMode.MESSAGE) hide()
                    return true
                }
                return super.dispatchTouchEvent(event)
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
            y = (6 * context.resources.displayMetrics.density).toInt()
        }
        context.getSystemService(WindowManager::class.java).addView(root, params)
        view = root
        owner = overlayOwner
    }

    private fun removeView() {
        val current = view ?: return
        app?.getSystemService(WindowManager::class.java)?.removeView(current)
        owner?.destroy()
        view = null
        owner = null
    }

    private fun onTap(context: Context) {
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
private fun Pill(mode: IslandMode, text: String, shown: Boolean, onTap: () -> Unit) {
    val scale by animateFloatAsState(
        if (shown) 1f else 0.6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "alpha")
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(Color.Black, RoundedCornerShape(26.dp))
            .clickable(onClick = onTap)
            .animateContentSize(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            .widthIn(min = 132.dp, max = 300.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Bars(mode)
        AnimatedContent(text, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "text") {
            Text(
                it,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = if (mode == IslandMode.MESSAGE) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        IslandMode.MESSAGE -> MitauriColors.Warning
    }
    Canvas(Modifier.size(width = 26.dp, height = 22.dp)) {
        val shapes = listOf(0.55f, 1f, 0.7f, 0.9f)
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * shapes.size) / (shapes.size - 1)
        shapes.forEachIndexed { index, shape ->
            val energy = when (mode) {
                IslandMode.LISTENING -> 0.25f + 0.75f * Island.level
                IslandMode.THINKING, IslandMode.SPEAKING -> 0.3f + 0.7f * ((pulse + index * 0.3f) % 1f)
                IslandMode.MESSAGE -> 0.45f
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
