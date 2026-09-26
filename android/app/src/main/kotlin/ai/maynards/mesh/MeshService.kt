package ai.maynards.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the mesh alive while the engine runs: a foreground service (so Android does not kill us),
 * a partial wake lock (so the CPU keeps working with the screen off) and a low-latency Wi-Fi lock
 * (so every token's round trip stays short).
 */
class MeshService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(intent?.getStringExtra(EXTRA_TEXT) ?: "Mesh running"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mesh:engine").apply { acquire(6 * 60 * 60 * 1000L) }
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "mesh:link").apply { acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mesh", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("MeshAI")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "mesh"
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context, text: String) =
            ctx.startForegroundService(Intent(ctx, MeshService::class.java).putExtra(EXTRA_TEXT, text))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MeshService::class.java))
    }
}
