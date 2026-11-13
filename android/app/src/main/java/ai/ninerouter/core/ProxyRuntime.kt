package ai.ninerouter.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable state of the embedded proxy server, shared by service and UI. */
object ProxyRuntime {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(20127)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _startedAt = MutableStateFlow<Long?>(null)
    val startedAt: StateFlow<Long?> = _startedAt.asStateFlow()

    fun markStarted(port: Int) {
        _port.value = port
        _startedAt.value = System.currentTimeMillis()
        _lastError.value = null
        _running.value = true
    }

    fun markStopped() {
        _running.value = false
        _startedAt.value = null
    }

    fun markError(message: String?) {
        _lastError.value = message
        _running.value = false
        _startedAt.value = null
    }

    val endpoint: String
        get() = "http://127.0.0.1:${_port.value}/v1"
}

object AppContainer {
    @Volatile
    private var repo: AppRepository? = null

    fun init(context: android.content.Context) {
        if (repo == null) {
            synchronized(this) {
                if (repo == null) repo = AppRepository(context.applicationContext)
            }
        }
    }

    val repository: AppRepository
        get() = repo ?: error("AppContainer not initialized")

    val engine get() = repository.engine
}
