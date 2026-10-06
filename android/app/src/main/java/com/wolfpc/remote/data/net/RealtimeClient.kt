package com.wolfpc.remote.data.net

import android.util.Log
import com.wolfpc.remote.data.SessionStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Supabase Realtime over its Phoenix websocket protocol (PRD §21).
 *
 * Only this user's own rows are watched, filtered to their pc. Realtime runs
 * the same RLS policies as a select, so the filter is a bandwidth optimisation
 * and the policy is the actual boundary — a forged filter would still return
 * nothing.
 *
 * This is written directly against the websocket rather than through a client
 * library to keep the app's dependency surface small; the protocol is four
 * message types.
 */
class RealtimeClient(
    private val store: SessionStore,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "WolfRealtime"
        const val HEARTBEAT_MS = 25_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val reference = AtomicInteger(1)
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnectAttempt = 0
    private var closedByUs = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Emits the table name whose row changed; the caller re-reads it. */
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val changes: SharedFlow<String> = _changes

    fun start(pcId: String) {
        closedByUs = false
        scope.launch { connect(pcId) }
    }

    fun stop() {
        closedByUs = true
        heartbeat?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    private suspend fun connect(pcId: String) {
        val snapshot = store.snapshot()
        if (snapshot.supabaseUrl.isBlank() || snapshot.accessToken.isBlank()) return

        val wsUrl = snapshot.supabaseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=${snapshot.anonKey}&vsn=1.0.0"

        val request = Request.Builder().url(wsUrl).build()

        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "connected")
                reconnectAttempt = 0
                _connected.value = true
                join(webSocket, pcId, snapshot.accessToken)
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "websocket failed: ${t.message}")
                _connected.value = false
                heartbeat?.cancel()
                scheduleReconnect(pcId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                heartbeat?.cancel()
                if (!closedByUs) scheduleReconnect(pcId)
            }
        })
    }

    private fun scheduleReconnect(pcId: String) {
        if (closedByUs) return
        // Capped exponential backoff. A phone that has just lost Wi-Fi should
        // not spend its battery reconnecting once a second; the UI degrades to
        // polling in the meantime.
        val delayMs = minOf(1000L shl minOf(reconnectAttempt, 5), 30_000L)
        reconnectAttempt++
        scope.launch {
            delay(delayMs)
            connect(pcId)
        }
    }

    private fun join(webSocket: WebSocket, pcId: String, accessToken: String) {
        val payload = buildJsonObject {
            put("config", buildJsonObject {
                put("broadcast", buildJsonObject { put("self", false) })
                put("presence", buildJsonObject { put("key", "") })
                put("postgres_changes", buildJsonArray {
                    add(buildJsonObject {
                        put("event", "*")
                        put("schema", "public")
                        put("table", "pc_status")
                        put("filter", "pc_id=eq.$pcId")
                    })
                    add(buildJsonObject {
                        put("event", "*")
                        put("schema", "public")
                        put("table", "commands")
                        put("filter", "pc_id=eq.$pcId")
                    })
                    // Phase 8. A login challenge lives 60 seconds and the user
                    // is standing at a login screen for all of it, so this is
                    // the one subscription where latency is the whole point.
                    add(buildJsonObject {
                        put("event", "*")
                        put("schema", "public")
                        put("table", "login_challenges")
                        put("filter", "pc_id=eq.$pcId")
                    })
                })
                put("private", true)
            })
            // The user's JWT is what Realtime evaluates RLS against. Without
            // it the channel joins as anon and sees nothing.
            put("access_token", accessToken)
        }

        send(webSocket, "realtime:wolfpc:$pcId", "phx_join", payload)
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                send(webSocket, "phoenix", "heartbeat", buildJsonObject { })
            }
        }
    }

    private fun send(webSocket: WebSocket, topic: String, event: String, payload: JsonObject) {
        val frame = buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", reference.getAndIncrement().toString())
        }
        webSocket.send(frame.toString())
    }

    private fun handle(text: String) {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val event = message["event"]?.jsonPrimitive?.content ?: return
        if (event != "postgres_changes") return

        val table = message["payload"]?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("table")?.jsonPrimitive?.content
            ?: return

        _changes.tryEmit(table)
    }
}
