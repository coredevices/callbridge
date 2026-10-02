package callbridge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/** WebSocket client to the callbridge server; see docs/protocol.md. */
class ServerClient(
    private val url: String, private val mode: String, private val room: String, private val delayMs: Int,
    private val name: String = "",
) {
    private val http = HttpClient { install(WebSockets) }
    private var session: DefaultClientWebSocketSession? = null
    private val clock = TimeSource.Monotonic.markNow()

    private val _rttMs = MutableStateFlow<Double?>(null)
    /** Last measured app ⇄ server round trip, from ping/pong. */
    val rttMs: StateFlow<Double?> = _rttMs

    /** server clock - local monotonic clock, in ms, from the last pong (server_t vs midpoint of the ping). */
    @Volatile var clockOffsetMs: Double? = null
        private set
    /** Server clock estimate of "now" in ms, or null before the first pong. */
    fun serverNowMs(): Double? = clockOffsetMs?.let { nowMs() + it }

    private val _burstDetected = MutableStateFlow<Pair<Double, Double>?>(null) // (t_server, up_ms), latest
    /** Set when the server's tone detector sees the burst we armed: (server detect time, one-way up ms). */
    val burstDetected: StateFlow<Pair<Double, Double>?> = _burstDetected

    /** Connects and completes the hello handshake. Throws on error reply or 5 s timeout. */
    suspend fun connect() {
        var s: DefaultClientWebSocketSession? = null
        try {
            withTimeout(5_000) {
                val ws = http.webSocketSession(url)
                s = ws
                val hello = buildJsonObject {
                    put("type", "hello"); put("mode", mode); put("room", room); put("delayMs", delayMs); put("name", name)
                }
                ws.send(WsFrame.Text(hello.toString()))
                val reply = Json.parseToJsonElement((ws.incoming.receive() as WsFrame.Text).readText()).jsonObject
                if (reply["type"]?.jsonPrimitive?.content != "ok") error("server: ${reply["message"]?.jsonPrimitive?.content}")
            }
        } catch (e: Throwable) {
            runCatching { s?.close() }; http.close(); throw e
        }
        session = s
    }

    /** Audio frames from the server. Text control messages (pong) are handled inline. */
    fun audioIn(): Flow<ByteArray> = flow {
        val s = session ?: return@flow
        for (f in s.incoming) {
            when (f) {
                is WsFrame.Binary -> emit(f.readBytes())
                is WsFrame.Text -> handleText(f.readText())
                else -> {}
            }
        }
    }

    private fun handleText(txt: String) {
        // malformed control messages must not end audio reception
        runCatching {
            val o = Json.parseToJsonElement(txt).jsonObject
            when (o["type"]?.jsonPrimitive?.content) {
                "pong" -> {
                    val t = o["t"]?.jsonPrimitive?.doubleOrNull ?: return
                    val now = nowMs()
                    _rttMs.value = now - t
                    o["server_t"]?.jsonPrimitive?.doubleOrNull?.let { st -> clockOffsetMs = st - (t + now) / 2 }
                }
                "burst_detected" -> {
                    val ts = o["t_server"]?.jsonPrimitive?.doubleOrNull ?: return
                    val up = o["up_ms"]?.jsonPrimitive?.doubleOrNull ?: return
                    _burstDetected.value = ts to up
                }
            }
        }
    }

    private fun nowMs(): Double = clock.elapsedNow().inWholeMicroseconds / 1000.0

    suspend fun sendAudio(frame: ByteArray) = session?.send(WsFrame.Binary(true, frame))

    suspend fun ping() = session?.send(WsFrame.Text(buildJsonObject { put("type", "ping"); put("t", nowMs()) }.toString()))

    /** Reports per-hop round trips (null = unmeasured) for the server's latency page. */
    suspend fun reportLatency(scoRtMs: Double?, bleRtMs: Double?, wsRtMs: Double?) = session?.send(WsFrame.Text(buildJsonObject {
        put("type", "latency")
        put("sco_rt_ms", scoRtMs?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ble_rt_ms", bleRtMs?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ws_rt_ms", wsRtMs?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()))

    fun clearBurst() { _burstDetected.value = null }

    /** Arms the server's detector; [tServerMs] is when the burst left the phone, on the server clock. */
    suspend fun sendBurst(tServerMs: Double) {
        _burstDetected.value = null
        session?.send(WsFrame.Text(buildJsonObject { put("type", "burst"); put("t_server", tServerMs) }.toString()))
    }

    suspend fun sendE2E(fullRtMs: Double?, downMs: Double?) = session?.send(WsFrame.Text(buildJsonObject {
        put("type", "e2e")
        put("full_rt_ms", fullRtMs?.let { JsonPrimitive(it) } ?: JsonNull)
        put("down_ms", downMs?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()))

    suspend fun close() { session?.close(); session = null; http.close() }
}
