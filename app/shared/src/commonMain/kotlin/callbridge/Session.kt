package callbridge

import com.juul.kable.Advertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.time.TimeSource

/** Relay statistics, derived from frame sequence numbers on each direction. */
data class Stats(
    val espToServer: Long = 0, val serverToEsp: Long = 0,
    val lostFromEsp: Long = 0, val lostFromServer: Long = 0,
    val levelUp: Int = 0, val levelDown: Int = 0,   // peak |sample| of last frame, 0..32767
    val downDropped: Long = 0,                       // server frames dropped because BLE could not keep up
    val esp: EspStats? = null, val callState: String = "idle",
    val bleKbps: Double = 0.0, val bleFramesPerSec: Int = 0,   // measured on audio_tx over the last second
    val linkFramesRead: Long = 0, val maxWsSendMs: Double = 0.0, // diagnostics: link reader count, slowest WS send last second
    val readBlockedMsLastSec: Long = 0,                           // ms of the last second the link reader waited for data
)

/**
 * Per-hop round-trip times in ms from pings, plus end-to-end values measured with a real tone burst
 * (phone → server one-way, server → phone one-way, full loop). null = not measured yet.
 */
data class Latency(
    val scoRtMs: Double? = null, val bleRtMs: Double? = null, val wsRtMs: Double? = null,
    val upMs: Double? = null, val downMs: Double? = null, val fullRtMs: Double? = null,
) {
    val totalRtMs: Double? get() = if (scoRtMs == null && bleRtMs == null && wsRtMs == null) null
        else (scoRtMs ?: 0.0) + (bleRtMs ?: 0.0) + (wsRtMs ?: 0.0)
}

/** Result of one end-to-end tone test. */
data class E2EResult(val upMs: Double?, val downMs: Double?, val fullRtMs: Double?, val message: String)

sealed interface BleState {
    data object Idle : BleState
    data class Connecting(val name: String) : BleState
    data class Connected(val name: String, val link: String = "ble") : BleState
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
@OptIn(ExperimentalAtomicApi::class)
class Session(private val scope: CoroutineScope) {
    private val _ble = MutableStateFlow<BleState>(BleState.Idle)
    val ble: StateFlow<BleState> = _ble
    private val _server = MutableStateFlow<ServerState>(ServerState.Idle)
    val server: StateFlow<ServerState> = _server
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats
    private val _latency = MutableStateFlow(Latency())
    val latency: StateFlow<Latency> = _latency

    /** What the user wants loopback to be outside of tests (the firmware boots with it on). */
    @Volatile var loopbackWanted: Boolean = true

    /** Platform SCO test (Android) stores its result here. */
    fun setScoRoundTrip(ms: Double?) = _latency.update { it.copy(scoRtMs = ms) }

    private val lock = Mutex() // serializes connect/disconnect on both sides
    private var bleTransport: Link? = null   // the data link in use: SPP (Android) or BLE GATT
    /** Latest-wins queue towards the ESP32: BLE writes are slow and serialized with control writes, so
     *  stale audio is dropped here rather than queued behind everything else. */
    private val downlink = Channel<ByteArray>(capacity = 6, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Frames per link write towards the ESP32 (SPP packet budget is shared with the uplink). */
    var downlinkBatch = 3
    /** Test aid: frames from the server are sent straight back to the server instead of to the ESP32. */
    @Volatile var appEcho = false

    /** Keepalive period towards the ESP32 over SPP; 0 disables. Applied at connect time. */
    var keepaliveMs = 0L   // costs air time; only ever helped SPP, which is no longer the default
    private var serverClient: ServerClient? = null
    private val bleJobs = mutableListOf<Job>()
    private val serverJobs = mutableListOf<Job>()
    private var lastSeqFromEsp = -1
    private var lastSeqFromServer = -1

    private val clock = TimeSource.Monotonic.markNow()
    private class PendingPing(val token: Int, val sentMs: Double)
    private var pendingPing: PendingPing? = null // one immutable record: token + send time read together
    /** BLE MTU negotiated on connect (Android only), for diagnostics. */
    private val _mtu = MutableStateFlow<Int?>(null)
    val mtu: StateFlow<Int?> = _mtu

    /** When false, connectBle uses the BLE GATT link even where SPP is available (for comparison tests). */
    var preferSpp = false   // BLE carries the stream fine during a call; SPP is limited to ~2 kB/s by 1-slot packets

    /** Connects via a BLE advertisement: SPP to the same address when the platform has it, else GATT. */
    suspend fun connectBle(adv: Advertisement) = lock.withLock {
        if (bleTransport != null) return@withLock
        val name = adv.name ?: adv.identifier.toString()
        _ble.value = BleState.Connecting(name)
        // BLE is the discovery mechanism; on Android the data goes over Classic SPP when the firmware
        // offers it, and the BLE link is dropped so it does not take radio time from SCO.
        val t: Link = (if (preferSpp) openSppLink(adv.identifier.toString()) else null) ?: run {
            val b = BleTransport(adv)
            try {
                _mtu.value = b.connect()
            } catch (e: CancellationException) {
                runCatching { b.disconnect() }; _ble.value = BleState.Idle; throw e
            } catch (e: Exception) {
                runCatching { b.disconnect() }; _ble.value = BleState.Error(e.message ?: "connect failed"); return@withLock
            }
            b
        }
        attach(t, name)
    }

    /** Connects over BLE to a known address without scanning (Android: the bonded dongle). */
    suspend fun connectBleAddress(address: String, name: String) = lock.withLock {
        if (bleTransport != null) return@withLock
        val p = peripheralForAddress(address) ?: run { _ble.value = BleState.Error("no direct BLE connect on this platform"); return@withLock }
        _ble.value = BleState.Connecting(name)
        val b = BleTransport(p)
        try {
            _mtu.value = b.connect()
        } catch (e: CancellationException) {
            runCatching { b.disconnect() }; _ble.value = BleState.Idle; throw e
        } catch (e: Exception) {
            runCatching { b.disconnect() }; _ble.value = BleState.Error(e.message ?: "connect failed"); return@withLock
        }
        attach(b, name)
    }

    /** Connects straight over SPP to a bonded device (no BLE scan needed); Android only. */
    suspend fun connectSpp(address: String, name: String) = lock.withLock {
        if (bleTransport != null) return@withLock
        _ble.value = BleState.Connecting(name)
        val t = openSppLink(address) ?: run { _ble.value = BleState.Error("SPP connect to $name failed"); return@withLock }
        attach(t, name)
    }

    private fun attach(t: Link, name: String) {
        bleTransport = t
        lastSeqFromEsp = -1
        _ble.value = BleState.Connected(name, t.kind)
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
        val bytesThisSec = AtomicLong(0L)
        val framesThisSec = AtomicInt(0)
        var maxSendMs = 0.0
        var lastBlocked = 0L
        bleJobs += guardedBle(t) {
            t.audioIn().collect { raw ->
                bytesThisSec.addAndFetch(raw.size.toLong()); framesThisSec.addAndFetch(1)
                val f = Frame.decode(raw) ?: return@collect
                if (f.codec == Frame.CODEC_BLAST) return@collect   // throughput test frames: counted, not relayed
                lastSeqFromEsp = account(lastSeqFromEsp, f.seq) { lost -> _stats.update { it.copy(espToServer = it.espToServer + 1, lostFromEsp = it.lostFromEsp + lost, levelUp = peak(f)) } }
                val t0 = nowMs()
                serverClient?.sendAudio(raw)
                val dt = nowMs() - t0
                if (dt > maxSendMs) maxSendMs = dt
            }
        }
        bleJobs += guardedBle(t) {
            while (true) {
                delay(1_000)
                val b = bytesThisSec.exchange(0L); val n = framesThisSec.exchange(0)
                val m = maxSendMs; maxSendMs = 0.0
                val blocked = t.readBlockedMs; val dBlocked = blocked - lastBlocked; lastBlocked = blocked
                _stats.update { it.copy(bleKbps = b * 8 / 1000.0, bleFramesPerSec = n, linkFramesRead = t.framesReadCount, maxWsSendMs = m, readBlockedMsLastSec = dBlocked) }
            }
        }
        bleJobs += guardedBle(t) {
            // gather whatever is queued (up to downlinkBatch) and ship it in one write
            for (first in downlink) {
                val batch = mutableListOf(first)
                while (batch.size < downlinkBatch) batch += downlink.tryReceive().getOrNull() ?: break
                if (batch.size < downlinkBatch) {
                    // wait briefly for the rest of a batch rather than sending a lone frame
                    delay(20L * (downlinkBatch - batch.size))
                    while (batch.size < downlinkBatch) batch += downlink.tryReceive().getOrNull() ?: break
                }
                // a failed audio write is a lost frame, not a dead link (the link's own state flow reports drops)
                try { t.sendAudioBatch(batch) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    _stats.update { it.copy(downDropped = it.downDropped + batch.size) }
                }
            }
        }
        if (t.kind == "spp" && keepaliveMs > 0) bleJobs += guardedBle(t) {
            // Android hands out RFCOMM credits mostly on its own outgoing frames; with nothing to send the
            // uplink starves. A steady trickle towards the ESP32 keeps credits flowing. See docs/protocol.md.
            while (true) {
                delay(keepaliveMs)
                try { t.sendControl(Control.keepalive()) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
        bleJobs += guardedBle(t) {
            // BLE ping every 2 s; stays null until the firmware answers (GATT milestone).
            var token = 0
            while (true) {
                delay(2_000)
                token++
                pendingPing = PendingPing(token, nowMs())
                try {
                    t.sendControl(Control.ping(token))
                    t.sendControl(Control.requestStats())
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
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
                if (appEcho) s.sendAudio(raw) else if (bleTransport != null) downlink.trySend(raw)
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

    /** Writes a control message; false if not connected or the write failed (the link may have just dropped). */
    suspend fun sendControl(msg: ByteArray): Boolean {
        val t = bleTransport ?: return false
        // a write right after connect can hit a GATT procedure still in flight (MTU, parameter update): retry
        repeat(3) { attempt ->
            try { t.sendControl(msg); return true } catch (e: CancellationException) { throw e } catch (e: Exception) {
                println("callbridge control write failed (attempt ${attempt + 1}): $e")
                delay(200)
            }
        }
        return false
    }

    /**
     * End-to-end test with real audio. [fire] must: drain the phone's recorder, call `onFired` the
     * instant the tone burst is written to the SCO link, then listen and return the ms until the
     * burst comes back through the phone's microphone stream (or null). Needs BLE + server + SCO up.
     * The firmware loopback is switched off for the test so the only way back is through the server.
     */
    suspend fun runE2E(fire: suspend (onFired: suspend () -> Unit) -> Double?): E2EResult {
        println("callbridge e2e: start")
        val s = serverClient ?: return E2EResult(null, null, null, "server not connected")
        val t = bleTransport ?: return E2EResult(null, null, null, "ESP32 not connected")
        if (s.serverNowMs() == null) return E2EResult(null, null, null, "no clock sync yet (wait for a pong)")
        s.clearBurst()
        var fired = false
        val full = try {
            t.sendControl(Control.loopback(false))
            println("callbridge e2e: loopback off")
            delay(300)
            fire { fired = true; println("callbridge e2e: burst fired"); s.sendBurst(s.serverNowMs()!!) }.also { println("callbridge e2e: full=$it") }
        } finally {
            // restore even if the test was cancelled; the loopback switch in the UI reflects "on"
            withContext(NonCancellable) { runCatching { t.sendControl(Control.loopback(loopbackWanted)) } }
        }
        if (!fired) return E2EResult(null, null, null, "no tone was fired (is SCO open?)")
        // the server's detection may land a moment after the phone's
        var up: Double? = null
        repeat(20) { if (up == null) { up = s.burstDetected.value?.second; if (up == null) delay(100) } }
        println("callbridge e2e: up=$up")
        val down = if (full != null && up != null) full - up else null
        _latency.update { it.copy(upMs = up, downMs = down, fullRtMs = full) }
        s.sendE2E(full, down)
        val msg = when {
            up == null && full == null -> "burst not seen at the server or back at the phone"
            up == null -> "phone heard it back but the server never detected it (check uplink audio)"
            full == null -> "server detected it; nothing came back to the phone (check downlink)"
            else -> "ok"
        }
        return E2EResult(up, down, full, msg)
    }

    suspend fun disconnectBle() = lock.withLock { teardownBle(BleState.Idle) }
    suspend fun disconnectServer() = lock.withLock { teardownServer(ServerState.Idle) }

    private suspend fun teardownBle(final: BleState) {
        val jobs = bleJobs.toList(); bleJobs.clear()
        jobs.forEach { it.cancel() }
        // close the transport first: a writer blocked in a socket write only wakes when the socket closes
        bleTransport?.let { runCatching { it.close() } }; bleTransport = null
        jobs.forEach { it.join() }
        while (downlink.tryReceive().isSuccess) { /* drop audio queued for the old link */ }
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
    private fun guardedBle(owner: Link, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
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
