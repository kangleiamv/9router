package ai.ninerouter.service

import ai.ninerouter.MainActivity
import ai.ninerouter.R
import ai.ninerouter.core.AppContainer
import ai.ninerouter.core.AppRepository
import ai.ninerouter.core.ProxyRuntime
import ai.ninerouter.service.server.KtorProxyServer
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyForegroundService : Service() {
    companion object {
        const val ACTION_START = "ai.ninerouter.action.START"
        const val ACTION_STOP = "ai.ninerouter.action.STOP"
        const val CHANNEL_ID = "ninerouter_proxy"
        const val NOTIFICATION_ID = 9001

        fun start(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: AppRepository
    private var telegram: TelegramBot? = null

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        repo = AppContainer.repository
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting local gateway…"))
        scope.launch {
            runCatching {
                val settings = repo.settingsMap()
                val port = settings["proxy_port"]?.toIntOrNull()?.coerceIn(1024, 65535) ?: 20127
                val started = withContext(Dispatchers.IO) { KtorProxyServer.start(port, repo) }
                val text = if (started) {
                    "Listening on http://127.0.0.1:$port"
                } else {
                    "Failed: ${ProxyRuntime.lastError.value ?: "unknown error"}"
                }
                notify(text)
                if (started) {
                    telegram?.stop()
                    telegram = TelegramBot(repo).also { it.start() }
                    val days = settings["retention_days"]?.toIntOrNull() ?: 7
                    repo.pruneOldRequests(days)
                }
            }.onFailure { notify("Failed: ${it.message}") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        telegram?.stop()
        telegram = null
        KtorProxyServer.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.proxy_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }
}
