package ai.ninerouter.service

import ai.ninerouter.MainActivity
import ai.ninerouter.R
import ai.ninerouter.core.AppContainer
import ai.ninerouter.core.AppRepository
import ai.ninerouter.core.CloudflareTunnel
import ai.ninerouter.core.ProxyRuntime
import ai.ninerouter.core.TunnelOptions
import ai.ninerouter.core.TunnelPorts
import ai.ninerouter.core.TunnelRuntime
import ai.ninerouter.core.TunnelStatus
import ai.ninerouter.service.server.KtorProxyServer
import ai.ninerouter.util.Crypto
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyForegroundService : Service() {
    companion object {
        const val ACTION_START = "ai.ninerouter.action.START"
        const val ACTION_STOP = "ai.ninerouter.action.STOP"
        const val ACTION_START_TUNNEL = "ai.ninerouter.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "ai.ninerouter.action.STOP_TUNNEL"
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

        fun startTunnel(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START_TUNNEL
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTunnel(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP_TUNNEL
            }
            runCatching { context.startService(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: AppRepository
    private var telegram: TelegramBot? = null
    private var tunnelObserver: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        repo = AppContainer.repository
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                START_NOT_STICKY
            }

            ACTION_STOP_TUNNEL -> {
                startForegroundIfNeeded()
                scope.launch {
                    stopTunnelInternal()
                    notify("Tunnel stopped · gateway on http://127.0.0.1:${currentPort()}")
                }
                START_STICKY
            }

            ACTION_START_TUNNEL -> {
                startForegroundIfNeeded()
                scope.launch {
                    val port = currentPort()
                    if (!KtorProxyServer.isRunning) {
                        val started = ensureGateway(port)
                        if (!started) {
                            notify("Failed: ${ProxyRuntime.lastError.value ?: "gateway did not start"}")
                            return@launch
                        }
                    }
                    startTunnelInternal(port)
                }
                START_STICKY
            }

            else -> {
                startForegroundIfNeeded()
                scope.launch {
                    val port = currentPort()
                    val started = ensureGateway(port)
                    val text = if (started) {
                        "Listening on http://127.0.0.1:$port"
                    } else {
                        "Failed: ${ProxyRuntime.lastError.value ?: "unknown error"}"
                    }
                    notify(text)
                    if (started) {
                        val settings = repo.settingsMap()
                        if (settings["tunnel_enabled"] == "true") {
                            startTunnelInternal(port)
                        }
                    }
                }
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopTunnelInternal()
        telegram?.stop()
        telegram = null
        KtorProxyServer.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun currentPort(): Int {
        val settings = runCatching { repo.settingsMap() }.getOrDefault(emptyMap())
        return settings["proxy_port"]?.toIntOrNull()?.coerceIn(1024, 65535) ?: 20127
    }

    private suspend fun ensureGateway(port: Int): Boolean {
        if (KtorProxyServer.isRunning) return true
        val started = withContext(Dispatchers.IO) { KtorProxyServer.start(port, repo) }
        if (!started) return false
        telegram?.stop()
        telegram = TelegramBot(repo).also { it.start() }
        val days = runCatching { repo.settingsMap()["retention_days"]?.toIntOrNull() ?: 7 }.getOrDefault(7)
        repo.pruneOldRequests(days)
        return true
    }

    private suspend fun startTunnelInternal(proxyPort: Int) {
        val settings = runCatching { repo.settingsMap() }.getOrDefault(emptyMap())
        val mode = settings["tunnel_mode"]?.takeIf { it == "token" } ?: "quick"
        val token = Crypto.decrypt(settings["tunnel_token"]).orEmpty()
        val hostname = settings["tunnel_hostname"].orEmpty()

        if (mode == "token" && token.isBlank()) {
            CloudflareTunnel.fail("Add a Cloudflare tunnel token before starting a named tunnel.")
            notify("Tunnel error: token is missing")
            return
        }

        val publicPort = TunnelPorts.publicPort(proxyPort)
        val listener = withContext(Dispatchers.IO) {
            KtorProxyServer.startPublicServer(publicPort, repo)
        }
        if (!listener) {
            CloudflareTunnel.fail("Could not open the public tunnel listener on 127.0.0.1:$publicPort.")
            notify("Tunnel error: public listener failed")
            return
        }

        observeTunnel()
        CloudflareTunnel.start(
            this,
            TunnelOptions(
                localPort = publicPort,
                mode = mode,
                token = token,
                hostname = hostname,
            ),
        )
    }

    private fun stopTunnelInternal() {
        tunnelObserver?.cancel()
        tunnelObserver = null
        CloudflareTunnel.stop()
        KtorProxyServer.stopPublicServer()
    }

    /** Keep the ongoing notification in sync with the connector state. */
    private fun observeTunnel() {
        if (tunnelObserver != null) return
        tunnelObserver = scope.launch {
            TunnelRuntime.state.collect { state ->
                val tunnel = when (state.status) {
                    TunnelStatus.RUNNING -> state.publicUrl.ifBlank { "named tunnel connected" }
                    TunnelStatus.STARTING -> "starting…"
                    TunnelStatus.ERROR -> "error: ${state.message}"
                    TunnelStatus.STOPPED -> "off"
                }
                notify("http://127.0.0.1:${currentPort()} · Tunnel: $tunnel")
            }
        }
    }

    private fun startForegroundIfNeeded() {
        val tunnel = when (TunnelRuntime.state.value.status) {
            TunnelStatus.RUNNING -> TunnelRuntime.state.value.publicUrl.ifBlank { "connected" }
            TunnelStatus.STARTING -> "starting…"
            TunnelStatus.ERROR -> "error"
            TunnelStatus.STOPPED -> "off"
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Local gateway · Tunnel: $tunnel"),
        )
    }

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
