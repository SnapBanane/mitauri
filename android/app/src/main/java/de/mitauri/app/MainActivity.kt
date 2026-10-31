package de.mitauri.app

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh++
            PresenceBeacon.start(this)
        }
    private var refresh by mutableIntStateOf(0)
    private var photoCaption = ""
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendPhoto(uri, photoCaption)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keeps your chats out of the recent apps preview. Screenshots you take still work.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        Mitauri.init(this)
        MitauriService.start(this)
        setContent {
            MitauriTheme {
                Surface(Modifier.fillMaxSize(), color = MitauriColors.Background, contentColor = MitauriColors.Ink) {
                    var showSettings by remember { mutableStateOf(false) }
                    var showMemories by remember { mutableStateOf(false) }
                    var showNotifications by remember { mutableStateOf(false) }
                    var showDoorLogin by remember { mutableStateOf(false) }
                    var showModules by remember { mutableStateOf(false) }
                    BackHandler(showSettings) {
                        when {
                            showMemories -> showMemories = false
                            showNotifications -> showNotifications = false
                            showDoorLogin -> showDoorLogin = false
                            showModules -> showModules = false
                            else -> showSettings = false
                        }
                    }
                    when {
                        !Mitauri.isPaired -> PairScreen { MitauriService.start(this) }
                        showMemories -> MemoryScreen { showMemories = false }
                        showNotifications -> NotificationAppsScreen { showNotifications = false }
                        showDoorLogin -> DoorLoginScreen { showDoorLogin = false }
                        showModules -> ModulesScreen { showModules = false }
                        showSettings -> SettingsScreen(
                            setupItems(refresh),
                            ::restartApp,
                            { confirmIdentity("Memories and privacy") { showMemories = true } },
                            { confirmIdentity("Notifications") { showNotifications = true } },
                            { confirmIdentity("Modules") { showModules = true } },
                            { confirmIdentity("Door login") { showDoorLogin = true } },
                            DoorLogin.isSet(this),
                            { confirmIdentity("Disconnect from this server") { Mitauri.unpair() } },
                        ) { showSettings = false }
                        else -> ChatScreen(
                            needsSetup = setupItems(refresh).any { it.done == false && it.required },
                            onSettings = { showSettings = true },
                            onVoice = ::startVoice,
                            onPhoto = { caption ->
                                photoCaption = caption
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        refresh++
    }

    override fun onPause() {
        super.onPause()
        isVisible = false
    }

    /** Re-encoding keeps the photo small and removes its metadata, including where it was taken. */
    private fun sendPhoto(uri: Uri, text: String) {
        Mitauri.photoStarted(text)
        lifecycleScope.launch(Dispatchers.IO) {
            val reply = try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > MAX_PHOTO_PIXELS) {
                        decoder.setTargetSize(info.size.width * MAX_PHOTO_PIXELS / longest, info.size.height * MAX_PHOTO_PIXELS / longest)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                Mitauri.askAboutPhoto(output.toByteArray(), text)
            } catch (_: Exception) {
                "The photo couldn't be sent."
            }
            withContext(Dispatchers.Main) { Mitauri.photoAnswered(reply) }
        }
    }

    private fun startVoice() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            VoiceMode.listen(this)
        }
    }

    /** Asks for a fingerprint or the screen lock before showing or changing private things. */
    private fun confirmIdentity(title: String, onConfirmed: () -> Unit) {
        val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onConfirmed() // This phone has no screen lock to check against.
            return
        }
        BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onConfirmed()
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(allowed).build()
        )
    }

    private fun restartApp() {
        VoiceMode.stop()
        Mitauri.disconnect()
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(Intent.makeRestartActivityTask(launch.component))
        Process.killProcess(Process.myPid()) // The service and connection come back with the app.
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(action: String) {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    }

    private fun openAutostart() {
        try {
            startActivity(
                Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
            )
        } catch (_: RuntimeException) {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
    }

    private fun setupItems(@Suppress("UNUSED_PARAMETER") refreshKey: Int): List<SetupItem> {
        val roles = getSystemService(RoleManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)
        return listOf(
            SetupItem("Power button", "Make Mitauri your default assistant", roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            },
            SetupItem("Microphone", "For voice mode", granted(Manifest.permission.RECORD_AUDIO)) {
                askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            },
            SetupItem("Island", "Turn on Mitauri Island under Accessibility. It reads nothing on your screen", IslandService.instance != null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            SetupItem("Stay active in the background", "Set battery use to unrestricted", power.isIgnoringBatteryOptimizations(packageName)) {
                openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            },
            SetupItem(
                "Presence beacon", "Lets your Bluetooth sensor notice you nearby",
                PresenceBeacon.canRun(this), required = false,
            ) {
                askPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE))
            },
            SetupItem("Autostart", "On Xiaomi: turn on Autostart for Mitauri", null, action = ::openAutostart),
            SetupItem("Notifications", "For calls and reminders", notifications) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            SetupItem(
                "Location", "Only read when Mitauri needs it",
                granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            ) {
                askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            SetupItem(
                "Background location", "Choose Allow all the time",
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION), required = false,
            ) {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            },
        )
    }

    companion object {
        private const val MAX_PHOTO_PIXELS = 1600
        var isVisible = false
            private set
    }
}
