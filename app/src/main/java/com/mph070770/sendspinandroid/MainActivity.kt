package com.mph070770.sendspinandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(vm)
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
private fun PlayerScreen(vm: PlayerViewModel) {
    val ui by vm.ui.collectAsState()

    var wsUrl by remember { mutableStateOf(ui.wsUrl) }
    var clientId by remember { mutableStateOf(ui.clientId) }
    var clientName by remember { mutableStateOf(ui.clientName) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sendspin Player", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = wsUrl,
            onValueChange = { wsUrl = it },
            label = { Text("WebSocket URL (ws://host:port/sendspin)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("client_id") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = clientName,
                onValueChange = { clientName = it },
                label = { Text("name") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.connect(wsUrl.trim(), clientId.trim(), clientName.trim()) },
                enabled = !ui.connected
            ) { Text("Connect") }

            OutlinedButton(
                onClick = { vm.disconnect() },
                enabled = ui.connected
            ) { Text("Disconnect") }
        }

        Divider()

        Text("Status: ${ui.status}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Roles: ${ui.activeRoles.ifBlank { "-" }}")
        Text("Group: ${ui.groupName.ifBlank { "-" }} (${ui.playbackState.ifBlank { "-" }})")

        // Metadata Display with Album Art
        if (ui.hasMetadata && ui.trackTitle != null) {
            Divider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = "No artwork",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Track info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Now Playing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = ui.trackTitle ?: "Unknown Track",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (ui.trackArtist != null) {
                            Text(
                                text = ui.trackArtist!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (ui.albumTitle != null) {
                            Text(
                                text = ui.albumTitle!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (ui.trackNumber != null) {
                                Text(
                                    text = "Track ${ui.trackNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    when (ui.repeatMode) {
                                        "one" -> "Repeat One"
                                        "all" -> "Repeat All"
                                        else -> "Repeat"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (ui.repeatMode == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }

                    if (ui.shuffleEnabled == true) {
                        AssistChip(
                            onClick = { },
                            label = { Text("Shuffle", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }

                    if (ui.playbackSpeed != null && ui.playbackSpeed != 1000) {
                        val speed = ui.playbackSpeed!! / 1000.0
                        AssistChip(
                            onClick = { },
                            label = { Text("${speed}x", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        // Controller Section
        if (ui.hasController) {
            Divider()

            Text("Group Controls", style = MaterialTheme.typography.titleMedium)

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
                Text("Group Volume", style = MaterialTheme.typography.bodyMedium)
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
        if (ui.connected && ui.activeRoles.contains("player")) {
            Divider()

            Text("Local Player Volume", style = MaterialTheme.typography.titleMedium)
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

        Divider()

        Text("Stream: ${ui.streamDesc.ifBlank { "-" }}")
        Text("Clock: offset=${ui.offsetUs}us drift=${String.format("%.3f", ui.driftPpm)}ppm rtt~${ui.rttUs}us")
        Text("Buffer: queued=${ui.queuedChunks} chunks, ahead~${ui.bufferAheadMs}ms, lateDrops=${ui.lateDrops}")

        // Playout offset knob
        Text("Playout offset: ${ui.playoutOffsetMs}ms  (neg = earlier / catch up)")
        Slider(
            value = ui.playoutOffsetMs.toFloat(),
            onValueChange = { vm.setPlayoutOffsetMs(it.toLong()) },
            valueRange = -1000f..1000f,
            steps = 200,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.weight(1f))
        Text(
            "Sendspin Android Player v${BuildConfig.VERSION_NAME} | Opus/PCM with controller, metadata & artwork",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TrackProgressBar(vm: PlayerViewModel, ui: PlayerViewModel.UiState) {
    // Update progress every 200ms for smooth display
    var currentProgress by remember { mutableStateOf(0L) }

    LaunchedEffect(ui.trackProgress, ui.playbackSpeed, ui.metadataTimestamp, ui.offsetUs) {
        while (true) {
            // Calculate current server time using clock offset
            val nowLocalUs = System.nanoTime() / 1000L
            val serverTimeUs = nowLocalUs + ui.offsetUs
            currentProgress = vm.getCurrentTrackPosition(serverTimeUs) ?: 0L
            delay(200)
        }
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (ui.trackDuration != null && ui.trackDuration!! > 0) {
                Text(
                    text = formatDuration(ui.trackDuration!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Live",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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