package com.example.delayedsecrect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings

// Defaults per your spec — will become user-configurable in the Settings step
const val CLIPBOARD_CLEAR_DELAY_MS = 5 * 1000L // 5 seconds
const val DEFAULT_DELAY_MS = 1 * 60 * 1000L
const val DEFAULT_VISIBLE_MS = 60 * 1000L
const val EXTEND_MS = 5 * 60 * 1000L

enum class Screen { LIST, SETTINGS }
class MainActivity : ComponentActivity() {

    private lateinit var secretStore: SecretStore
    private lateinit var timerStore: TimerStore

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        secretStore = SecretStore(applicationContext)
        timerStore = TimerStore(applicationContext)

        val settingsStore = SettingsStore(applicationContext)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.LIST) }
                when (screen) {
                    Screen.LIST -> SecretListScreen(
                        secretStore, timerStore, settingsStore,
                        onOpenSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settingsStore,
                        onDone = { screen = Screen.LIST }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SecretListScreen(
    secretStore: SecretStore,
    timerStore: TimerStore,
    settingsStore: SettingsStore,
    onOpenSettings: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var secretPendingDelete by remember { mutableStateOf<Secret?>(null) }
    var secrets by remember { mutableStateOf(secretStore.getAll()) }
    var showAddDialog by remember { mutableStateOf(false) }

    // One RevealTimer per secret, created once and reused
    var delayMs by remember { mutableStateOf(settingsStore.getDelayMinutes() * 60_000L) }
    var visibleMs by remember { mutableStateOf(settingsStore.getVisibleSeconds() * 1000L) }

    // Re-read settings each time this screen becomes visible again (e.g. returning from Settings)
    LaunchedEffect(Unit) {
        delayMs = settingsStore.getDelayMinutes() * 60_000L
        visibleMs = settingsStore.getVisibleSeconds() * 1000L
    }

    val timers = remember { mutableStateMapOf<String, RevealTimer>() }
    secrets.forEach { secret ->
        if (!timers.containsKey(secret.id)) {
            timers[secret.id] = RevealTimer(secret.id, delayMs, visibleMs, timerStore)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var overlayGranted by remember { mutableStateOf(hasOverlayPermission(context)) }

    val lastKnownState = remember { mutableStateMapOf<String, RevealState>() }

    // Global 1-second tick so every card's countdown updates live
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    LaunchedEffect(tick) {
        overlayGranted = hasOverlayPermission(context)
        settingsStore.reconcilePending()
        delayMs = settingsStore.getDelayMinutes() * 60_000L
        visibleMs = settingsStore.getVisibleSeconds() * 1000L
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delayed Secret") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(androidx.compose.material.icons.Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (!overlayGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Overlay permission needed to show secrets as a floating window.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { requestOverlayPermission(context) }) {
                            Text("Grant permission")
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                items(secrets, key = { it.id }) { secret ->
                val timer = timers[secret.id]!!
                @Suppress("UNUSED_EXPRESSION") tick
                    val previousState = lastKnownState[secret.id]
                    val info = timer.currentState()
                    if (previousState == RevealState.COUNTING_DOWN && info.state == RevealState.REVEALED) {
                        showSecretReadyNotification(context, secret.name, secret.id.hashCode())

                        if (hasOverlayPermission(context)) {
                            val serviceIntent = Intent(context, OverlayService::class.java).apply {
                                putExtra(OverlayService.EXTRA_SECRET_NAME, secret.name)
                                putExtra(OverlayService.EXTRA_SECRET_VALUE, secret.value)
                                putExtra(OverlayService.EXTRA_SECRET_ID, secret.id)
                                putExtra(OverlayService.EXTRA_HIDE_AT_ELAPSED_MS, info.hideAtElapsedMs)
                            }
                            context.startForegroundService(serviceIntent)
                        }
                    }
                    lastKnownState[secret.id] = info.state

                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(secret.name, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { secretPendingDelete = secret }) {
                                Text("Delete")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        when (info.state) {
                            RevealState.LOCKED -> {
                                Button(onClick = { timer.startCountdown() }) {
                                    Text("Reveal")
                                }
                            }
                            RevealState.COUNTING_DOWN -> {
                                val remainingMs = info.revealAtElapsedMs - android.os.SystemClock.elapsedRealtime()
                                Text("Unlocking in ${formatDuration(remainingMs)}")
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    OutlinedButton(onClick = { timer.cancel() }) {
                                        Text("Cancel")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.extendCountdown(EXTEND_MS) }) {
                                        Text("+5 min")
                                    }
                                }
                            }
                            RevealState.REVEALED -> {
                                val remainingMs = info.hideAtElapsedMs - android.os.SystemClock.elapsedRealtime()
                                Text("SECRET: ${secret.value}", style = MaterialTheme.typography.bodyLarge)
                                Text("Hides in ${formatDuration(remainingMs)}")
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    OutlinedButton(onClick = {
                                        copySecretToClipboard(context, secret.value)
                                        scope.launch {
                                            kotlinx.coroutines.delay(CLIPBOARD_CLEAR_DELAY_MS)
                                            clearClipboardIfMatches(context, secret.value)
                                        }
                                    }) {
                                        Text("Copy")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(onClick = { timer.closeEarly() }) {
                                        Text("Close")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }}

    if (showAddDialog) {
        AddSecretDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, value ->
                secretStore.add(name, value)
                secrets = secretStore.getAll()
                showAddDialog = false
            }
        )
    }

    secretPendingDelete?.let { secret ->
        AlertDialog(
            onDismissRequest = { secretPendingDelete = null },
            title = { Text("Delete \"${secret.name}\"?") },
            text = { Text("Are you sure? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    timers[secret.id]?.cancel()   // clears any active countdown/reveal state too
                    timerStore.clear(secret.id)
                    secretStore.delete(secret.id)
                    timers.remove(secret.id)
                    lastKnownState.remove(secret.id)
                    secrets = secretStore.getAll()
                    secretPendingDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { secretPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "${seconds}s"
}

@Composable
fun AddSecretDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Secret") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Secret text") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && value.isNotBlank()) onSave(name, value)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}