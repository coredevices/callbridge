package callbridge

import com.juul.kable.Advertisement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Relay statistics, derived from frame sequence numbers on each direction. */
data class Stats(
    val espToServer: Long = 0, val serverToEsp: Long = 0,
    val lostFromEsp: Long = 0, val lostFromServer: Long = 0,
    val levelUp: Int = 0, val levelDown: Int = 0,   // peak |sample| of last frame, 0..32767
    val esp: EspStats? = null, val callState: String = "idle",
)

sealed interface SessionState {
    data object Idle : SessionState
    data object Scanning : SessionState
    data class Connecting(val name: String) : SessionState
    data class Connected(val name: String) : SessionState
    data class Relaying(val name: String, val server: String) : SessionState
    data class Error(val message: String) : SessionState
}

/** Holds one ESP32 connection and, optionally, a server relay. */
class Session(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    private var ble: BleTransport? = null
    private var server: ServerClient? = null
    private var jobs = mutableListOf<Job>()
    private var lastSeqFromEsp = -1
    private var lastSeqFromServer = -1

    suspend fun connect(adv: Advertisement) {
        val name = adv.name ?: adv.identifier.toString()
        _state.value = SessionState.Connecting(name)
        val t = BleTransport(adv)
        try {
            t.connect()
        } catch (e: Exception) {
            _state.value = SessionState.Error(e.message ?: "connect failed"); return
        }
        ble = t
        _state.value = SessionState.Connected(name)
        jobs += scope.launch {
            t.controlIn().collect { msg ->
                when (msg.firstOrNull()?.toInt()) {
                    Control.CALL_STATE -> _stats.update { it.copy(callState = Control.callStateNames.getOrNull(msg[1].toInt()) ?: "?") }
                    Control.STATS -> EspStats.parse(msg)?.let { s -> _stats.update { it.copy(esp = s) } }
                }
            }
        }
        jobs += scope.launch {
            t.audioIn().collect { raw ->
                val f = Frame.decode(raw) ?: return@collect
                lastSeqFromEsp = account(lastSeqFromEsp, f.seq) { lost -> _stats.update { it.copy(espToServer = it.espToServer + 1, lostFromEsp = it.lostFromEsp + lost, levelUp = peak(f)) } }
                server?.sendAudio(raw)
            }
        }
    }

    suspend fun startRelay(url: String, mode: String, room: String, delayMs: Int) {
        val t = ble ?: return
        val s = ServerClient(url, mode, room, delayMs)
        try {
            s.connect()
        } catch (e: Exception) {
            _state.value = SessionState.Error(e.message ?: "server failed"); return
        }
        server = s
        _state.value = SessionState.Relaying((state.value as? SessionState.Connected)?.name ?: "", url)
        jobs += scope.launch {
            s.audioIn().collect { raw ->
                val f = Frame.decode(raw) ?: return@collect
                lastSeqFromServer = account(lastSeqFromServer, f.seq) { lost -> _stats.update { it.copy(serverToEsp = it.serverToEsp + 1, lostFromServer = it.lostFromServer + lost, levelDown = peak(f)) } }
                t.sendAudio(raw)
            }
        }
    }

    suspend fun sendControl(msg: ByteArray) { ble?.sendControl(msg) }

    suspend fun disconnect() {
        jobs.forEach { it.cancelAndJoin() }; jobs.clear()
        server?.close(); server = null
        ble?.disconnect(); ble = null
        _state.value = SessionState.Idle
        _stats.value = Stats()
        lastSeqFromEsp = -1; lastSeqFromServer = -1
    }

    internal fun accountForTest(last: Int, seq: Int, onFrame: (Long) -> Unit) = account(last, seq, onFrame)

    private inline fun account(last: Int, seq: Int, onFrame: (lost: Long) -> Unit): Int {
        // Forward modular distance; a duplicate or late (backward) frame counts no loss and keeps `last`.
        val d = if (last < 0) 1 else (seq - last) and 0xffff
        val forward = d in 1..32767
        onFrame(if (forward) (d - 1).toLong() else 0L)
        return if (forward) seq else last
    }

    private fun peak(f: Frame): Int = Adpcm.decodeFrame(f).maxOfOrNull { abs(it.toInt()) } ?: 0
}
