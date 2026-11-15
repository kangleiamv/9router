package ai.ninerouter.core

import android.content.Context

/**
 * Owns the independent quick tunnel connectors for local links.
 *
 * The shared gateway connector lives in [CloudflareTunnel.GATEWAY_ID]. Every
 * enabled quick link owns a separate cloudflared process and a separate
 * trycloudflare.com hostname, all pointing at the restricted public listener.
 * [LinkTunnelRuntime] maps each generated hostname back to its link so the
 * local router can forward requests to the right loopback service.
 */
object LinkTunnelManager {
    private data class Running(val target: String, val publicPort: Int)

    private val lock = Any()
    private val running = mutableMapOf<String, Running>()

    /**
     * Reconcile running quick connectors with [links]. Unchanged links keep
     * their process and URL; removed, disabled or edited targets are stopped
     * and restarted so a quick URL never points at a stale service.
     */
    fun sync(context: Context, links: List<TunnelLink>, publicPort: Int) {
        val app = context.applicationContext
        val desired = links
            .filter { it.enabled && TunnelLinks.isQuick(it) }
            .associateBy { it.id }

        val toStop = mutableListOf<String>()
        val toStart = mutableListOf<TunnelLink>()
        synchronized(lock) {
            running.entries.toList().forEach { (id, active) ->
                val link = desired[id]
                if (link == null || active.target != link.target || active.publicPort != publicPort) {
                    running.remove(id)
                    toStop += id
                }
            }
            desired.forEach { (id, link) ->
                if (!running.containsKey(id)) {
                    running[id] = Running(link.target, publicPort)
                    toStart += link
                }
            }
        }

        toStop.forEach { id ->
            CloudflareTunnel.stopInstance(id)
            LinkTunnelRuntime.remove(id)
        }
        toStart.forEach { link -> start(app, link, publicPort) }
    }

    /** Force a fresh connector for one link, used by the per-link retry action. */
    fun restart(context: Context, link: TunnelLink, publicPort: Int) {
        val app = context.applicationContext
        synchronized(lock) { running.remove(link.id) }
        CloudflareTunnel.stopInstance(link.id)
        LinkTunnelRuntime.remove(link.id)
        if (!link.enabled || !TunnelLinks.isQuick(link)) return
        synchronized(lock) { running[link.id] = Running(link.target, publicPort) }
        start(app, link, publicPort)
    }

    /** Stop every quick link connector; the gateway connector is untouched. */
    fun stopAll() {
        val ids = synchronized(lock) {
            val current = running.keys.toList()
            running.clear()
            current
        }
        ids.forEach { CloudflareTunnel.stopInstance(it) }
        LinkTunnelRuntime.clear()
    }

    fun isActive(linkId: String): Boolean = synchronized(lock) { running.containsKey(linkId) }

    private fun start(context: Context, link: TunnelLink, publicPort: Int) {
        CloudflareTunnel.startInstance(
            id = link.id,
            context = context,
            options = TunnelOptions(
                localPort = publicPort,
                mode = TunnelCommand.MODE_QUICK,
            ),
            sink = LinkTunnelRuntime.sink(link.id),
        )
    }
}
