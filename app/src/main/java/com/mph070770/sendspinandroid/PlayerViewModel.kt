package com.mph070770.sendspinandroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val wsUrl: String = "ws://192.168.1.137:8927/sendspin",
        val clientId: String = "android-player-1",
        val clientName: String = "Android Player",
        val connected: Boolean = false,
        val status: String = "idle",
        val activeRoles: String = "",
        val playbackState: String = "",
        val groupName: String = "",
        val streamDesc: String = "",
        val offsetUs: Long = 0,
        val driftPpm: Double = 0.0,
        val rttUs: Long = 0,
        val queuedChunks: Int = 0,
        val bufferAheadMs: Long = 0,
        val lateDrops: Long = 0,
        val playoutOffsetMs: Long = 0,
        // Controller state (group-wide)
        val hasController: Boolean = false,
        val groupVolume: Int = 100,
        val groupMuted: Boolean = false,
        val supportedCommands: Set<String> = emptySet(),
        // Player state (local device)
        val playerVolume: Int = 100,
        val playerMuted: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var client: SendspinPcmClient? = null

    fun connect(wsUrl: String, clientId: String, clientName: String) {
        disconnect()

        _ui.value = _ui.value.copy(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            status = "connecting...",
            connected = true
        )

        client = SendspinPcmClient(
            wsUrl = wsUrl,
            clientId = clientId,
            clientName = clientName,
            onUiUpdate = { patch -> _ui.value = patch(_ui.value) }
        ).also { it.setPlayoutOffsetMs(_ui.value.playoutOffsetMs) }

        viewModelScope.launch {
            client?.connect()
        }
    }

    fun disconnect() {
        client?.close("user_disconnect")
        client = null
        _ui.value = _ui.value.copy(connected = false, status = "disconnected")
    }

    fun setPlayoutOffsetMs(ms: Long) {
        val clamped = ms.coerceIn(-1000L, 1000L)
        _ui.value = _ui.value.copy(playoutOffsetMs = clamped)
        client?.setPlayoutOffsetMs(clamped)
    }

    // Controller commands
    fun sendPlay() = client?.sendControllerCommand("play")
    fun sendPause() = client?.sendControllerCommand("pause")
    fun sendStop() = client?.sendControllerCommand("stop")
    fun sendNext() = client?.sendControllerCommand("next")
    fun sendPrevious() = client?.sendControllerCommand("previous")

    fun setGroupVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        client?.sendControllerCommand("volume", volume = clamped)
    }

    fun setGroupMute(muted: Boolean) {
        client?.sendControllerCommand("mute", mute = muted)
    }

    // Player (local device) volume controls
    fun setPlayerVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        _ui.value = _ui.value.copy(playerVolume = clamped)
        client?.setPlayerVolume(clamped)
    }

    fun setPlayerMute(muted: Boolean) {
        _ui.value = _ui.value.copy(playerMuted = muted)
        client?.setPlayerMute(muted)
    }
}