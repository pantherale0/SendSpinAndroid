package com.mph070770.sendspinandroid

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SendspinPcmClient(
    private val wsUrl: String,
    private val clientId: String,
    private val clientName: String,
    private val onUiUpdate: ((PlayerViewModel.UiState) -> PlayerViewModel.UiState) -> Unit
) {
    private val tag = "SendspinPcmClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    private val isConnected = AtomicBoolean(false)
    private var handshakeComplete: Boolean = false

    private val clock = ClockSync()
    private val jitter = AudioJitterBuffer()
    private val output: PcmAudioOutput = PcmAudioOutput()

    private var timeLoopJob: Job? = null
    private var playoutJob: Job? = null
    private var statsJob: Job? = null

    private var codec: String = ""
    private var sampleRate: Int = 48000
    private var channels: Int = 2
    private var bitDepth: Int = 16

    private var opusDecoder: OpusDecoder? = null

    private var playAtServerUs: Long = Long.MIN_VALUE

    // Realtime playout offset (µs). Negative means "play earlier" (speed up/catch up).
    @Volatile private var playoutOffsetUs: Long = 0L

    fun setPlayoutOffsetMs(ms: Long) {
        val clamped = ms.coerceIn(-1000L, 1000L)
        playoutOffsetUs = clamped * 1000L
        Log.i(tag, "playoutOffset=${clamped}ms")
    }

    suspend fun connect() {
        val req = Request.Builder().url(wsUrl).build()

        onUiUpdate { it.copy(status = "connecting...", connected = false) }

        ws = okHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WS open")
                isConnected.set(true)
                handshakeComplete = false
                onUiUpdate { it.copy(status = "ws_open", connected = true) }
                sendClientHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleBinary(bytes.toByteArray())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(tag, "WS closed code=$code reason=$reason")
                teardown("closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WS failure", t)
                teardown("failure: ${t.message}")
            }
        })
    }

    fun close(reason: String) {
        try { sendClientGoodbye(reason) } catch (_: Throwable) { }
        ws?.close(1000, reason)
        teardown("client_close:$reason")
    }

    private fun teardown(status: String) {
        isConnected.set(false)
        handshakeComplete = false

        timeLoopJob?.cancel(); timeLoopJob = null
        playoutJob?.cancel(); playoutJob = null
        statsJob?.cancel(); statsJob = null

        output.stop()
        jitter.clear()
        opusDecoder = null
        playAtServerUs = Long.MIN_VALUE

        onUiUpdate {
            it.copy(
                status = status,
                connected = false,
                activeRoles = "",
                streamDesc = "",
                playbackState = "",
                groupName = "",
                queuedChunks = 0,
                bufferAheadMs = 0
            )
        }
    }

    private fun sendJson(type: String, payload: JSONObject) {
        val obj = JSONObject().put("type", type).put("payload", payload)
        ws?.send(obj.toString())
    }

    private fun buildPlayerSupportObject(): JSONObject {
        val supportedFormats = JSONArray()
            // Prefer Opus for bandwidth efficiency
            .put(JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 16))
            .put(JSONObject().put("codec", "opus").put("channels", 2).put("sample_rate", 44100).put("bit_depth", 16))
            // Fallback to PCM
            .put(JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 16))
            .put(JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", 44100).put("bit_depth", 16))

        val supportedCommands = JSONArray().put("volume").put("mute")

        return JSONObject()
            .put("supported_formats", supportedFormats)
            .put("buffer_capacity", 2_000_000)
            .put("supported_commands", supportedCommands)
    }

    private fun sendClientHello() {
        val hello = JSONObject()
            .put("client_id", clientId)
            .put("name", clientName)
            .put("version", 1)
            .put("supported_roles", JSONArray().put("player@v1"))

        val playerSupport = buildPlayerSupportObject()
        hello.put("player@v1_support", playerSupport)
        hello.put("player_support", playerSupport)  // Legacy field for compatibility

        sendJson("client/hello", hello)
        onUiUpdate { it.copy(status = "sent client/hello") }
    }

    private fun sendClientGoodbye(reason: String) {
        sendJson("client/goodbye", JSONObject().put("reason", reason))
    }

    private fun sendClientStateSynchronized(volume: Int = 100, muted: Boolean = false) {
        val player = JSONObject().put("state", "synchronized").put("volume", volume).put("muted", muted)
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun sendClientStateError(volume: Int = 100, muted: Boolean = true) {
        val player = JSONObject().put("state", "error").put("volume", volume).put("muted", muted)
        sendJson("client/state", JSONObject().put("player", player))
    }

    private fun startTimeSyncLoop() {
        timeLoopJob?.cancel()
        timeLoopJob = scope.launch {
            while (isActive && isConnected.get()) {
                sendJson("client/time", JSONObject().put("client_transmitted", nowUs()))
                delay(250L)
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isConnected.get()) {
                val snapshot = jitter.snapshot(clock.estimatedOffsetUs())
                Log.i(
                    tag,
                    "stats: offset=${clock.estimatedOffsetUs()}us drift=${String.format("%.3f", clock.estimatedDriftPpm())}ppm " +
                            "rtt~=${clock.estimatedRttUs()}us queued=${snapshot.queuedChunks} ahead~=${snapshot.bufferAheadMs}ms " +
                            "codec=$codec playoutOffset=${playoutOffsetUs / 1000}ms"
                )
                delay(3000L)
            }
        }
    }

    private fun startPlayoutLoop() {
        playoutJob?.cancel()
        playoutJob = scope.launch {
            val targetBufferMs = 200L

            // Normal "too-late" drop once we're running.
            val lateDropUs = 50_000L

            // Make offset changes audible by catching up (dropping) or slowing down (waiting).
            val dropLateUs = 80_000L
            val targetLateUs = 20_000L
            val maxEarlySleepMs = 50L

            // ✅ NEW: if output is stopped and the queue head is very late, we must drop until near-now,
            // otherwise bufferAheadMs stays negative and we never restart (queue grows forever).
            val restartKeepWithinUs = 20_000L      // bring head within 20ms late
            val restartMinAheadMs = -20L           // allow small negative ahead at start
            val restartMinQueued = 1              // if we have any data after dropping, we can start

            while (isActive && isConnected.get()) {
                val offUs = clock.estimatedOffsetUs()
                val snapshot = jitter.snapshot(offUs)

                onUiUpdate {
                    it.copy(
                        queuedChunks = snapshot.queuedChunks,
                        bufferAheadMs = snapshot.bufferAheadMs,
                        lateDrops = snapshot.lateDrops,
                        offsetUs = offUs,
                        driftPpm = clock.estimatedDriftPpm(),
                        rttUs = clock.estimatedRttUs()
                    )
                }

                if (!output.isStarted()) {
                    if (codec != "pcm" && codec != "opus") {
                        delay(50)
                        continue
                    }

                    // ✅ NEW: prevent deadlock when head is late (negative ahead) by dropping late chunks now.
                    if (snapshot.queuedChunks > 0 && snapshot.bufferAheadMs < restartMinAheadMs) {
                        val dropped = jitter.dropWhileLate(nowUs(), offUs, restartKeepWithinUs)
                        if (dropped > 0) {
                            Log.w(tag, "restart-catchup: dropped=$dropped head was late (ahead~${snapshot.bufferAheadMs}ms)")
                        }
                    }

                    val snap2 = jitter.snapshot(offUs)

                    val canStart =
                        (snap2.queuedChunks >= restartMinQueued) &&
                                (snap2.bufferAheadMs >= targetBufferMs || snap2.bufferAheadMs >= restartMinAheadMs)

                    if (canStart) {
                        output.start(sampleRate, channels, bitDepth)

                        // Initialize Opus decoder if needed
                        if (codec == "opus") {
                            opusDecoder = try {
                                OpusDecoder(sampleRate, channels)
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to create Opus decoder", e)
                                sendClientStateError()
                                delay(100)
                                continue
                            }
                        }

                        sendClientStateSynchronized()
                        Log.i(tag, "Audio output started sr=$sampleRate ch=$channels bd=$bitDepth codec=$codec")
                    } else {
                        delay(10)
                        continue
                    }
                }

                val chunk = jitter.pollPlayable(nowUs(), offUs, lateDropUs)
                if (chunk == null) {
                    if (jitter.isEmpty()) {
                        sendClientStateError()
                        output.flushSilence(20)
                    }
                    delay(2)
                    continue
                }

                // Decode if Opus
                val pcmData = if (codec == "opus") {
                    opusDecoder?.decode(chunk.pcmData) ?: ByteArray(0)
                } else {
                    chunk.pcmData
                }

                if (pcmData.isEmpty()) {
                    Log.w(tag, "Empty PCM data after decode")
                    continue
                }

                val effectiveServerTsUs =
                    if (playAtServerUs != Long.MIN_VALUE) maxOf(chunk.serverTimestampUs, playAtServerUs)
                    else chunk.serverTimestampUs

                val localPlayUs = (effectiveServerTsUs - offUs) + playoutOffsetUs
                val now = nowUs()
                val earlyUs = localPlayUs - now

                // If we're behind by a lot, drop chunks to catch up (audible effect).
                if (earlyUs < -dropLateUs) {
                    var dropped = 1
                    while (true) {
                        val next = jitter.pollPlayable(nowUs(), offUs, Long.MAX_VALUE) ?: break
                        val nextPcm = if (codec == "opus") {
                            opusDecoder?.decode(next.pcmData) ?: ByteArray(0)
                        } else {
                            next.pcmData
                        }

                        val nextLocalPlayUs = (next.serverTimestampUs - offUs) + playoutOffsetUs
                        val nextEarlyUs = nextLocalPlayUs - nowUs()
                        dropped++
                        if (nextEarlyUs >= -targetLateUs) {
                            if (nextPcm.isNotEmpty()) {
                                output.writePcm(nextPcm)
                            }
                            break
                        }
                    }
                    Log.w(tag, "catch-up: late=${(-earlyUs) / 1000}ms dropped=$dropped playoutOffset=${playoutOffsetUs / 1000}ms")
                    continue
                }

                if (earlyUs > 5_000) {
                    delay((earlyUs / 1000).coerceAtMost(maxEarlySleepMs))
                }

                output.writePcm(pcmData)
            }
        }
    }

    private fun handleText(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            when (type) {
                "server/hello" -> {
                    handshakeComplete = true
                    val activeRoles = payload.optJSONArray("active_roles")?.let { arr ->
                        (0 until arr.length()).joinToString(",") { arr.getString(it) }
                    } ?: ""

                    onUiUpdate { it.copy(status = "server/hello", activeRoles = activeRoles) }

                    startTimeSyncLoop()
                    startPlayoutLoop()
                    startStatsLoop()
                    sendClientStateSynchronized()
                }

                "server/time" -> {
                    val clientTx = payload.getLong("client_transmitted")
                    val sRecv = payload.getLong("server_received")
                    val sTx = payload.getLong("server_transmitted")
                    val clientRx = nowUs()
                    clock.onServerTime(clientTx, clientRx, sRecv, sTx)
                }

                "stream/start" -> {
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        codec = player.optString("codec", codec)
                        sampleRate = player.optInt("sample_rate", sampleRate)
                        channels = player.optInt("channels", channels)
                        bitDepth = player.optInt("bit_depth", bitDepth)
                        playAtServerUs =
                            if (player.has("play_at")) player.optLong("play_at", Long.MIN_VALUE) else Long.MIN_VALUE

                        onUiUpdate {
                            it.copy(
                                status = "stream/start",
                                streamDesc = "$codec ${sampleRate}Hz ${channels}ch ${bitDepth}bit"
                            )
                        }

                        output.stop()
                        jitter.clear()
                        opusDecoder = null
                    }
                }

                "stream/clear" -> {
                    jitter.clear()
                    opusDecoder?.reset()
                }

                "stream/end" -> {
                    output.stop()
                    jitter.clear()
                    opusDecoder = null
                    playAtServerUs = Long.MIN_VALUE
                    onUiUpdate { it.copy(status = "stream/end", streamDesc = "") }
                }

                "group/update" -> {
                    val playbackState = payload.optString("playback_state", "")
                    val groupName = payload.optString("group_name", "")
                    onUiUpdate { it.copy(playbackState = playbackState, groupName = groupName) }
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Bad JSON: ${t.message}")
        }
    }

    private fun handleBinary(data: ByteArray) {
        if (!handshakeComplete) return
        if (data.isEmpty()) return

        val type = data[0].toInt() and 0xFF
        if (type != 4) return
        if (codec != "pcm" && codec != "opus") return
        if (data.size < 1 + 8 + 1) return

        val tsServerUs = readInt64BE(data, 1)
        val encodedData = data.copyOfRange(1 + 8, data.size)

        jitter.offer(tsServerUs, encodedData, clock.estimatedOffsetUs(), nowUs())
    }

    private fun readInt64BE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFFL)
        return v
    }

    private fun nowUs(): Long = System.nanoTime() / 1000L
}