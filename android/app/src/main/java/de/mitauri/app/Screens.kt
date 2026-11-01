package de.mitauri.app

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupItem(
    val title: String,
    val description: String,
    val done: Boolean?,
    val required: Boolean = true,
    val action: () -> Unit,
)

@Composable
fun Orb(size: Int, active: Boolean) {
    val pulse by rememberInfiniteTransition(label = "mark").animateFloat(
        0.96f, 1.03f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "mark",
    )
    Image(
        painterResource(R.drawable.logo_mark),
        null,
        Modifier.size(size.dp).alpha(if (active) pulse.coerceAtMost(1f) else 1f),
    )
}

@Composable
private fun Icon(resource: Int, description: String?, tint: Color = MitauriColors.Muted, size: Int = 22) {
    Image(painterResource(resource), description, Modifier.size(size.dp), colorFilter = ColorFilter.tint(tint))
}

@Composable
private fun IconAction(resource: Int, description: String, tint: Color = MitauriColors.Muted, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(resource, description, tint) }
}

@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.ic_back, "Back", MitauriColors.Ink, onBack)
            Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp),
        color = MitauriColors.Muted,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MitauriColors.Surface),
        content = content,
    )
}

@Composable
private fun ListRow(
    title: String,
    description: String? = null,
    icon: Int? = null,
    titleColor: Color = MitauriColors.Ink,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { Icon(it, null, if (titleColor == MitauriColors.Ink) MitauriColors.Muted else titleColor) }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        }
        trailing()
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MitauriColors.Hairline, thickness = 0.5.dp)
}

@Composable
fun PairScreen(onPaired: () -> Unit) {
    var address by remember { mutableStateOf(Mitauri.server.removePrefix("ws://").substringBefore(":")) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(104.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        Text("Connect to Mitauri", style = MaterialTheme.typography.headlineMedium)
        Text("Run mitauri pair on your server and enter the details here.", color = MitauriColors.Muted)
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Server address") }, singleLine = true)
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("One-time code") }, singleLine = true)
        error?.let { Text(it, color = MitauriColors.Danger) }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val failure = withContext(Dispatchers.IO) { Mitauri.pair(address, code) }
                    busy = false
                    error = failure
                    if (failure == null) onPaired()
                }
            },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = !busy && address.isNotBlank() && code.isNotBlank(),
        ) { Text(if (busy) "Connecting…" else "Connect") }
    }
}

@Composable
fun DoorLoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSet by remember { mutableStateOf(DoorLogin.isSet(context)) }
    ScreenScaffold("Door login", onBack) {
        Text(
            "With a login here, saying or typing exactly \"Öffne die Tür\" or \"Tür auf\" presses the door button after 3 seconds, " +
                "with no fingerprint, and it's logged under your name. Tap the island to cancel. It only works while this phone is unlocked. " +
                "The login stays encrypted on this phone and is never sent to the server or Claude.",
            color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
        if (isSet) {
            Group { ListRow("Remove door login", "Goes back to the door app and its fingerprint", R.drawable.ic_lock, titleColor = MitauriColors.Danger, onClick = {
                DoorLogin.clear(context)
                isSet = false
            }) }
        } else {
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    DoorLogin.save(context, user, password)
                    user = ""
                    password = ""
                    isSet = true
                },
                Modifier.fillMaxWidth(),
                enabled = user.isNotBlank() && password.isNotBlank(),
            ) { Text("Save login") }
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = MitauriColors.Accent,
    checkedThumbColor = MitauriColors.AccentInk,
    uncheckedTrackColor = MitauriColors.Raised,
    uncheckedThumbColor = MitauriColors.Muted,
    uncheckedBorderColor = MitauriColors.Muted,
)

@Composable
fun ModulesScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<ModuleInfo>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            items = withContext(Dispatchers.IO) { Mitauri.fetchModules() }
        } catch (_: Exception) {
            message = "The server can't be reached right now."
        }
    }
    ModulesContent(items, message, onBack) { module, enabled ->
        scope.launch {
            try {
                items = withContext(Dispatchers.IO) { Mitauri.setModule(module.name, enabled) }
                message = null
            } catch (_: Exception) {
                message = "That change couldn't be saved."
            }
        }
    }
}

@Composable
fun ModulesContent(items: List<ModuleInfo>?, message: String?, onBack: () -> Unit, onChange: (ModuleInfo, Boolean) -> Unit) {
    ScreenScaffold("Modules", onBack) {
        Text(
            "Switch off what you don't use. Mitauri loses its tools and stops any background work, so there's less it can reach.",
            color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MitauriColors.Danger, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(18.dp))
        if (items == null) {
            Text("Loading…", color = MitauriColors.Muted)
        } else {
            Group {
                items.forEachIndexed { index, module ->
                    if (index > 0) Divider()
                    ListRow(module.label, module.description) {
                        if (module.required) {
                            Text("Always on", color = MitauriColors.Muted, style = MaterialTheme.typography.labelMedium)
                        } else {
                            Switch(
                                module.enabled,
                                { onChange(module, it) },
                                colors = switchColors(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(NotificationAccess.allowedApps(context)) }
    val apps = remember { NotificationAccess.seenApps(context) }
    ScreenScaffold("Notifications", onBack) {
        Text(
            "Mitauri only reads apps you turn on here, only when you ask, and doesn't store them on this phone. " +
                "What it reads goes to Claude to answer you. Banking, password and code apps are always blocked.",
            color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        if (!NotificationAccess.isEnabled(context)) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                Modifier.fillMaxWidth(),
            ) { Text("Turn on access") }
        }
        SectionLabel("Allowed apps")
        if (apps.isEmpty()) {
            Text("Apps appear here once they've sent a notification.", color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            Group {
                apps.forEachIndexed { index, app ->
                    if (index > 0) Divider()
                    ListRow(NotificationAccess.appLabel(context, app)) {
                        Switch(
                            app in allowed,
                            { on ->
                                NotificationAccess.setAllowed(context, app, on)
                                allowed = NotificationAccess.allowedApps(context)
                            },
                            colors = switchColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WatchScreen(refresh: Int, onAskPermission: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(WatchLink.address(context)) }
    var found by remember { mutableStateOf(emptyList<FoundWatch>()) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var allowed by remember { mutableStateOf(WatchLink.allowedApps(context)) }
    val apps = remember { NotificationAccess.seenApps(context) }
    val canRun = remember(refresh) { WatchLink.canRun(context) }
    DisposableEffect(Unit) { onDispose { WatchLink.stopScan(context) } }
    ScreenScaffold("Watch", onBack) {
        Text(
            "This watch has no pairing or encryption. Anyone nearby with the right tools could read what's sent to it, " +
                "so only turn on apps you're fine with that for. Banking, password and code apps are always blocked.",
            color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        if (WatchLink.oldAppInstalled(context)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "VeryFitPro is still installed. The watch only talks to one app at a time, so uninstall or force stop it.",
                color = MitauriColors.Danger, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        when {
            !canRun -> Button(onAskPermission, Modifier.fillMaxWidth()) { Text("Allow Bluetooth") }
            address == null -> {
                Button(
                    onClick = {
                        found = emptyList()
                        scanning = true
                        WatchLink.scan(context, { results -> found = results }, { scanning = false })
                    },
                    Modifier.fillMaxWidth(), enabled = !scanning,
                ) { Text(if (scanning) "Searching..." else "Find my watch") }
                if (found.isNotEmpty()) {
                    SectionLabel("Tap yours")
                    Group {
                        found.forEachIndexed { index, watch ->
                            if (index > 0) Divider()
                            ListRow(watch.name, "${watch.address}, signal ${watch.rssi}", onClick = {
                                WatchLink.stopScan(context)
                                WatchLink.link(context, watch.address)
                                address = watch.address
                            })
                        }
                    }
                } else if (!scanning) {
                    Spacer(Modifier.height(8.dp))
                    Text("Keep the watch awake and close to the phone.", color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> {
                val state = if (WatchLink.connected) "Connected" + (WatchLink.battery?.let { ", battery $it%" } ?: "") else "Looking for the watch..."
                Group {
                    ListRow(state, address)
                    Divider()
                    ListRow("Send a test message", onClick = {
                        message = "Sending..."
                        WatchLink.show("Mitauri", "Hallo vom Handy!") { sent -> message = if (sent) "The watch accepted it." else "The watch didn't accept it." }
                    })
                    Divider()
                    ListRow("Forget this watch", titleColor = MitauriColors.Danger, onClick = {
                        WatchLink.forget(context)
                        address = null
                        message = null
                    })
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SectionLabel("Show these apps on the watch")
        if (!NotificationAccess.isEnabled(context)) {
            Text("Needs notification access, under Settings > Notifications.", color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium)
        } else if (apps.isEmpty()) {
            Text("Apps appear here once they've sent a notification.", color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            Group {
                apps.forEachIndexed { index, app ->
                    if (index > 0) Divider()
                    ListRow(NotificationAccess.appLabel(context, app)) {
                        Switch(
                            app in allowed,
                            { on ->
                                WatchLink.setAllowed(context, app, on)
                                allowed = WatchLink.allowedApps(context)
                            },
                            colors = switchColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutoMusicScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var canSee by remember { mutableStateOf(AutoMusic.canSeeOpenApp(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            canSee = AutoMusic.canSeeOpenApp(context)
            delay(1000)
        }
    }
    ScreenScaffold("Auto music", onBack) {
        Text(
            "Spotify starts when headphones you pick here connect. Not during a call, while something's already playing, or while WhatsApp is open.",
            color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        SectionLabel("Headphones")
        val names = AutoMusic.seen.sorted()
        if (names.isEmpty()) {
            Text("Connect your headphones once and they'll show up here.", color = MitauriColors.Muted)
        } else {
            Group {
                names.forEachIndexed { index, name ->
                    if (index > 0) Divider()
                    ListRow(name) {
                        Switch(name in AutoMusic.chosen, { on -> AutoMusic.setChosen(context, name, on) }, colors = switchColors())
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            AutoMusic.lastResult.ifBlank { "Switched-on headphones start the music the next time they connect." },
            color = MitauriColors.AccentSoft, style = MaterialTheme.typography.bodyMedium,
        )
        SectionLabel("Keeping out of WhatsApp")
        Group {
            ListRow(
                if (canSee) "Usage access is on" else "Turn on usage access",
                if (canSee) "Mitauri can tell whether WhatsApp is open. It only checks when your headphones connect." else
                    "Needed to tell whether WhatsApp is open. Without it, music only starts while the phone is locked.",
                R.drawable.ic_lock,
                onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            )
        }
    }
}

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    var memories by remember { mutableStateOf<List<Memory>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf<Memory?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmStyle by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        try {
            memories = withContext(Dispatchers.IO) { Mitauri.fetchMemories() }
            style = withContext(Dispatchers.IO) { Mitauri.fetchStyle() }
        } catch (_: Exception) {
            message = "The server can't be reached right now."
        }
    }
    LaunchedEffect(Unit) { reload() }
    confirmForget?.let { memory ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget this?") },
            text = { Text(memory.text) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Mitauri.deleteMemory(memory.id) }
                            reload()
                        } catch (_: Exception) {
                            message = "The server can't be reached right now."
                        }
                    }
                }) { Text("Forget", color = MitauriColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete long-term memory?") },
            text = { Text("All past chats kept for searching are deleted from the server. This can't be undone. Saved memories above stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) { Mitauri.clearLongTermMemory() }
                            "Long-term memory deleted."
                        } catch (_: Exception) {
                            "The server can't be reached right now."
                        }
                    }
                }) { Text("Delete", color = MitauriColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (confirmNew) {
        NewChatDialog({ confirmNew = false }) { message = "New chat started. The old history is deleted." }
    }
    if (confirmStyle) {
        AlertDialog(
            onDismissRequest = { confirmStyle = false },
            title = { Text("Forget how you write?") },
            text = { Text("The description and all the lines it was learned from are deleted. It starts learning again from new messages.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStyle = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Mitauri.clearStyle() }
                            style = "" to ""
                            message = "Forgotten."
                        } catch (_: Exception) {
                            message = "The server can't be reached right now."
                        }
                    }
                }) { Text("Forget", color = MitauriColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmStyle = false }) { Text("Keep") } },
        )
    }
    ScreenScaffold("Memories and privacy", onBack) {
        Text(
            "What Mitauri remembers about you. Tap something to forget it. It can still recall it in the current conversation until you start a new chat.",
            color = MitauriColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MitauriColors.AccentSoft, style = MaterialTheme.typography.bodyMedium)
        }
        SectionLabel("Saved")
        val list = memories
        when {
            list == null -> Text("Loading…", color = MitauriColors.Muted)
            list.isEmpty() -> Text("Nothing saved yet.", color = MitauriColors.Muted)
            else -> Group {
                list.forEachIndexed { index, memory ->
                    if (index > 0) Divider()
                    ListRow(memory.text, onClick = { confirmForget = memory })
                }
            }
        }
        SectionLabel("How you write")
        val notes = style
        when {
            notes == null -> Text("Loading…", color = MitauriColors.Muted)
            notes.first.isBlank() && notes.second.isBlank() -> Text(
                "Nothing learned yet. To teach it faster, export a WhatsApp chat without media and share it with Mitauri.",
                color = MitauriColors.Muted,
            )
            else -> Group {
                if (notes.first.isNotBlank()) ListRow("To friends", notes.first)
                if (notes.first.isNotBlank() && notes.second.isNotBlank()) Divider()
                if (notes.second.isNotBlank()) ListRow("To Mitauri", notes.second)
                Divider()
                ListRow("Forget how I write", "Deletes this and the lines it was learned from", R.drawable.ic_lock, titleColor = MitauriColors.Danger, onClick = { confirmStyle = true })
            }
        }
        SectionLabel("History")
        Group {
            ListRow("New chat", "Deletes the history on this phone and the conversation on the server", R.drawable.ic_new_chat, onClick = { confirmNew = true })
            Divider()
            ListRow("Delete long-term memory", "Past chats kept so Mitauri can search them", R.drawable.ic_lock, titleColor = MitauriColors.Danger, onClick = { confirmClear = true })
        }
    }
}

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onDone: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a new chat?") },
        text = { Text("The history on this phone and the conversation on the server are deleted. Saved memories stay.") },
        confirmButton = {
            TextButton(onClick = {
                Mitauri.startNewChat()
                onDismiss()
                onDone()
            }) { Text("New chat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ChatScreen(needsSetup: Boolean, onSettings: () -> Unit, onVoice: () -> Unit, onPhoto: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var confirmNew by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (confirmNew) NewChatDialog({ confirmNew = false })
    ChatContent(
        messages = Mitauri.messages,
        status = Mitauri.status,
        waiting = Mitauri.waiting,
        needsSetup = needsSetup,
        draft = draft,
        onDraft = { draft = it },
        onDoor = { Toast.makeText(context, Door.open(context).substringBefore(" The door"), Toast.LENGTH_SHORT).show() },
        onNewChat = { confirmNew = true },
        onSettings = onSettings,
        onVoice = onVoice,
        onPhoto = {
            onPhoto(draft.trim())
            draft = ""
        },
        onSend = { if (Mitauri.send(draft.trim())) draft = "" },
    )
}

@Composable
fun ChatContent(
    messages: List<ChatMessage>,
    status: Status,
    waiting: Boolean,
    needsSetup: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onDoor: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    onVoice: () -> Unit,
    onPhoto: () -> Unit,
    onSend: () -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, waiting) {
        val count = messages.size + if (waiting) 1 else 0
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Header(status, needsSetup, onDoor, onNewChat, onSettings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(onDraft)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = list,
                    contentPadding = PaddingValues(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages) { Message(it) }
                    if (waiting) item { ThinkingDots() }
                }
            }
        }
        Composer(draft, onDraft, status == Status.CONNECTED, onVoice, onPhoto, onSend)
    }
}

@Composable
private fun Header(status: Status, needsSetup: Boolean, onDoor: () -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit) {
    val (label, color) = when (status) {
        Status.CONNECTED -> "Connected" to MitauriColors.Success
        Status.CONNECTING -> "Connecting…" to MitauriColors.Warning
        Status.DISCONNECTED -> "Offline" to MitauriColors.Muted
        Status.REJECTED -> "Not accepted" to MitauriColors.Danger
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text("Mitauri", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(color, CircleShape))
                Text(label, color = MitauriColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        IconAction(R.drawable.ic_door, "Open door", onClick = onDoor)
        IconAction(R.drawable.ic_new_chat, "New chat", onClick = onNewChat)
        Box {
            IconAction(R.drawable.ic_settings, "Settings", onClick = onSettings)
            if (needsSetup) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(8.dp).background(MitauriColors.Accent, CircleShape))
            }
        }
    }
    HorizontalDivider(color = MitauriColors.Hairline, thickness = 0.5.dp)
}

/** Replaced in screenshot tests, which must not depend on the time of day. */
internal var currentHour: () -> Int = { LocalTime.now().hour }

private fun greeting(): String = when (currentHour()) {
    in 5..10 -> "Good morning"
    in 11..17 -> "Hello"
    in 18..22 -> "Good evening"
    else -> "Still up?"
}

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(112.dp))
        Spacer(Modifier.height(20.dp))
        Text(greeting(), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text("How can I help?", color = MitauriColors.Muted)
        Spacer(Modifier.height(28.dp))
        Group {
            listOf("Was steht heute an?", "Wann fährt mein nächster Bus?", "Erinnere mich in einer Stunde an …").forEachIndexed { index, text ->
                if (index > 0) Divider()
                ListRow(text, onClick = { onSuggestion(text) }) { Icon(R.drawable.ic_chevron, null, size = 20) }
            }
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, connected: Boolean, onVoice: () -> Unit, onPhoto: () -> Unit, onSend: () -> Unit) {
    val hasText = draft.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MitauriColors.Surface)
            .border(0.5.dp, MitauriColors.Hairline, RoundedCornerShape(26.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconAction(R.drawable.ic_image, "Send photo", onClick = onPhoto)
        Box(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 2.dp)) {
            if (draft.isEmpty()) Text("Message Mitauri", color = MitauriColors.Muted)
            BasicTextField(
                draft, onDraft, Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = MitauriColors.Ink, fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight),
                cursorBrush = SolidColor(MitauriColors.Accent),
                maxLines = 5,
            )
        }
        val enabled = !hasText || connected
        Box(
            Modifier
                .size(44.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .clip(CircleShape)
                .background(if (hasText) MitauriColors.Accent else MitauriColors.Raised)
                .clickable(enabled = enabled, onClickLabel = if (hasText) "Send" else "Talk") { if (hasText) onSend() else onVoice() },
            contentAlignment = Alignment.Center,
        ) {
            if (hasText) Icon(R.drawable.ic_send, "Send", MitauriColors.AccentInk)
            else Icon(R.drawable.ic_mic, "Talk", MitauriColors.Ink)
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            val dot by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(600, delayMillis = index * 150), RepeatMode.Reverse),
                label = "dot",
            )
            Box(Modifier.size(7.dp).alpha(dot).background(MitauriColors.AccentSoft, CircleShape))
        }
    }
}

/** Your messages sit in a bubble, Mitauri's read as plain text, which suits long answers. */
@Composable
private fun Message(message: ChatMessage) {
    if (message.fromUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                linkified(message.text),
                Modifier
                    .widthIn(max = 300.dp)
                    .background(MitauriColors.UserBubble, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    } else {
        Text(linkified(message.text), Modifier.fillMaxWidth().padding(end = 24.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsScreen(
    items: List<SetupItem>,
    onRestart: () -> Unit,
    onMemories: () -> Unit,
    onNotifications: () -> Unit,
    onModules: () -> Unit,
    onAutoMusic: () -> Unit,
    onWatch: () -> Unit,
    onDoorLogin: () -> Unit,
    doorLoginSet: Boolean,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect from this server?") },
            text = { Text("This phone's key is deleted, and you'll need a new code to connect again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) { Text("Disconnect", color = MitauriColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
    ScreenScaffold("Settings", onBack) {
        SectionLabel("Privacy")
        Group {
            ListRow("Memories and privacy", "What Mitauri knows about you", R.drawable.ic_lock, onClick = onMemories) { Icon(R.drawable.ic_chevron, null, size = 20) }
            Divider()
            ListRow("Door login", if (doorLoginSet) "Saying \"Öffne die Tür\" opens the door" else "Off: the door app and its fingerprint are used", R.drawable.ic_door, onClick = onDoorLogin) { Icon(R.drawable.ic_chevron, null, size = 20) }
            Divider()
            ListRow("Notifications", "Which apps Mitauri may read", R.drawable.ic_bell, onClick = onNotifications) { Icon(R.drawable.ic_chevron, null, size = 20) }
        }
        SectionLabel("Features")
        Group {
            ListRow("Auto music", "Start Spotify when your headphones connect", R.drawable.ic_settings, onClick = onAutoMusic) { Icon(R.drawable.ic_chevron, null, size = 20) }
            Divider()
            ListRow("Watch", "Connect your watch without its old app", R.drawable.ic_bell, onClick = onWatch) { Icon(R.drawable.ic_chevron, null, size = 20) }
            Divider()
            ListRow("Modules", "Switch features on and off", R.drawable.ic_settings, onClick = onModules) { Icon(R.drawable.ic_chevron, null, size = 20) }
        }
        SectionLabel("Setup")
        Group {
            items.forEachIndexed { index, item ->
                if (index > 0) Divider()
                ListRow(item.title, item.description, onClick = item.action) {
                    when (item.done) {
                        true -> Icon(R.drawable.ic_check, "Done", MitauriColors.Success)
                        false -> Text("Set up", color = MitauriColors.Accent, style = MaterialTheme.typography.labelMedium)
                        null -> Icon(R.drawable.ic_chevron, null, size = 20)
                    }
                }
            }
        }
        SectionLabel("App")
        Group {
            val context = LocalContext.current
            ListRow(
                "Check for updates",
                Updater.status.ifEmpty { "Version ${Updater.currentVersionName(context)}" },
                R.drawable.ic_restart,
                onClick = { Updater.checkByHand(context) },
            )
            Divider()
            ListRow("Restart Mitauri", icon = R.drawable.ic_restart, onClick = onRestart)
            Divider()
            ListRow("Disconnect", icon = R.drawable.ic_unlink, titleColor = MitauriColors.Danger, onClick = { confirmDisconnect = true })
        }
    }
}

private val LINK = Regex("""https?://\S+""")

private fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var position = 0
    for (match in LINK.findAll(text)) {
        append(text.substring(position, match.range.first))
        val style = TextLinkStyles(SpanStyle(MitauriColors.AccentSoft, textDecoration = TextDecoration.Underline))
        pushLink(LinkAnnotation.Url(match.value, style))
        append(match.value)
        pop()
        position = match.range.last + 1
    }
    append(text.substring(position))
}
