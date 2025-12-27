package com.mph070770.sendspinandroid

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
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
        val playoutOffsetMs: Long = -330,  // for Android tablet
        // Controller state (group-wide)
        val hasController: Boolean = false,
        val groupVolume: Int = 100,
        val groupMuted: Boolean = false,
        val supportedCommands: Set<String> = emptySet(),
        // Player state (local Android device volume)
        val playerVolume: Int = 100,
        val playerMuted: Boolean = false,
        // Metadata state
        val hasMetadata: Boolean = false,
        val metadataTimestamp: Long? = null,
        val trackTitle: String? = null,
        val trackArtist: String? = null,
        val albumTitle: String? = null,
        val albumArtist: String? = null,
        val trackYear: Int? = null,
        val trackNumber: Int? = null,
        val artworkUrl: String? = null,
        val trackProgress: Long? = null,  // milliseconds
        val trackDuration: Long? = null,  // milliseconds
        val playbackSpeed: Int? = null,   // multiplier * 1000 (e.g., 1000 = 1.0x)
        val repeatMode: String? = null,   // "off", "one", "all"
        val shuffleEnabled: Boolean? = null,
        // Artwork state
        val hasArtwork: Boolean = false,
        val artworkBitmap: Bitmap? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var client: SendspinPcmClient? = null
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        // Initialize with current Android system volume
        updateAndroidVolumeState()
    }

    /**
     * Calculate current track position based on metadata timestamp and playback speed
     */
    fun getCurrentTrackPosition(currentServerTimeUs: Long): Long? {
        val state = _ui.value
        val timestamp = state.metadataTimestamp ?: return null
        val progress = state.trackProgress ?: return null
        val speed = state.playbackSpeed ?: return null
        val duration = state.trackDuration ?: return null

        if (speed == 0) {
            // Paused - return the progress at the time of the metadata update
            return progress
        }

        // Calculate elapsed time in server's time domain
        val elapsedUs = currentServerTimeUs - timestamp
        val elapsedMs = elapsedUs / 1000L

        val speedMultiplier = speed / 1000.0
        val calculatedProgress = progress + (elapsedMs * speedMultiplier).toLong()

        // Clamp to duration (unless duration is 0 which means unlimited/unknown)
        return if (duration != 0L) {
            calculatedProgress.coerceIn(0L, duration)
        } else {
            calculatedProgress.coerceAtLeast(0L)
        }
    }

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

    // Player (local Android device) volume controls
    fun setPlayerVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)

        // Set Android system volume
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val systemVolume = (clamped * maxVolume / 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemVolume, 0)

        // Update UI state
        _ui.value = _ui.value.copy(playerVolume = clamped)
    }

    fun setPlayerMute(muted: Boolean) {
        // Android doesn't have a direct mute for STREAM_MUSIC, so we'll just store the state
        // In a full implementation, you'd set volume to 0 when muted and restore when unmuted
        _ui.value = _ui.value.copy(playerMuted = muted)
        if (muted) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    // Call this periodically or on volume button press to sync UI with Android volume
    fun updateAndroidVolumeState() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (currentVolume * 100 / maxVolume).coerceIn(0, 100)

        _ui.value = _ui.value.copy(playerVolume = volumePercent)
    }
}