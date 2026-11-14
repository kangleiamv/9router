package ai.ninerouter.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

object TunnelRuntime {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    internal fun begin(mode: String, localPort: Int, hostname: String) {
        _state.value = TunnelState(
            status = TunnelStatus.STARTING,
            mode = mode,
            localPort = localPort,
            hostname = hostname,
            message = "Starting cloudflared…",
        )
    }

    internal fun update(transform: (TunnelState) -> TunnelState) {
        _state.update(transform)
    }

    internal fun connected(publicUrl: String, message: String) {
        _state.update {
            it.copy(
                status = TunnelStatus.RUNNING,
                publicUrl = publicUrl,
                message = message,
                startedAt = System.currentTimeMillis(),
            )
        }
    }

    internal fun fail(message: String) {
        _state.update {
            it.copy(
                status = TunnelStatus.ERROR,
                message = message,
                startedAt = null,
                processAlive = false,
            )
        }
    }

    internal fun stopped() {
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
