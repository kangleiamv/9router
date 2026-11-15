package ai.ninerouter.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI

/** Lifecycle of the on-device cloudflared connector. */
enum class TunnelStatus { STOPPED, STARTING, RUNNING, ERROR }

/**
 * Observable Cloudflare Tunnel state shared by the foreground service, the
 * Compose UI and the notification. Kept in a global object for the same reason
 * as [ProxyRuntime]: service and activity live in one process but not one
 * ViewModel.
 */
data class TunnelState(
    val status: TunnelStatus = TunnelStatus.STOPPED,
    val mode: String = "quick",
    val localPort: Int = 0,
    val publicUrl: String = "",
    val hostname: String = "",
    val message: String = "",
    val logTail: List<String> = emptyList(),
    val startedAt: Long? = null,
    val processAlive: Boolean = false,
) {
    val running: Boolean get() = status == TunnelStatus.RUNNING
    val busy: Boolean get() = status == TunnelStatus.STARTING
}

/** State receiver used by [CloudflareTunnel] so one manager can drive the
 * gateway connector and each independent quick-link connector. */
internal interface TunnelStateSink {
    val status: TunnelStatus
    fun begin(mode: String, localPort: Int, hostname: String)
    fun update(transform: (TunnelState) -> TunnelState)
    fun connected(publicUrl: String, message: String)
    fun fail(message: String)
    fun stopped()
}

object TunnelRuntime : TunnelStateSink {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    override val status: TunnelStatus get() = _state.value.status

    override fun begin(mode: String, localPort: Int, hostname: String) {
        _state.value = TunnelState(
            status = TunnelStatus.STARTING,
            mode = mode,
            localPort = localPort,
            hostname = hostname,
            message = "Starting cloudflared…",
        )
    }

    override fun update(transform: (TunnelState) -> TunnelState) {
        _state.update(transform)
    }

    override fun connected(publicUrl: String, message: String) {
        _state.update {
            it.copy(
                status = TunnelStatus.RUNNING,
                publicUrl = publicUrl,
                message = message,
                startedAt = System.currentTimeMillis(),
            )
        }
    }

    override fun fail(message: String) {
        _state.update {
            it.copy(
                status = TunnelStatus.ERROR,
                message = message,
                startedAt = null,
                processAlive = false,
            )
        }
    }

    override fun stopped() {
        _state.update {
            it.copy(
                status = TunnelStatus.STOPPED,
                publicUrl = "",
                message = "",
                startedAt = null,
                processAlive = false,
            )
        }
    }

    /** Public OpenAI base URL clients should use (tunnel root + /v1). */
    val clientBaseUrl: String
        get() {
            val url = _state.value.publicUrl.trimEnd('/')
            return if (url.isBlank()) "" else "$url/v1"
        }
}

/**
 * Independent state for quick tunnels owned by a single [TunnelLink].
 *
 * Quick links do not share the gateway tunnel: each enabled link runs its own
 * cloudflared quick connector, gets a unique trycloudflare.com hostname and is
 * mapped back to that link by the public listener. Named links never appear in
 * this map; they keep using the shared Zero Trust tunnel.
 */
object LinkTunnelRuntime {
    private val _states = MutableStateFlow<Map<String, TunnelState>>(emptyMap())
    val states: StateFlow<Map<String, TunnelState>> = _states.asStateFlow()

    /** trycloudflare hostname -> link id, used by the local router. */
    private val _hosts = MutableStateFlow<Map<String, String>>(emptyMap())
    val hosts: StateFlow<Map<String, String>> = _hosts.asStateFlow()

    fun stateFor(linkId: String): TunnelState? = _states.value[linkId]

    internal fun sink(linkId: String): TunnelStateSink = LinkSink(linkId)

    internal fun remove(linkId: String) {
        _states.update { it - linkId }
        _hosts.update { hosts -> hosts.filterValues { id -> id != linkId } }
    }

    internal fun clear() {
        _states.value = emptyMap()
        _hosts.value = emptyMap()
    }

    internal fun registerHost(linkId: String, publicUrl: String) {
        val host = runCatching { URI(publicUrl.trim()) }.getOrNull()?.host
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return
        _hosts.update { current ->
            val cleaned = current.filterValues { it != linkId }
            cleaned + (host to linkId)
        }
    }

    private fun unregisterHost(linkId: String) {
        _hosts.update { hosts -> hosts.filterValues { id -> id != linkId } }
    }

    private class LinkSink(private val linkId: String) : TunnelStateSink {
        override val status: TunnelStatus
            get() = _states.value[linkId]?.status ?: TunnelStatus.STOPPED

        private fun current(): TunnelState =
            _states.value[linkId] ?: TunnelState(mode = "quick")

        private fun put(state: TunnelState) {
            _states.update { it + (linkId to state) }
        }

        override fun begin(mode: String, localPort: Int, hostname: String) {
            put(
                TunnelState(
                    status = TunnelStatus.STARTING,
                    mode = mode,
                    localPort = localPort,
                    hostname = hostname,
                    message = "Starting cloudflared…",
                ),
            )
        }

        override fun update(transform: (TunnelState) -> TunnelState) {
            put(transform(current()))
        }

        override fun connected(publicUrl: String, message: String) {
            put(
                current().copy(
                    status = TunnelStatus.RUNNING,
                    publicUrl = publicUrl,
                    message = message,
                    startedAt = System.currentTimeMillis(),
                    processAlive = true,
                ),
            )
            registerHost(linkId, publicUrl)
        }

        override fun fail(message: String) {
            put(
                current().copy(
                    status = TunnelStatus.ERROR,
                    message = message,
                    startedAt = null,
                    processAlive = false,
                ),
            )
            unregisterHost(linkId)
        }

        override fun stopped() {
            put(
                current().copy(
                    status = TunnelStatus.STOPPED,
                    publicUrl = "",
                    message = "",
                    startedAt = null,
                    processAlive = false,
                ),
            )
            unregisterHost(linkId)
        }
    }
}
