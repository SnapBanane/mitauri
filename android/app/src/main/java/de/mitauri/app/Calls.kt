package de.mitauri.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Incoming calls and reminders from the server. */
object Calls {
    private const val CALL_CHANNEL = "calls"
    private const val REMINDER_CHANNEL = "reminders"
    private const val CALL_NOTIFICATION = 2
    private const val ALARM_NOTIFICATION = 4
    private const val RING_MILLIS = 45_000L
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { app?.let(::missed) }
    private var app: Context? = null
    var reason = ""
        private set

    /** Shows a call, even over the lock screen, ringing until answered or it times out. */
    fun ring(context: Context, text: String) {
        val application = context.applicationContext
        app = application
        reason = text
        createChannels(application)
        val intent = Intent(application, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pending = PendingIntent.getActivity(
            application, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(application, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mitauri is calling")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT // keeps ringing
        application.getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION, notification)
        if (Settings.canDrawOverlays(application)) {
            // The full-screen notification alone only shows a banner while the phone is in use.
            try {
                application.startActivity(intent)
            } catch (_: RuntimeException) {
                // The notification is still ringing.
            }
        }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, RING_MILLIS)
    }

    /** A wake-up on the speaker: a notification with a Stop button, for as long as it plays. */
    fun alarmStarted(context: Context, text: String) {
        val application = context.applicationContext
        createChannels(application)
        val stop = PendingIntent.getBroadcast(
            application, 3, Intent(application, AlarmStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(application, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wake-up alarm")
            .setContentText(text)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        application.getSystemService(NotificationManager::class.java).notify(ALARM_NOTIFICATION, notification)
    }

    fun alarmOver(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION)
    }

    /** Stops the ringing because the call was answered or declined, and any alarm with it. */
    fun end() {
        Mitauri.stopAlarm()
        main.removeCallbacks(giveUp)
        app?.getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIFICATION)
        onEnded?.invoke()
    }

    var onEnded: (() -> Unit)? = null

    private fun missed(context: Context) {
        val text = reason
        end()
        notify(context, "Missed call", text.ifBlank { "Mitauri tried to reach you." })
    }

    /** A reminder's text stays hidden on the lock screen until the phone is unlocked. */
    fun notify(context: Context, title: String, text: String) {
        val application = context.applicationContext
        createChannels(application)
        val open = PendingIntent.getActivity(
            application, 2, Intent(application, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val hidden = Notification.Builder(application, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mitauri")
            .setContentText("You have a reminder")
            .build()
        val notification = Notification.Builder(application, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        application.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Alarm audio, because a call you asked for should get through a silent phone.
        val calls = NotificationChannel(CALL_CHANNEL, "Calls from Mitauri", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val reminders = NotificationChannel(REMINDER_CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(calls)
        manager.createNotificationChannel(reminders)
    }
}

class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Mitauri.init(context)
        Mitauri.stopAlarm()
        Calls.alarmOver(context)
    }
}

private enum class CallState { RINGING, CONNECTING, ACTIVE }

/** The call screen. Over the lock screen it hides the reason until you unlock. */
class CallActivity : ComponentActivity() {
    private var state by mutableStateOf(CallState.RINGING)
    private var startedAt by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Mitauri.init(this)
        Calls.onEnded = { if (state == CallState.RINGING) finish() }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MitauriColors.Background, contentColor = MitauriColors.Ink) {
                    CallScreen(
                        state = state,
                        reason = if (locked) "" else Calls.reason,
                        locked = locked,
                        startedAt = startedAt,
                        onAnswer = ::answer,
                        onDecline = {
                            Calls.end()
                            finish()
                        },
                        onHangUp = ::hangUp,
                    )
                }
            }
        }
    }

    private fun answer() {
        state = CallState.CONNECTING
        val reason = Calls.reason
        Calls.end()
        // Unlocking comes first, so a locked phone can't be used to reach anything private.
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            // Android reports dismissing a lock screen that isn't there as an error.
            talk(reason)
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = talk(reason)
                override fun onDismissCancelled() = finish()
                override fun onDismissError() {
                    if (keyguard.isKeyguardLocked) finish() else talk(reason)
                }
            },
        )
    }

    private fun talk(reason: String) {
        MitauriService.start(this)
        state = CallState.ACTIVE
        startedAt = SystemClock.elapsedRealtime()
        Island.embedded = true
        VoiceMode.afterExchange = { keepGoing -> if (keepGoing) listen() else hangUp() }
        VoiceMode.speakThen(this, reason.ifBlank { "Hallo, ich bin dran." }) { listen() }
    }

    private fun listen() {
        if (!isFinishing) VoiceMode.listen(this)
    }

    private fun hangUp() {
        VoiceMode.afterExchange = null
        VoiceMode.stop()
        Island.embedded = false
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Android takes the microphone away from a screen you've left, so the call ends with it.
        if (state == CallState.ACTIVE && !isFinishing) hangUp()
    }

    override fun onDestroy() {
        Calls.onEnded = null
        if (state == CallState.ACTIVE) {
            VoiceMode.afterExchange = null
            Island.embedded = false
        }
        super.onDestroy()
    }
}

@Composable
private fun CallScreen(
    state: CallState,
    reason: String,
    locked: Boolean,
    startedAt: Long,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
) {
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "glow",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(MitauriColors.Accent.copy(alpha = 0.16f * glow), Color.Transparent),
                    radius = 1500f,
                )
            )
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.55f))
            RippleOrb(state)
            Spacer(Modifier.height(40.dp))
            Text("Mitauri", fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(10.dp))
            AnimatedContent(state, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "status") {
                when (it) {
                    CallState.RINGING -> Text("is calling…", color = MitauriColors.AccentSoft, fontSize = 17.sp)
                    CallState.CONNECTING -> Text("Unlock to answer", color = MitauriColors.AccentSoft, fontSize = 17.sp)
                    CallState.ACTIVE -> CallTimer(startedAt)
                }
            }
            Spacer(Modifier.height(22.dp))
            AnimatedContent(state == CallState.ACTIVE, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "detail") { active ->
                if (active) LiveStatus()
                else Text(
                    reason.ifBlank { if (locked) "Unlock your phone to see why" else "" },
                    Modifier.padding(horizontal = 12.dp),
                    color = if (reason.isBlank()) MitauriColors.Muted else Color.White,
                    fontSize = if (reason.isBlank()) 14.sp else 18.sp,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            AnimatedContent(state == CallState.RINGING, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "buttons") { ringing ->
                if (ringing) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 44.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallButton("Decline", MitauriColors.Danger, rotation = 135f, pulse = false, onDecline)
                        CallButton("Accept", MitauriColors.Success, rotation = 0f, pulse = true, onAnswer)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(bottom = 44.dp), contentAlignment = Alignment.Center) {
                        CallButton("Hang up", MitauriColors.Danger, rotation = 135f, pulse = false, onHangUp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RippleOrb(state: CallState) {
    val ripple = rememberInfiniteTransition(label = "ripple")
    val progress by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "progress")
    val breathe by ripple.animateFloat(0.96f, 1.05f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    val voice by animateFloatAsState(Island.level, tween(90), label = "voice")
    val listening = state == CallState.ACTIVE && Island.currentMode == IslandMode.LISTENING
    val scale = if (listening) 1f + voice * 0.28f else breathe
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val orbRadius = 64.dp.toPx()
            repeat(3) { index ->
                val phase = (progress + index / 3f) % 1f
                drawCircle(
                    MitauriColors.AccentSoft.copy(alpha = (1f - phase) * 0.34f),
                    radius = orbRadius + (size.minDimension / 2 - orbRadius) * phase,
                    center = center,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
        Box(
            Modifier.size(136.dp).scale(scale).background(MitauriColors.Raised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.logo_mark), null, Modifier.size(96.dp))
        }
    }
}

@Composable
private fun CallTimer(startedAt: Long) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            delay(1000)
        }
    }
    Text(
        "%02d:%02d".format(seconds / 60, seconds % 60),
        color = MitauriColors.AccentSoft,
        fontSize = 17.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun LiveStatus() {
    val (label, color) = when (Island.currentMode) {
        IslandMode.LISTENING -> "Listening" to MitauriColors.Success
        IslandMode.THINKING -> "Thinking" to MitauriColors.AccentSoft
        IslandMode.SPEAKING -> "Speaking" to MitauriColors.AccentSoft
        else -> Island.currentText to MitauriColors.Warning
    }
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun CallButton(label: String, color: Color, rotation: Float, pulse: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "button")
    val ring by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "ring")
    val nudge by transition.animateFloat(-9f, 9f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "nudge")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            if (pulse) {
                Box(
                    Modifier
                        .size(78.dp)
                        .scale(1f + ring * 0.33f)
                        .alpha((1f - ring) * 0.5f)
                        .background(color, CircleShape)
                )
            }
            Box(
                Modifier.size(78.dp).clip(CircleShape).background(color).clickable(onClickLabel = label, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(R.drawable.ic_call),
                    label,
                    Modifier.size(34.dp).rotate(rotation + if (pulse) nudge else 0f),
                )
            }
        }
        Text(label, fontSize = 14.sp, color = Color.White.copy(alpha = 0.86f))
    }
}
