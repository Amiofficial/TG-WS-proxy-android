package app.tgwsproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.tgwsproxy.MainActivity
import app.tgwsproxy.Settings
import app.tgwsproxy.core.AppLog
import app.tgwsproxy.core.ProxyServer
import app.tgwsproxy.core.ProxyState

class ProxyService : Service() {
    private var server: ProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProxy()
        return START_STICKY
    }

    private fun startProxy() {
        if (server != null) return
        val settings = Settings(this)
        showForeground(settings.port)
        val cfg = try {
            settings.load()
        } catch (e: Exception) {
            fail("Неверные настройки: ${e.message}")
            return
        }
        try {
            ProxyState.stats.reset()
            server = ProxyServer(cfg, ProxyState.stats).also { it.start() }
            ProxyState.error.value = null
            ProxyState.running.value = true
        } catch (_: java.net.BindException) {
            fail("Порт ${cfg.port} занят. Выберите другой в настройках.")
        } catch (e: Exception) {
            fail("Не удалось запустить: $e")
        }
    }

    private fun fail(msg: String) {
        AppLog.e(msg)
        ProxyState.error.value = msg
        ProxyState.running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showForeground(port: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Работа прокси", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TG WS Proxy работает")
            .setContentText("127.0.0.1:$port")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Выключить", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        ProxyState.running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.tgwsproxy.STOP"
        private const val CHANNEL = "proxy"
        private const val NOTIF_ID = 1
    }
}
