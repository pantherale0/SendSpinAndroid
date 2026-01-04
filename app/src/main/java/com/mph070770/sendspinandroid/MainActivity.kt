package com.mph070770.sendspinandroid

import android.Manifest
import android.content.res.Configuration
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()
    
    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission grant result will be handled by the system
        // NSD discovery will start or continue based on permission grant
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure system bars remain visible and don't draw behind them
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Check if we need to show battery optimization warning (only on first launch)
        val prefs = getSharedPreferences("SendspinPlayerPrefs", MODE_PRIVATE)
        val shownBatteryWarning = prefs.getBoolean("shown_battery_optimization_warning", false)
        
        // Request NEARBY_WIFI_DEVICES permission for mDNS service discovery
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(vm, showBatteryWarning = !shownBatteryWarning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only disconnect if the activity is finishing (not just rotating or backgrounding)
        if (isFinishing) {
            vm.disconnect()
        }
    }
}

@Composable
private fun PlayerScreen(vm: PlayerViewModel, showBatteryWarning: Boolean = false) {
    val ui by vm.ui.collectAsState()
    val discoveredServers by vm.discoveredServers.collectAsState()
    val scrollState = rememberScrollState()
    var showBatteryDialog by remember { mutableStateOf(showBatteryWarning) }

    // Sync with system volume when UI is shown
    LaunchedEffect(Unit) {
        vm.updateAndroidVolumeState()
    }

    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8927") }
    var showManualEntryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sendspin Player", style = MaterialTheme.typography.h5)

        // Show discovery status or manual entry prompt
        if (!ui.connected) {
            if (discoveredServers.isNotEmpty()) {
                Text("Available Servers Found:", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
                Text("Connecting to: ${discoveredServers.first().name}", style = MaterialTheme.typography.caption)
            } else if (!ui.discoveryTimeoutExpired) {
                Text("Searching for Sendspin servers...", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
            } else {
                // Timeout expired, show prompt dialog
                Button(
                    onClick = { showManualEntryDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cannot find server, enter IP address?")
                }
            }
        }

        if (ipAddress.isNotBlank() && port.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.connect(
                            "ws://${ipAddress}:${port}/sendspin"
                        )
                    },
                    enabled = !ui.connected
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    enabled = ui.connected
                ) { Text("Disconnect") }
            }
        }

        Divider()

        // Manual entry dialog
        if (showManualEntryDialog) {
            AlertDialog(
                onDismissRequest = { showManualEntryDialog = false },
                title = { Text("Enter Server Details") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = ipAddress,
                            onValueChange = { ipAddress = it },
                            label = { Text("IP Address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (ipAddress.isNotBlank() && port.isNotBlank()) {
                                val url = "ws://${ipAddress}:${port}/sendspin"
                                vm.connect(url)
                                showManualEntryDialog = false
                            }
                        },
                        enabled = ipAddress.isNotBlank() && port.isNotBlank()
                    ) {
                        Text("Connect")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showManualEntryDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Battery optimization warning dialog (Android 12+)
        if (showBatteryDialog) {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                title = { Text("Disable Battery Optimization") },
                text = {
                    Text(
                        "To prevent the app from being stopped by battery optimization, " +
                        "please disable battery optimization for Sendspin Player in your device settings.\n\n" +
                        "Tap 'Open Settings' below to go to the battery optimization screen."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // Open battery optimization settings
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                            // Update prefs to prevent showing again
                            context.getSharedPreferences("SendspinPlayerPrefs", ComponentActivity.MODE_PRIVATE)
                                .edit {
                                    putBoolean("shown_battery_optimization_warning", true)
                                }
                            showBatteryDialog = false
                        }
                    ) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showBatteryDialog = false }
                    ) {
                        Text("Dismiss")
                    }
                }
            )
        }
        Text("Status: ${ui.status}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Roles: ${ui.activeRoles.ifBlank { "-" }}")
        Text("Group: ${ui.groupName.ifBlank { "-" }} (${ui.playbackState.ifBlank { "-" }})")

        // Metadata Display with Album Art
        if (ui.connected && ui.hasMetadata && ui.trackTitle != null && !ui.isLowMemoryDevice) {
            Divider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Album artwork
                    if (ui.hasArtwork && ui.artworkBitmap != null) {
                        // Use key to force recomposition when bitmap changes
                        key(ui.artworkBitmap) {
                            Image(
                                bitmap = ui.artworkBitmap!!.asImageBitmap(),
                                contentDescription = "Album Art",
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        // Placeholder when no artwork
                        Surface(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            color = MaterialTheme.colors.surface
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = "No artwork",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                tint = MaterialTheme.colors.onSurface
                            )
                        }
                    }

                    // Track info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Now Playing",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = ui.trackTitle ?: "Unknown Track",
                            style = MaterialTheme.typography.h6,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (ui.trackArtist != null) {
                            Text(
                                text = ui.trackArtist!!,
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        if (ui.albumTitle != null) {
                            Text(
                                text = ui.albumTitle!!,
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            if (ui.trackYear != null) {
                                Text(
                                    text = ui.trackYear.toString(),
                                    style = MaterialTheme.typography.overline,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            if (ui.trackNumber != null) {
                                Text(
                                    text = "Track ${ui.trackNumber}",
                                    style = MaterialTheme.typography.overline,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                // Progress bar
                if (ui.trackProgress != null && ui.trackDuration != null) {
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    Column(modifier = Modifier.padding(16.dp).padding(top = 0.dp)) {
                        TrackProgressBar(vm, ui)
                    }
                }

                // Playback state indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)
                ) {
                    if (ui.repeatMode != null && ui.repeatMode != "off") {
                        Surface(
                            color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (ui.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colors.primary
                                )
                                Text(
                                    when (ui.repeatMode) {
                                        "one" -> "Repeat One"
                                        "all" -> "Repeat All"
                                        else -> "Repeat"
                                    },
                                    style = MaterialTheme.typography.caption
                                )
                            }
                        }
                    }

                    if (ui.shuffleEnabled == true) {
                        Surface(
                            color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colors.primary
                                )
                                Text("Shuffle", style = MaterialTheme.typography.caption)
                            }
                        }
                    }

                    if (ui.playbackSpeed != null && ui.playbackSpeed != 1000) {
                        val speed = ui.playbackSpeed!! / 1000.0
                        Surface(
                            color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                "${speed}x",
                                style = MaterialTheme.typography.caption,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Controller Section
        if (ui.hasController && !ui.isLowMemoryDevice) {
            Divider()

            Text("Group Controls", style = MaterialTheme.typography.h6)

            // Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { vm.sendPrevious() },
                    enabled = ui.supportedCommands.contains("previous")
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }

                IconButton(
                    onClick = { vm.sendPlay() },
                    enabled = ui.supportedCommands.contains("play")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }

                IconButton(
                    onClick = { vm.sendPause() },
                    enabled = ui.supportedCommands.contains("pause")
                ) {
                    Icon(Icons.Default.Pause, contentDescription = "Pause")
                }

                IconButton(
                    onClick = { vm.sendStop() },
                    enabled = ui.supportedCommands.contains("stop")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }

                IconButton(
                    onClick = { vm.sendNext() },
                    enabled = ui.supportedCommands.contains("next")
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }

            // Group Volume Control
            if (ui.supportedCommands.contains("volume")) {
                Text("Group Volume", style = MaterialTheme.typography.body1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { vm.setGroupMute(!ui.groupMuted) },
                        enabled = ui.supportedCommands.contains("mute")
                    ) {
                        Icon(
                            if (ui.groupMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (ui.groupMuted) "Unmute" else "Mute"
                        )
                    }

                    Text("${ui.groupVolume}", modifier = Modifier.width(40.dp))

                    Slider(
                        value = ui.groupVolume.toFloat(),
                        onValueChange = { vm.setGroupVolume(it.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Player (Local Device) Volume Control - shown when connected with player role
        if (ui.connected && ui.activeRoles.contains("player") && !ui.isLowMemoryDevice) {
            Divider()

            Text("Local Player Volume", style = MaterialTheme.typography.h6)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { vm.setPlayerMute(!ui.playerMuted) }
                ) {
                    Icon(
                        if (ui.playerMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (ui.playerMuted) "Unmute Player" else "Mute Player"
                    )
                }

                Text("${ui.playerVolume}", modifier = Modifier.width(40.dp))

                Slider(
                    value = ui.playerVolume.toFloat(),
                    onValueChange = { vm.setPlayerVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Debug information - only show in debug builds
        if (ui.connected && ui.activeRoles.contains("player") && !ui.isLowMemoryDevice && BuildConfig.DEBUG) {
            Divider()

            Text("Stream: ${ui.streamDesc.ifBlank { "-" }}")
            Text(
                "Clock: offset=${ui.offsetUs}us drift=${
                    String.format(
                        "%.3f",
                        ui.driftPpm
                    )
                }ppm rtt~${ui.rttUs}us"
            )
            Text("Buffer: queued=${ui.queuedChunks} chunks, ahead~${ui.bufferAheadMs}ms, lateDrops=${ui.lateDrops}")
        }

        // Playout offset knob
        if (ui.connected && ui.activeRoles.contains("player")) {
            Text("Playout offset: ${ui.playoutOffsetMs}ms  (neg = earlier / catch up)")
            Slider(
                value = ui.playoutOffsetMs.toFloat(),
                onValueChange = { vm.setPlayoutOffsetMs(it.toLong()) },
                valueRange = -1000f..1000f,
                steps = 200,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Sendspin Android Player v${BuildConfig.VERSION_NAME} | Opus/PCM",
            style = MaterialTheme.typography.caption
        )
    }
}

@Composable
private fun TrackProgressBar(vm: PlayerViewModel, ui: PlayerViewModel.UiState) {
    // Calculate current progress WITHOUT triggering recompositions
    // Only the progress indicator and text recompose, not the whole UI
    val currentProgress = remember(ui.trackProgress, ui.playbackSpeed, ui.metadataTimestamp, ui.offsetUs) {
        val nowLocalUs = System.nanoTime() / 1000L
        val serverTimeUs = nowLocalUs + ui.offsetUs
        vm.getCurrentTrackPosition(serverTimeUs) ?: 0L
    }

    Column {
        LinearProgressIndicator(
            progress = if (ui.trackDuration != null && ui.trackDuration!! > 0) {
                (currentProgress.toFloat() / ui.trackDuration!!.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(currentProgress),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )

            if (ui.trackDuration != null && ui.trackDuration!! > 0) {
                Text(
                    text = formatDuration(ui.trackDuration!!),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    text = "Live",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}