package callbridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import callbridge.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Foreground service that owns the [Session] so the relay survives the UI going to the background. */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Session(scope)

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
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { const val CHANNEL = "relay" }
}
