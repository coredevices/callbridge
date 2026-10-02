package callbridge

import kotlinx.coroutines.flow.Flow

/** A data link to the ESP32 carrying audio frames and control messages (docs/protocol.md). */
interface Link {
    val kind: String
    fun audioIn(): Flow<ByteArray>
    fun controlIn(): Flow<ByteArray>
    suspend fun sendAudio(frame: ByteArray)
    /** Several frames in one link write where the link supports it (SPP); default sends them one by one. */
    suspend fun sendAudioBatch(frames: List<ByteArray>) { for (f in frames) sendAudio(f) }
    suspend fun sendControl(msg: ByteArray)
    suspend fun close()
}

/**
 * Opens a Bluetooth Classic SPP (RFCOMM) link to the device with this address, or returns null
 * where the platform has no SPP (iOS). SPP shares the HFP piconet so it does not compete with
 * the SCO link for the radio the way BLE does on the ESP32.
 */
expect suspend fun openSppLink(address: String): Link?
