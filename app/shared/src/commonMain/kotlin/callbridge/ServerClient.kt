package callbridge

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.close
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** WebSocket client to the callbridge server; see docs/protocol.md. */
class ServerClient(private val url: String, private val mode: String, private val room: String, private val delayMs: Int) {
    private val http = HttpClient { install(WebSockets) }
    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null

    /** Connects and completes the hello handshake. Throws on error reply. */
    suspend fun connect() {
        val s = http.webSocketSession(url)
        val hello = buildJsonObject {
            put("type", "hello"); put("mode", mode); put("room", room); put("delayMs", delayMs)
        }
        s.send(WsFrame.Text(hello.toString()))
        val reply = Json.parseToJsonElement((s.incoming.receive() as WsFrame.Text).readText()).jsonObject
        if (reply["type"]?.jsonPrimitive?.content != "ok") {
            s.close(); error("server: ${reply["message"]?.jsonPrimitive?.content}")
        }
        session = s
    }

    /** Audio frames from the server (binary only). Text control messages are dropped for now. */
    fun audioIn(): Flow<ByteArray> =
        session!!.incoming.consumeAsFlow().filterIsInstance<WsFrame.Binary>().map { it.readBytes() }

    suspend fun sendAudio(frame: ByteArray) = session?.send(WsFrame.Binary(true, frame))

    suspend fun close() { session?.close(); session = null; http.close() }
}
