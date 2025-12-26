package com.mph070770.sendspinandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

    override fun onStop() {
        super.onStop()
        vm.disconnect()
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
            "Opus/PCM player with controller support. Binary audio chunks type=4 with 8-byte BE timestamp (µs).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}