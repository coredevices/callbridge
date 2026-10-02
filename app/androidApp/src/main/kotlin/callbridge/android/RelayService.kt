package callbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import callbridge.Control
import callbridge.Session
import callbridge.scanCallbridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

/** Foreground service that owns the [Session] so the relay survives the UI going to the background. */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Session(scope)
    val sco by lazy { ScoTester(this) }

    /** BLE (the default data link), or SPP straight to the bonded device when [Session.preferSpp] is set. */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun connectAny() {
        // the dongle is bonded (HFP); connect to its address directly. A scan is only the fallback:
        // Android silently throttles apps that start more than 5 scans in 30 s.
        val bonded = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { it.name == "callbridge" }
        if (bonded != null) {
            // enabling notifications right after connect sometimes fails (GATT 133, mostly just after the
            // dongle rebooted) and tears the link down; give it a moment to settle and retry
            repeat(4) { attempt ->
                if (session.preferSpp) session.connectSpp(bonded.address, bonded.name) else session.connectBleAddress(bonded.address, bonded.name)
                kotlinx.coroutines.delay(1500)
                if (session.ble.value is callbridge.BleState.Connected) return
                Log.i("callbridge", "connect attempt ${attempt + 1} failed: ${session.ble.value}")
                session.disconnectBle()
                kotlinx.coroutines.delay(1000)
            }
        }
        val adv = withTimeout(10_000) { scanCallbridge().first() }
        session.connectBle(adv)
    }

    /**
     * Debug command channel so tests can be driven from adb without touching the UI:
     *   adb shell am broadcast -a callbridge.CMD --es cmd connect|disconnect|relay|unrelay|sco|unsco|measure|e2e|blast|unblast|loopback|unloopback
     * Results are logged under the "callbridge" tag.
     */
    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd") ?: return
            val url = intent.getStringExtra("url") ?: "ws://127.0.0.1:8080/ws"
            scope.launch {
                val result = runCatching {
                    when (cmd) {
                        "connect", "connectble" -> { session.preferSpp = false; connectAny(); session.ble.value.toString() }
                        "connectspp" -> { session.preferSpp = true; connectAny(); session.ble.value.toString() }
                        "disconnect" -> { session.disconnectBle(); "ok" }
                        "relay" -> { session.connectServer(url, "echo", "demo", 0); session.server.value.toString() }
                        "relayroom" -> { session.connectServer(url, "room", "solo", 0); session.server.value.toString() }
                        "appecho" -> { session.appEcho = true; if (session.server.value !is callbridge.ServerState.Connected) session.connectServer(url, "room", intent.getStringExtra("room") ?: "demo", 0); "app echoes server audio back (room ${intent.getStringExtra("room") ?: "demo"})" }
                        "unappecho" -> { session.appEcho = false; "app echo off" }
                        "itvl6", "itvl7", "itvl8", "itvl9", "itvl10", "itvl11", "itvl12", "itvl13", "itvl14" ->
                            "ble interval ${cmd.removePrefix("itvl").toInt() * 1.25} ms: ${session.sendControl(Control.bleInterval(cmd.removePrefix("itvl").toInt()))}"
                        "linkecho" -> "dongle link echo ${session.sendControl(Control.linkEcho(true))}"
                        "unlinkecho" -> "dongle link echo off ${session.sendControl(Control.linkEcho(false))}"
                        "scoecho" -> { if (!sco.isOpen) sco.open(); scope.launch { sco.startEcho() }; "phone echoes SCO (dongle loopback should be off)" }
                        "unscoecho" -> { sco.stopEcho(); "echo off" }
                        "room" -> { session.connectServer(url, "room", intent.getStringExtra("room") ?: "demo", 0); session.server.value.toString() }
                        "unrelay" -> { session.disconnectServer(); "ok" }
                        "sco" -> { sco.open(); "sco open" }
                        "unsco" -> { sco.close(); "sco closed" }
                        "measure" -> { val ms = sco.measure(); session.setScoRoundTrip(ms); "sco rt $ms" }
                        "e2e" -> session.runE2E { onFired -> sco.fireAndListen(onFired = onFired) }.toString()
                        "blast" -> "blast ${session.sendControl(Control.blast(true))}"
                        "unblast" -> "blast off ${session.sendControl(Control.blast(false))}"
                        "loopback" -> { session.loopbackWanted = true; "loopback ${session.sendControl(Control.loopback(true))}" }
                        "batch1", "batch2", "batch3", "batch4" -> {
                            val n = cmd.last().digitToInt(); session.downlinkBatch = n
                            "batch $n: esp ${session.sendControl(Control.sppBatch(n))}"
                        }
                        "ka0", "ka20", "ka50", "ka100" -> { session.keepaliveMs = cmd.removePrefix("ka").toLong(); "keepalive ${session.keepaliveMs} ms (reconnect to apply)" }
                        "lowlat" -> { sco.lowLatency = true; "lowLatency on (reopen sco)" }
                        "nolowlat" -> { sco.lowLatency = false; "lowLatency off (reopen sco)" }
                        "unloopback" -> { session.loopbackWanted = false; "loopback off ${session.sendControl(Control.loopback(false))}" }
                        else -> "unknown cmd $cmd"
                    }
                }.getOrElse { "error: $it" }
                Log.i("callbridge", "cmd $cmd -> $result")
            }
        }
    }

    inner class LocalBinder : Binder() { val service get() = this@RelayService }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "callbridge relay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("callbridge").setContentText("relay running")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
        startForeground(1, n)
        // debug builds only: any app could otherwise drive the relay or redirect audio to its own server
        if (BuildConfig.DEBUG) registerReceiver(cmdReceiver, IntentFilter("callbridge.CMD"), Context.RECEIVER_EXPORTED)
        // one diagnostic line per second in logcat: adb logcat -s callbridge
        scope.launch {
            while (true) {
                delay(1_000)
                val s = session.stats.value; val l = session.latency.value
                Log.i("callbridge", "ble=${session.ble.value} server=${session.server.value} mtu=${session.mtu.value} " +
                    "rx ${s.bleFramesPerSec} f/s ${"%.1f".format(s.bleKbps)} kbps linkRead=${s.linkFramesRead} maxWsSend=${"%.0f".format(s.maxWsSendMs)}ms readWait=${s.readBlockedMsLastSec}ms up=${s.espToServer}/lost ${s.lostFromEsp} peakUp=${s.levelUp} down=${s.serverToEsp}/lost ${s.lostFromServer} peakDown=${s.levelDown} " +
                    "call=${s.callState} esp=${s.esp?.let { "tx ${it.txFrames} rx ${it.rxFrames} lost ${it.rxLost} late ${it.rxLate} scodrop ${it.scoDrops}" }} " +
                    "lat sco=${l.scoRtMs} ble=${l.bleRtMs} ws=${l.wsRtMs} up=${l.upMs} down=${l.downMs} full=${l.fullRtMs}")
            }
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) unregisterReceiver(cmdReceiver)
        sco.closeNow()
        // close links before the scope dies: SPP's reader has its own scope and would outlive us
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(2_000) { session.disconnectBle(); session.disconnectServer() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val CHANNEL = "relay" }
}
