package callbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val CH_AUDIO = 0
private const val CH_CONTROL = 1
private const val MAX_PAYLOAD = 1024

/** RFCOMM stream to the firmware's "callbridge" SPP server: [channel u8][len u16 LE][payload]. */
class SppLink private constructor(private val socket: BluetoothSocket) : Link {
    override val kind = "spp"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audio = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val control = Channel<ByteArray>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val writeLock = Mutex()
    private val out = socket.outputStream

    init {
        scope.launch {
            val input = DataInputStream(socket.inputStream)
            val hdr = ByteArray(3)
            try {
                while (true) {
                    input.readFully(hdr)
                    val ch = hdr[0].toInt() and 0xff
                    val len = (hdr[1].toInt() and 0xff) or ((hdr[2].toInt() and 0xff) shl 8)
                    if (len > MAX_PAYLOAD) throw IOException("bad frame length $len")
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    when (ch) {
                        CH_AUDIO -> audio.send(payload)
                        CH_CONTROL -> control.send(payload)
                    }
                }
            } catch (e: IOException) {
                audio.close(e); control.close(e)
            }
        }
    }

    override fun audioIn(): Flow<ByteArray> = audio.receiveAsFlow()
    override fun controlIn(): Flow<ByteArray> = control.receiveAsFlow()

    private suspend fun write(ch: Int, data: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val buf = ByteArray(3 + data.size)
            buf[0] = ch.toByte(); buf[1] = data.size.toByte(); buf[2] = (data.size shr 8).toByte()
            data.copyInto(buf, 3)
            out.write(buf); out.flush()
        }
    }

    override suspend fun sendAudio(frame: ByteArray) = write(CH_AUDIO, frame)

    /** One socket write for the whole batch: the link is limited in packets/s, not bytes/s. */
    override suspend fun sendAudioBatch(frames: List<ByteArray>) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val buf = ByteArray(frames.sumOf { 3 + it.size })
            var off = 0
            for (f in frames) {
                buf[off] = CH_AUDIO.toByte(); buf[off + 1] = f.size.toByte(); buf[off + 2] = (f.size shr 8).toByte()
                f.copyInto(buf, off + 3); off += 3 + f.size
            }
            out.write(buf); out.flush()
        }
    }
    override suspend fun sendControl(msg: ByteArray) = write(CH_CONTROL, msg)

    override suspend fun close() = withContext(Dispatchers.IO) {
        scope.cancel()
        runCatching { socket.close() }
        Unit
    }

    companion object {
        @SuppressLint("MissingPermission")
        suspend fun open(address: String): SppLink = withContext(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("no Bluetooth adapter")
            val device = adapter.getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            try {
                socket.connect()   // blocking; throws IOException if the server is not up
            } catch (e: Throwable) {
                runCatching { socket.close() }; throw e
            }
            SppLink(socket)
        }
    }
}

actual suspend fun openSppLink(address: String): Link? = try {
    SppLink.open(address)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    println("callbridge spp: open failed: $e"); null
}
