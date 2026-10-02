package callbridge

/** iOS has no third-party SPP; the BLE link is used instead. */
actual suspend fun openSppLink(address: String): Link? = null
