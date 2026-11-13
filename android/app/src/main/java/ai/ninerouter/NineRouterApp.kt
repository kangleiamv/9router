package ai.ninerouter

import ai.ninerouter.core.AppContainer
import ai.ninerouter.engine.UpstreamClient
import ai.ninerouter.service.ProxyForegroundService
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NineRouterApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        UpstreamClient.tokenRefreshSink = { connectionId, tokens ->
            appScope.launch {
                runCatching { AppContainer.repository.persistRefreshedTokens(connectionId, tokens) }
            }
        }
        appScope.launch {
            runCatching {
                AppContainer.repository.seed()
                val settings = AppContainer.repository.settingsMap()
                if (settings["auto_start"] != "false") {
                    withContext(Dispatchers.Main) {
                        runCatching { ProxyForegroundService.start(this@NineRouterApp) }
                    }
                }
            }
        }
    }
}
