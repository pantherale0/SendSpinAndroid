package com.mph070770.sendspinandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        Text("Sendspin PCM Player", style = MaterialTheme.typography.titleLarge)

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

        Divider()

        Text("Stream: ${ui.streamDesc.ifBlank { "-" }}")
        Text("Clock: offset=${ui.offsetUs}us drift=${ui.driftPpm}ppm rtt~${ui.rttUs}us")
        Text("Buffer: queued=${ui.queuedChunks} chunks, ahead~${ui.bufferAheadMs}ms, lateDrops=${ui.lateDrops}")

        // ✅ Realtime playout offset knob (minimal UI impact)
        Text("Playout offset: ${ui.playoutOffsetMs}ms  (neg = earlier / catch up)")
        Slider(
            value = ui.playoutOffsetMs.toFloat(),
            onValueChange = { vm.setPlayoutOffsetMs(it.toLong()) },
            valueRange = -1000f..1000f,
            steps = 200, // ~10ms increments
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.weight(1f))
        Text(
            "PCM-only MVP. Binary audio chunks type=4 with 8-byte BE timestamp (µs).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
