package callbridge

import com.juul.kable.Peripheral

actual suspend fun Peripheral.requestMtuOrNull(mtu: Int): Int? = null

actual fun peripheralForAddress(address: String): Peripheral? = null
