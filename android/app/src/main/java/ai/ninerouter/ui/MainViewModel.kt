package ai.ninerouter.ui

import ai.ninerouter.core.AppContainer
import ai.ninerouter.core.CompatibleProbe
import ai.ninerouter.core.DashboardStats
import ai.ninerouter.core.ProxyRuntime
import ai.ninerouter.core.ProviderUsage
import ai.ninerouter.data.model.ApiKey
import ai.ninerouter.data.model.Combo
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.oauth.OAuthClient
import ai.ninerouter.engine.oauth.OAuthException
import ai.ninerouter.engine.oauth.OAuthProviders
import ai.ninerouter.engine.specificData
import ai.ninerouter.service.ProxyForegroundService
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppContainer.repository

    val connections: StateFlow<List<ProviderConnection>> =
        repo.connections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val combos: StateFlow<List<Combo>> =
        repo.combos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customProviders: StateFlow<List<CustomProvider>> =
        repo.customProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customSpecs: StateFlow<List<ProviderSpec>> =
        repo.customProviders
            .map { nodes -> nodes.map(ProviderCatalog::customSpec) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val keys: StateFlow<List<ApiKey>> =
        repo.keys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requests: StateFlow<List<RequestDetail>> =
        repo.requests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _settings = MutableStateFlow<Map<String, String>>(emptyMap())
    val settings: StateFlow<Map<String, String>> = _settings.asStateFlow()

    val running: StateFlow<Boolean> = ProxyRuntime.running
    val port: StateFlow<Int> = ProxyRuntime.port
    val lastError: StateFlow<String?> = ProxyRuntime.lastError

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** Provider ids with an OAuth browser sign-in currently in flight. */
    private val _oauthBusy = MutableStateFlow<Set<String>>(emptySet())
    val oauthBusy: StateFlow<Set<String>> = _oauthBusy.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val stats: StateFlow<DashboardStats> = combine(repo.requests, repo.connections) { list, conns ->
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = list.filter { it.timestamp >= start }
        val success = today.count { (it.status ?: 0) in 200..299 }
        DashboardStats(
            todayRequests = today.size,
            todayTokens = today.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() },
            successRate = if (today.isEmpty()) 0.0 else success * 100.0 / today.size,
            avgLatencyMs = if (today.isEmpty()) 0 else today.sumOf { it.latencyMs ?: 0 } / today.size,
            activeConnections = conns.count { it.isActive && it.testStatus != "unavailable" },
            totalConnections = conns.size,
            lockedConnections = conns.count {
                ai.ninerouter.engine.FallbackPolicy.isUnavailable(it) ||
                    ai.ninerouter.engine.FallbackPolicy.earliestModelLock(it) != null
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardStats())

    val providerUsage: StateFlow<List<ProviderUsage>> =
        combine(repo.requests, repo.connections, repo.customProviders) { list, conns, customNodes ->
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val today = list.filter { it.timestamp >= start }
            val customNames = customNodes.associate { it.id to it.name }
            fun nameOf(provider: String): String =
                ProviderCatalog.byId(provider)?.name ?: customNames[provider] ?: provider
            val grouped = today.groupBy { it.provider ?: "unknown" }.map { (provider, rows) ->
                ProviderUsage(
                    providerId = provider,
                    name = nameOf(provider),
                    requests = rows.size,
                    tokens = rows.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() },
                    errors = rows.count { (it.status ?: 0) >= 400 }
                )
            }.toMutableList()
            conns.map { it.provider }.distinct().forEach { provider ->
                if (grouped.none { it.providerId == provider }) {
                    grouped += ProviderUsage(
                        providerId = provider,
                        name = nameOf(provider),
                        requests = 0, tokens = 0, errors = 0
                    )
                }
            }
            grouped.sortedByDescending { it.requests }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { repo.seed() }
            refreshSettings()
        }
    }

    fun refreshSettings() {
        viewModelScope.launch {
            _settings.value = runCatching { repo.settingsMap() }.getOrDefault(emptyMap())
        }
    }

    fun toggleProxy(run: Boolean) {
        val context = getApplication<Application>()
        if (run) ProxyForegroundService.start(context) else ProxyForegroundService.stop(context)
    }

    fun restartProxy() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            ProxyForegroundService.stop(context)
            delay(700)
            ProxyForegroundService.start(context)
            emit("Restarting local gateway…")
        }
    }

    fun saveSettingsAndRestart(values: Map<String, String>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                values.forEach { (key, value) -> repo.putSetting(key, value) }
                refreshSettings()
            }.onFailure { emit(it.message ?: "Could not save settings", true) }
            ProxyForegroundService.stop(context)
            delay(700)
            ProxyForegroundService.start(context)
            emit("Settings saved · gateway restarting")
        }
    }

    fun saveSettings(values: Map<String, String>, message: String? = null) {
        viewModelScope.launch {
            runCatching {
                values.forEach { (key, value) -> repo.putSetting(key, value) }
                refreshSettings()
            }.onSuccess { if (message != null) emit(message) }
                .onFailure { emit(it.message ?: "Could not save settings", true) }
        }
    }

    fun addConnection(
        providerId: String,
        name: String,
        apiKey: String,
        email: String,
        baseUrl: String,
        advanced: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val providerLabel = withContext(Dispatchers.IO) {
                repo.specById(providerId)?.name ?: providerId
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val data = buildString {
                        val parts = mutableListOf<String>()
                        if (advanced.isNotBlank()) {
                            parts += advanced.trim().removePrefix("{").removeSuffix("}")
                        }
                        if (providerId == "antigravity" && apiKey.isNotBlank() && !advanced.contains("accessToken")) {
                            parts += "\"accessToken\":\"${apiKey.replace("\"", "\\\"")}\""
                        }
                        if (baseUrl.isNotBlank()) {
                            parts += "\"baseUrl\":\"${baseUrl.trim().replace("\"", "\\\"")}\""
                        }
                        if (parts.isNotEmpty()) {
                            append("{")
                            append(parts.joinToString(","))
                            append("}")
                        }
                    }
                    repo.addConnection(
                        providerId = providerId,
                        name = name,
                        apiKey = apiKey.ifBlank { null },
                        email = email.ifBlank { null },
                        authType = if (ai.ninerouter.engine.oauth.OAuthProviders.supports(providerId)) "oauth" else "apikey",
                        providerSpecificData = data.ifBlank { null },
                        baseUrl = baseUrl.ifBlank { null }
                    )
                }
            }.onSuccess {
                emit("$providerLabel connection added")
                onDone()
            }.onFailure { emit(it.message ?: "Could not add connection", true) }
        }
    }

    /**
     * Starts a real OAuth sign-in: opens the provider consent page in the
     * browser and waits for the one-shot loopback callback on localhost.
     */
    fun connectOAuth(providerId: String, onDone: () -> Unit) {
        val provider = OAuthProviders.byId(providerId)
        if (provider == null) {
            emit("${ProviderCatalog.byId(providerId)?.name ?: providerId} does not support OAuth sign-in", true)
            return
        }
        if (_oauthBusy.value.contains(providerId)) return
        viewModelScope.launch {
            setOAuthBusy(providerId, true)
            var session: ai.ninerouter.engine.oauth.OAuthSession? = null
            try {
                val started = withContext(Dispatchers.IO) { OAuthClient.start(provider) }
                session = started
                val opened = runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(started.authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    getApplication<Application>().startActivity(intent)
                }
                if (opened.isFailure) {
                    started.close()
                    session = null
                    emit("No browser found. Open this link in a browser to sign in:\n${started.authUrl}", true)
                    return@launch
                }
                emit("Finish the ${ProviderCatalog.byId(providerId)?.name ?: provider.providerId} sign-in in your browser…")
                val tokens = withContext(Dispatchers.IO) { started.awaitTokens() }
                val connection = repo.addOAuthConnection(providerId, tokens)
                emit("${ProviderCatalog.byId(providerId)?.name ?: providerId} connected${connection.email?.let { " as $it" }.orEmpty()}")
                onDone()
            } catch (e: OAuthException) {
                emit(e.message ?: "OAuth sign-in failed", true)
            } catch (e: Exception) {
                emit(e.message ?: "OAuth sign-in failed", true)
            } finally {
                runCatching { session?.close() }
                setOAuthBusy(providerId, false)
            }
        }
    }

    fun testConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            setBusy(connection.id, true)
            runCatching { withContext(Dispatchers.IO) { repo.testConnection(connection) } }
                .onSuccess { (ok, message) -> emit(message, !ok) }
                .onFailure { emit(it.message ?: "Test failed", true) }
            setBusy(connection.id, false)
        }
    }

    fun toggleConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            runCatching {
                repo.updateConnection(connection.copy(isActive = !connection.isActive))
            }.onSuccess {
                emit(if (connection.isActive) "Connection paused" else "Connection enabled")
            }.onFailure { emit(it.message ?: "Update failed", true) }
        }
    }

    fun deleteConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            runCatching { repo.deleteConnection(connection.id) }
                .onSuccess { emit("Connection deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun saveConnection(
        connection: ProviderConnection,
        name: String,
        defaultModel: String,
        apiKey: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                repo.updateConnection(
                    connection.copy(
                        name = name.ifBlank { connection.name },
                        displayName = name.ifBlank { connection.displayName },
                        defaultModel = defaultModel.ifBlank { connection.defaultModel },
                        apiKey = if (apiKey.isBlank()) connection.apiKey
                        else ai.ninerouter.util.Crypto.encrypt(apiKey)
                    )
                )
            }.onSuccess {
                emit("Connection updated")
                onDone()
            }.onFailure { emit(it.message ?: "Update failed", true) }
        }
    }

    fun saveCustomProvider(
        id: String?,
        type: String,
        name: String,
        prefix: String,
        apiType: String,
        baseUrl: String,
        models: List<String>,
        apiKey: String,
        createConnection: Boolean,
        onDone: (CustomProvider) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.saveCustomProvider(
                        id = id,
                        type = type,
                        name = name,
                        prefix = prefix,
                        apiType = apiType,
                        baseUrl = baseUrl,
                        models = models,
                        apiKey = apiKey.ifBlank { null },
                        createConnection = createConnection && apiKey.isNotBlank()
                    )
                }
            }.onSuccess { node ->
                emit(if (id == null) "Compatible provider created" else "Compatible provider updated")
                onDone(node)
            }.onFailure { emit(it.message ?: "Could not save compatible provider", true) }
        }
    }

    fun deleteCustomProvider(node: CustomProvider) {
        viewModelScope.launch {
            runCatching { repo.deleteCustomProvider(node.id) }
                .onSuccess { emit("${node.name} deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun checkCustomProvider(
        type: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        onResult: (CompatibleProbe) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.probeCompatible(type, baseUrl, apiKey, modelId)
                }
            }.onSuccess { result ->
                emit(result.message, !result.ok)
                onResult(result)
            }.onFailure {
                val message = it.message ?: "Check failed"
                emit(message, true)
                onResult(CompatibleProbe(false, message))
            }
        }
    }

    fun saveCustomModels(nodeId: String, models: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.saveCustomModels(nodeId, models) }
                .onSuccess {
                    emit("Models updated")
                    onDone()
                }
                .onFailure { emit(it.message ?: "Could not save models", true) }
        }
    }

    fun discoverCustomModels(nodeId: String, apiKey: String, onDone: (List<String>?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.discoverCustomModels(
                        nodeId,
                        apiKey.ifBlank { null },
                        merge = false
                    )
                }
            }.onSuccess { models ->
                emit("Found ${models.size} models")
                onDone(models)
            }.onFailure {
                emit(it.message ?: "Could not fetch models", true)
                onDone(null)
            }
        }
    }

    fun saveCombo(id: String?, name: String, models: List<String>, kind: String?, onDone: () -> Unit) {
        if (name.isBlank() || models.isEmpty()) {
            emit("Add a name and at least one model", true)
            return
        }
        viewModelScope.launch {
            runCatching { repo.saveCombo(id, name, models, kind) }
                .onSuccess {
                    emit("Combo saved")
                    onDone()
                }
                .onFailure { emit(it.message ?: "Could not save combo", true) }
        }
    }

    fun deleteCombo(combo: Combo) {
        viewModelScope.launch {
            runCatching { repo.deleteCombo(combo.id) }
                .onSuccess { emit("Combo deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun createKey(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.createKey(name) }
                .onSuccess {
                    emit("API key created")
                    onDone()
                }
                .onFailure { emit(it.message ?: "Could not create key", true) }
        }
    }

    fun deleteKey(key: ApiKey) {
        viewModelScope.launch {
            runCatching { repo.deleteKey(key.id) }
                .onSuccess { emit("Key deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun toggleKey(key: ApiKey) {
        viewModelScope.launch {
            runCatching { repo.setKeyActive(key, !key.isActive) }
                .onSuccess { emit(if (key.isActive) "Key disabled" else "Key enabled") }
                .onFailure { emit(it.message ?: "Update failed", true) }
        }
    }

    fun clearCooldowns() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.providerDao.getAll().forEach { connection ->
                        val data = connection.specificData()
                            .filterKeys {
                                !it.startsWith(ai.ninerouter.engine.FallbackPolicy.MODEL_LOCK_PREFIX) &&
                                    it != "rateLimitedUntil"
                            }
                        repo.updateConnection(
                            connection.copy(
                                testStatus = "active",
                                lastError = null,
                                errorCode = null,
                                backoffLevel = 0,
                                providerSpecificData = if (data.isEmpty()) null else
                                    kotlinx.serialization.json.JsonObject(
                                        data.mapValues { JsonPrimitive(it.value) }
                                    ).toString()
                            )
                        )
                    }
                }
            }.onSuccess { emit("Cooldowns cleared") }
                .onFailure { emit(it.message ?: "Could not clear cooldowns", true) }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            runCatching { repo.clearRequests() }
                .onSuccess { emit("Request log cleared") }
                .onFailure { emit(it.message ?: "Could not clear log", true) }
        }
    }

    fun pruneLogs(days: Int) {
        viewModelScope.launch {
            runCatching { repo.pruneOldRequests(days) }
                .onSuccess { emit("Removed requests older than $days days") }
                .onFailure { emit(it.message ?: "Cleanup failed", true) }
        }
    }

    fun sendTelegramTest(token: String, chatId: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.sendTelegram(token, chatId, "✅ 9Router Android is connected.") } }
                .onSuccess { (ok, message) -> emit(message, !ok) }
                .onFailure { emit(it.message ?: "Telegram request failed", true) }
        }
    }

    suspend fun defaultKey(): ApiKey? = withContext(Dispatchers.IO) { repo.defaultKey() }

    suspend fun models(): List<Triple<String, String, String>> =
        withContext(Dispatchers.IO) { ProviderCatalog.modelsForSelection(repo.customSpecs()) }

    suspend fun providers(): List<ProviderSpec> =
        withContext(Dispatchers.IO) { ProviderCatalog.allWith(repo.customSpecs()) }

    /** Resolves a static provider or a user-defined compatible node by id. */
    fun specFor(providerId: String): ProviderSpec? =
        ProviderCatalog.byId(providerId) ?: customSpecs.value.firstOrNull { it.id == providerId }

    private fun setBusy(id: String, value: Boolean) {
        _busy.value = if (value) _busy.value + id else _busy.value - id
    }

    private fun setOAuthBusy(id: String, value: Boolean) {
        _oauthBusy.value = if (value) _oauthBusy.value + id else _oauthBusy.value - id
    }

    private fun emit(message: String, isError: Boolean = false) {
        _events.tryEmit(UiEvent(message, isError))
    }

    data class UiEvent(val message: String, val isError: Boolean = false)
}
