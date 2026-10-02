package callbridge

import com.juul.kable.Advertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.time.TimeSource

/** Relay statistics, derived from frame sequence numbers on each direction. */
data class Stats(
    val espToServer: Long = 0, val serverToEsp: Long = 0,
    val lostFromEsp: Long = 0, val lostFromServer: Long = 0,
    val levelUp: Int = 0, val levelDown: Int = 0,   // peak |sample| of last frame, 0..32767
    val esp: EspStats? = null, val callState: String = "idle",
)

/** Per-hop round-trip times in ms; null = not measured yet. See docs/protocol.md "Latency". */
data class Latency(val scoRtMs: Double? = null, val bleRtMs: Double? = null, val wsRtMs: Double? = null) {
    val totalRtMs: Double? get() = if (scoRtMs == null && bleRtMs == null && wsRtMs == null) null
        else (scoRtMs ?: 0.0) + (bleRtMs ?: 0.0) + (wsRtMs ?: 0.0)
}

sealed interface BleState {
    data object Idle : BleState
    data class Connecting(val name: String) : BleState
    data class Connected(val name: String) : BleState
    data class Error(val message: String) : BleState
}

sealed interface ServerState {
    data object Idle : ServerState
    data object Connecting : ServerState
    data class Connected(val url: String) : ServerState
    data class Error(val message: String) : ServerState
}

/**
 * One ESP32 BLE link and one server link, independently connectable. Audio relays only while both
 * are up; latency reporting needs only the server.
 */
class Session(private val scope: CoroutineScope) {
    private val _ble = MutableStateFlow<BleState>(BleState.Idle)
    val ble: StateFlow<BleState> = _ble
    private val _server = MutableStateFlow<ServerState>(ServerState.Idle)
    val server: StateFlow<ServerState> = _server
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats
    private val _latency = MutableStateFlow(Latency())
    val latency: StateFlow<Latency> = _latency

    /** Platform SCO test (Android) stores its result here. */
    fun setScoRoundTrip(ms: Double?) = _latency.update { it.copy(scoRtMs = ms) }

    private val lock = Mutex() // serializes connect/disconnect on both sides
    private var bleTransport: BleTransport? = null
    private var serverClient: ServerClient? = null
    private val bleJobs = mutableListOf<Job>()
    private val serverJobs = mutableListOf<Job>()
    private var lastSeqFromEsp = -1
    private var lastSeqFromServer = -1

    private val clock = TimeSource.Monotonic.markNow()
    private class PendingPing(val token: Int, val sentMs: Double)
    private var pendingPing: PendingPing? = null // one immutable record: token + send time read together

    suspend fun connectBle(adv: Advertisement) = lock.withLock {
        if (bleTransport != null) return@withLock
        val name = adv.name ?: adv.identifier.toString()
        _ble.value = BleState.Connecting(name)
        val t = BleTransport(adv)
        try {
            t.connect()
        } catch (e: CancellationException) {
            runCatching { t.disconnect() }; _ble.value = BleState.Idle; throw e
        } catch (e: Exception) {
            runCatching { t.disconnect() }; _ble.value = BleState.Error(e.message ?: "connect failed"); return@withLock
        }
        bleTransport = t
        lastSeqFromEsp = -1
        _ble.value = BleState.Connected(name)
        bleJobs += guardedBle(t) {
            t.controlIn().collect { msg ->
                when (msg.firstOrNull()?.toInt()) {
                    Control.CALL_STATE -> _stats.update { it.copy(callState = Control.callStateNames.getOrNull(msg[1].toInt()) ?: "?") }
                    Control.STATS -> EspStats.parse(msg)?.let { s -> _stats.update { it.copy(esp = s) } }
                    Control.PONG -> {
                        val p = pendingPing
                        if (p != null && Control.pongToken(msg) == p.token) _latency.update { it.copy(bleRtMs = nowMs() - p.sentMs) }
                    }
                }
            }
        }
        bleJobs += guardedBle(t) {
            t.audioIn().collect { raw ->
                val f = Frame.decode(raw) ?: return@collect
                lastSeqFromEsp = account(lastSeqFromEsp, f.seq) { lost -> _stats.update { it.copy(espToServer = it.espToServer + 1, lostFromEsp = it.lostFromEsp + lost, levelUp = peak(f)) } }
                serverClient?.sendAudio(raw)
            }
        }
        bleJobs += guardedBle(t) {
            // BLE ping every 2 s; stays null until the firmware answers (GATT milestone).
            var token = 0
            while (true) {
                delay(2_000)
                token++
                pendingPing = PendingPing(token, nowMs())
                try { t.sendControl(Control.ping(token)) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    suspend fun connectServer(url: String, mode: String, room: String, delayMs: Int) = lock.withLock {
        if (serverClient != null) return@withLock
        _server.value = ServerState.Connecting
        val name = (ble.value as? BleState.Connected)?.name ?: "app"
        val s = ServerClient(url, mode, room, delayMs, name = name)
        try {
            s.connect()
        } catch (e: CancellationException) {
            _server.value = ServerState.Idle; throw e
        } catch (e: Exception) {
            _server.value = ServerState.Error(e.message ?: "server failed"); return@withLock
        }
        serverClient = s
        lastSeqFromServer = -1
        _server.value = ServerState.Connected(url)
        serverJobs += guardedServer(s) {
            s.audioIn().collect { raw ->
                val f = Frame.decode(raw) ?: return@collect
                lastSeqFromServer = account(lastSeqFromServer, f.seq) { lost -> _stats.update { it.copy(serverToEsp = it.serverToEsp + 1, lostFromServer = it.lostFromServer + lost, levelDown = peak(f)) } }
                bleTransport?.sendAudio(raw)
            }
            error("server closed the connection")
        }
        serverJobs += guardedServer(s) {
            // WS ping + per-hop latency report every 2 s.
            while (true) {
                s.ping()
                delay(1_000)
                _latency.update { it.copy(wsRtMs = s.rttMs.value) }
                val l = _latency.value
                s.reportLatency(l.scoRtMs, l.bleRtMs, l.wsRtMs)
                delay(1_000)
            }
        }
    }

    suspend fun sendControl(msg: ByteArray) { bleTransport?.sendControl(msg) }

    suspend fun disconnectBle() = lock.withLock { teardownBle(BleState.Idle) }
    suspend fun disconnectServer() = lock.withLock { teardownServer(ServerState.Idle) }

    private suspend fun teardownBle(final: BleState) {
        val jobs = bleJobs.toList(); bleJobs.clear()
        jobs.forEach { it.cancel() }; jobs.forEach { it.join() }
        bleTransport?.let { runCatching { it.disconnect() } }; bleTransport = null
        pendingPing = null
        _stats.update { Stats() }
        _latency.update { it.copy(bleRtMs = null) }
        _ble.value = final
    }

    private suspend fun teardownServer(final: ServerState) {
        val jobs = serverJobs.toList(); serverJobs.clear()
        jobs.forEach { it.cancel() }; jobs.forEach { it.join() }
        serverClient?.let { runCatching { it.close() } }; serverClient = null
        _latency.update { it.copy(wsRtMs = null) }
        _server.value = final
    }

    /**
     * A failure (not cancellation) in a BLE job tears that side down into Error instead of killing
     * the process. Only the owning transport is torn down, so a late failure from an old link
     * cannot kill a newer one.
     */
    private fun guardedBle(owner: BleTransport, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            scope.launch { lock.withLock { if (bleTransport === owner) teardownBle(BleState.Error(e.message ?: e.toString())) } }
        }
    }

    private fun guardedServer(owner: ServerClient, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            scope.launch { lock.withLock { if (serverClient === owner) teardownServer(ServerState.Error(e.message ?: e.toString())) } }
        }
    }

    private fun nowMs(): Double = clock.elapsedNow().inWholeMicroseconds / 1000.0

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
