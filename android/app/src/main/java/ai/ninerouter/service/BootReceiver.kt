package ai.ninerouter.service

import ai.ninerouter.core.AppContainer
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Re-arms the gateway after a reboot when the user enabled auto-start. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppContainer.init(context)
                val settings = AppContainer.repository.settingsMap()
                if (settings["auto_start"] != "false") {
                    ProxyForegroundService.start(context)
                }
            } catch (_: Throwable) {
                // Nothing to do: the app will start the service on next launch.
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
