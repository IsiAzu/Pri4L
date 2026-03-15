package com.pri4l.hub

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

class RosbridgeClient {

    val state = mutableStateOf(ConnectionState.DISCONNECTED)
    var messageCount = mutableStateOf(0)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val subscriptions = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var reconnectHost: String? = null
    private var reconnectPort: Int? = null
    private var reconnectAttempt = 0

    fun connect(host: String, port: Int = 9090) {
        reconnectHost = host
        reconnectPort = port
        reconnectAttempt = 0
        doConnect(host, port)
    }

    private fun doConnect(host: String, port: Int) {
        ws?.cancel()
        state.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    state.value = ConnectionState.CONNECTED
                    reconnectAttempt = 0
                    // Clear advertised topics so they get re-advertised on next publish
                    advertisedTopics.clear()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("op") == "publish") {
                    mainHandler.post { messageCount.value++ }
                    val topic = json.optString("topic")
                    val msg = json.optJSONObject("msg")
                    if (msg != null) {
                        subscriptions[topic]?.let { callback ->
                            mainHandler.post { callback(msg) }
                        }
                    }
                } else if (json.optString("op") == "service_response") {
                    // Route service responses via a special key
                    val id = json.optString("id", "")
                    subscriptions["__service__$id"]?.let { callback ->
                        mainHandler.post { callback(json) }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    state.value = ConnectionState.ERROR
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    state.value = ConnectionState.DISCONNECTED
                }
            }
        })
    }

    private fun scheduleReconnect() {
        val host = reconnectHost ?: return
        val port = reconnectPort ?: return
        reconnectAttempt++
        val delayMs = minOf(1000L * (1 shl minOf(reconnectAttempt, 3)), 10_000L)
        mainHandler.postDelayed({ doConnect(host, port) }, delayMs)
    }

    fun disconnect() {
        reconnectHost = null
        reconnectPort = null
        mainHandler.removeCallbacksAndMessages(null)
        ws?.close(1000, "user disconnect")
        ws = null
        advertisedTopics.clear()
        state.value = ConnectionState.DISCONNECTED
    }

    private val advertisedTopics = mutableSetOf<String>()

    fun publish(topic: String, type: String, msg: JSONObject) {
        // Rosbridge requires topics to be advertised before publishing
        if (topic !in advertisedTopics) {
            val adJson = JSONObject().apply {
                put("op", "advertise")
                put("topic", topic)
                put("type", type)
            }
            ws?.send(adJson.toString())
            advertisedTopics.add(topic)
        }

        val json = JSONObject().apply {
            put("op", "publish")
            put("topic", topic)
            put("type", type)
            put("msg", msg)
        }
        try {
            ws?.send(json.toString())
        } catch (_: Exception) {
            // Drop frame silently — reconnect will handle recovery
        }
    }

    fun subscribe(topic: String, type: String, throttleMs: Int = 0, callback: (JSONObject) -> Unit) {
        subscriptions[topic] = callback
        val json = JSONObject().apply {
            put("op", "subscribe")
            put("topic", topic)
            put("type", type)
            if (throttleMs > 0) put("throttle_rate", throttleMs)
        }
        ws?.send(json.toString())
    }

    fun callService(service: String, type: String, id: String = "", callback: (JSONObject) -> Unit) {
        if (id.isNotEmpty()) {
            subscriptions["__service__$id"] = callback
        }
        val json = JSONObject().apply {
            put("op", "call_service")
            put("service", service)
            put("type", type)
            if (id.isNotEmpty()) put("id", id)
        }
        ws?.send(json.toString())
    }
}
