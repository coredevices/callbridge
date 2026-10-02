package callbridge.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opens a Bluetooth SCO link to the paired ESP32 without a phone call (Android treats it as a
 * voice-communication session) and measures phone → ESP32 → phone round trip: play a 1 kHz tone
 * burst to the headset, the firmware loops SCO input back, detect the burst in the recorded stream.
 *
 * Needs RECORD_AUDIO. The ESP32 must have loopback enabled (default in the firmware).
 * All operations are serialized by [lock]; close() is safe at any time.
 */
class ScoTester(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val lock = Mutex()
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null

    val isOpen get() = rec != null

    /** Routes communication audio to SCO and starts streaming. Throws with a readable message on failure. */
    suspend fun open(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (rec != null) return@withLock
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                error("microphone permission denied")
            val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: error("no SCO device: is callbridge paired + connected?")
            var r: AudioRecord? = null
            var t: AudioTrack? = null
            try {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!am.setCommunicationDevice(dev)) error("setCommunicationDevice refused")
                // the tone we play is scaled by the voice-call volume; at its default it reaches the ESP32 ~43 dB down
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
                val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minRec * 4)
                if (r.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord init failed")
                t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                if (t.state != AudioTrack.STATE_INITIALIZED) error("AudioTrack init failed")
                r.startRecording(); t.play()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("recording did not start")
            } catch (e: Throwable) {
                r?.release(); t?.release(); restoreRouting(); throw e
            }
            rec = r; track = t
        }
        // give the SCO link a moment to come up before anyone measures
        delay(1_000)
    }

    suspend fun close() = withContext(Dispatchers.IO) { lock.withLock { closeLocked() } }

    /** Non-suspending variant for lifecycle disposal; skips if a measurement holds the lock (it closes after). */
    fun closeNow() { if (lock.tryLock()) try { closeLocked() } finally { lock.unlock() } else closeRequested = true }

    @Volatile private var closeRequested = false

    private fun closeLocked() {
        closeRequested = false
        rec?.runCatching { stop(); release() }; rec = null
        track?.runCatching { stop(); release() }; track = null
        restoreRouting()
    }

    private fun restoreRouting() {
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }

    /**
     * Median round trip over [bursts] bursts in ms, or null if the burst never came back
     * (no loopback, SCO not up, or too quiet). Returns null immediately if not open.
     */
    suspend fun measure(bursts: Int = 5): Double? = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = rec ?: return@withLock null
            val t = track ?: return@withLock null
            val results = mutableListOf<Double>()
            val burst = tone()
            val chunk = ShortArray(CHUNK)
            repeat(bursts) {
                // Drop whatever the recorder buffered while idle, so sample 0 of the listen window is "now".
                // Then take the 1 kHz noise floor from fresh audio.
                drain(r, chunk)
                val idle = ShortArray(CHUNK * 20); readFully(r, idle)
                val floor = (0 until 20).maxOf { goertzel(idle, it * CHUNK, CHUNK) }
                val threshold = maxOf(floor * 8, MIN_LEVEL)
                drain(r, chunk) // the floor read took 200 ms of wall time; realign once more
                t.write(burst, 0, burst.size)
                // ponytail: onset resolution = one CHUNK (10 ms); AudioRecord.getTimestamp would tighten it
                val window = ShortArray(RATE * 2) // 2 s listen window
                var onset = -1
                var filled = 0
                while (filled < window.size && onset < 0) {
                    val n = r.read(window, filled, CHUNK)
                    if (n <= 0) break
                    if (goertzel(window, filled, n) > threshold) onset = filled
                    filled += n
                }
                if (onset >= 0) {
                    results += onset * 1000.0 / RATE
                    // drain the rest of the burst so it doesn't trigger the next measurement
                    readFully(r, ShortArray(RATE / 2))
                }
            }
            results.sorted().getOrNull(results.size / 2).also { if (closeRequested) closeLocked() }
        }
    }

    /**
     * One burst for the end-to-end test: drain, call [onFired] the instant the burst is written,
     * then listen up to [listenMs] for the tone to come back. Returns ms from firing to detection.
     */
    suspend fun fireAndListen(listenMs: Int = 3000, onFired: suspend () -> Unit): Double? = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = rec ?: return@withLock null
            val t = track ?: return@withLock null
            val chunk = ShortArray(CHUNK)
            drain(r, chunk)
            val idle = ShortArray(CHUNK * 20); readFully(r, idle)
            val floor = (0 until 20).maxOf { goertzel(idle, it * CHUNK, CHUNK) }
            val threshold = maxOf(floor * 8, MIN_LEVEL)
            drain(r, chunk)
            try {
                // arm first, then emit: both the server's "up" and the phone's "full" are anchored at the
                // same instant (the drain just above = sample 0 of the listen window)
                val burst = tone()
                onFired()
                t.write(burst, 0, burst.size)
                val window = ShortArray(RATE * listenMs / 1000)
                var filled = 0
                while (filled < window.size) {
                    val n = r.read(window, filled, CHUNK)
                    if (n <= 0) break
                    if (goertzel(window, filled, n) > threshold) return@withLock filled * 1000.0 / RATE
                    filled += n
                }
                null
            } finally {
                if (closeRequested) closeLocked()
            }
        }
    }

    /** Non-blocking reads until the recorder has nothing queued. */
    private fun drain(r: AudioRecord, buf: ShortArray) {
        while (r.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING) > 0) { /* discard */ }
    }

    private fun readFully(r: AudioRecord, buf: ShortArray) {
        var off = 0
        while (off < buf.size) { val n = r.read(buf, off, buf.size - off); if (n <= 0) return; off += n }
    }

    private fun tone(): ShortArray {
        val n = RATE * BURST_MS / 1000
        return ShortArray(n) { i ->
            // 5 ms linear ramps keep the burst from clicking
            val env = minOf(1.0, i / (RATE * 0.005), (n - 1 - i) / (RATE * 0.005))
            (sin(2 * PI * TONE_HZ * i / RATE) * 0.6 * env * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Goertzel magnitude at TONE_HZ over buf[off, off+n), normalised per sample (floor and threshold share this scale). */
    private fun goertzel(buf: ShortArray, off: Int, n: Int): Double {
        val k = 2 * cos(2 * PI * TONE_HZ / RATE)
        var s0: Double; var s1 = 0.0; var s2 = 0.0
        for (i in off until off + n) { s0 = buf[i] / 32768.0 + k * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = RATE / 100       // 10 ms
        const val TONE_HZ = 1000.0
        const val BURST_MS = 100   // 5 frames: survives one lost 3-frame batch on the link
        const val MIN_LEVEL = 0.002
    }
}
