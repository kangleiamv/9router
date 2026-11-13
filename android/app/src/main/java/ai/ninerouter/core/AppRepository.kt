package ai.ninerouter.core

import ai.ninerouter.data.model.ApiKey
import ai.ninerouter.data.model.Combo
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.data.model.Setting
import ai.ninerouter.engine.ChatContext
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.UpstreamClient
import ai.ninerouter.engine.oauth.OAuthTokens
import ai.ninerouter.engine.specificData
import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DashboardStats(
    val todayRequests: Int = 0,
    val todayTokens: Long = 0,
    val successRate: Double = 0.0,
    val avgLatencyMs: Long = 0,
    val activeConnections: Int = 0,
    val totalConnections: Int = 0,
    val lockedConnections: Int = 0
)

data class ProviderUsage(
    val providerId: String,
    val name: String,
    val requests: Int,
    val tokens: Long,
    val errors: Int
)

class AppRepository(context: android.content.Context) {
    private val db = ai.ninerouter.data.db.NineRouterDatabase.get(context)
    val providerDao = db.providerDao()
    val comboDao = db.comboDao()
    val apiKeyDao = db.apiKeyDao()
    val settingDao = db.settingDao()
    val detailDao = db.requestDetailDao()

    val engine = ai.ninerouter.engine.ProxyEngine(providerDao, comboDao, apiKeyDao, detailDao)

    val connections: Flow<List<ProviderConnection>> = providerDao.observeAll()
    val combos: Flow<List<Combo>> = comboDao.observeAll()
    val keys: Flow<List<ApiKey>> = apiKeyDao.observeAll()
    val requests: Flow<List<RequestDetail>> = detailDao.observeRecent(300)

    val settings: Flow<Map<String, String>> = kotlinx.coroutines.flow.flow {
        emit(settingDao.getAll().associate { it.key to it.value })
    }

    suspend fun seed() = withContext(Dispatchers.IO) {
        val existingKeys = apiKeyDao.getAll()
        if (existingKeys.isEmpty()) {
            val now = Instant.now().toString()
            apiKeyDao.upsert(
                ApiKey(
                    id = Ids.connectionId(),
                    name = "Default",
                    key = Ids.apiKey(),
                    isActive = true,
                    createdAt = now
                )
            )
        }
        val defaults = mapOf(
            "proxy_port" to "20127",
            "auto_start" to "true",
            "retention_days" to "7",
            "telegram_enabled" to "false",
            "telegram_bot_token" to "",
            "telegram_chat_id" to "",
            "telegram_default_model" to "",
            "telegram_combo" to "",
            "telegram_system_prompt" to "You are 9Router, a helpful assistant running on Android.",
            "telegram_notifications" to "true"
        )
        val existing = settingDao.getAll().map { it.key }.toSet()
        defaults.forEach { (key, value) ->
            if (key !in existing) settingDao.put(Setting(key, value))
        }
    }

    suspend fun getSetting(key: String): String? = withContext(Dispatchers.IO) {
        settingDao.get(key)?.value
    }

    suspend fun putSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        settingDao.put(Setting(key, value))
    }

    suspend fun settingsMap(): Map<String, String> = withContext(Dispatchers.IO) {
        settingDao.getAll().associate { it.key to it.value }
    }

    suspend fun defaultKey(): ApiKey? = withContext(Dispatchers.IO) {
        apiKeyDao.getAll().firstOrNull { it.isActive }
    }

    suspend fun ensureTelegramKey(): ApiKey = withContext(Dispatchers.IO) {
        apiKeyDao.getActiveByName("Telegram Bot") ?: ApiKey(
            id = Ids.connectionId(),
            name = "Telegram Bot",
            key = Ids.apiKey(),
            isActive = true,
            createdAt = Instant.now().toString()
        ).also { apiKeyDao.upsert(it) }
    }

    // ------------------------------------------------------------ providers

    suspend fun addConnection(
        providerId: String,
        name: String,
        apiKey: String?,
        email: String? = null,
        authType: String = "apikey",
        providerSpecificData: String? = null,
        baseUrl: String? = null
    ): ProviderConnection = withContext(Dispatchers.IO) {
        val spec = ProviderCatalog.byId(providerId)
            ?: throw IllegalArgumentException("Unknown provider $providerId")
        val now = Instant.now().toString()
        val connection = ProviderConnection(
            id = Ids.connectionId(),
            provider = spec.id,
            name = name.ifBlank { spec.name },
            email = email,
            authType = authType,
            apiKey = Crypto.encrypt(apiKey),
            priority = nextPriority(spec.id),
            isActive = true,
            testStatus = null,
            defaultModel = spec.models.firstOrNull()?.id,
            displayName = name.ifBlank { spec.name },
            providerSpecificData = mergeData(providerSpecificData, baseUrl),
            createdAt = now,
            updatedAt = now
        )
        providerDao.upsert(connection)
        connection
    }

    /** Saves a connection produced by a completed OAuth sign-in. */
    suspend fun addOAuthConnection(
        providerId: String,
        tokens: OAuthTokens
    ): ProviderConnection = withContext(Dispatchers.IO) {
        val spec = ProviderCatalog.byId(providerId)
            ?: throw IllegalArgumentException("Unknown provider $providerId")
        val now = Instant.now().toString()
        val data = kotlinx.serialization.json.buildJsonObject {
            put("accessToken", tokens.accessToken)
            tokens.refreshToken?.let { put("refreshToken", it) }
            tokens.expiresAt?.let { put("expiresAt", it.toString()) }
            tokens.scope?.let { put("scope", it) }
            tokens.projectId?.let { put("projectId", it) }
            tokens.email?.let { put("email", it) }
            put("oauthProvider", providerId)
        }.toString()
        val label = tokens.email ?: spec.name
        val connection = ProviderConnection(
            id = Ids.connectionId(),
            provider = spec.id,
            name = label,
            email = tokens.email,
            authType = "oauth",
            apiKey = Crypto.encrypt(tokens.accessToken),
            priority = nextPriority(spec.id),
            isActive = true,
            testStatus = null,
            defaultModel = spec.models.firstOrNull()?.id,
            displayName = label,
            providerSpecificData = data,
            createdAt = now,
            updatedAt = now
        )
        providerDao.upsert(connection)
        connection
    }

    /** Merges rotated OAuth tokens back into the stored connection. */
    suspend fun persistRefreshedTokens(
        connectionId: String,
        values: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val connection = providerDao.getById(connectionId) ?: return@withContext
        val merged = LinkedHashMap(connection.specificData())
        values.forEach { (key, value) -> if (value.isNotBlank()) merged[key] = value }
        val newAccess = values["accessToken"]
        providerDao.upsert(
            connection.copy(
                apiKey = if (!newAccess.isNullOrBlank()) Crypto.encrypt(newAccess) else connection.apiKey,
                providerSpecificData = kotlinx.serialization.json.buildJsonObject {
                    merged.forEach { (key, value) -> put(key, value) }
                }.toString(),
                updatedAt = Instant.now().toString()
            )
        )
    }

    private suspend fun nextPriority(providerId: String): Int {
        val existing = providerDao.getByProvider(providerId)
        return (existing.maxOfOrNull { it.priority } ?: 998) + 1
    }

    private fun mergeData(raw: String?, baseUrl: String?): String? {
        val map = mutableMapOf<String, String>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val obj = ai.ninerouter.util.Json.lenient.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
                obj.forEach { (k, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { map[k] = it }
                }
            }
        }
        if (!baseUrl.isNullOrBlank()) map["baseUrl"] = baseUrl.trim()
        return if (map.isEmpty()) null else buildString {
            append("{")
            map.entries.forEachIndexed { index, (k, v) ->
                if (index > 0) append(",")
                append("\"")
                append(k.replace("\"", "\\\""))
                append("\":\"")
                append(v.replace("\\", "\\\\").replace("\"", "\\\""))
                append("\"")
            }
            append("}")
        }
    }

    suspend fun updateConnection(connection: ProviderConnection) = withContext(Dispatchers.IO) {
        providerDao.upsert(connection.copy(updatedAt = Instant.now().toString()))
    }

    suspend fun deleteConnection(id: String) = withContext(Dispatchers.IO) {
        providerDao.delete(id)
    }

    suspend fun testConnection(connection: ProviderConnection): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val spec = ProviderCatalog.byId(connection.provider)
                ?: return@withContext false to "Unknown provider"
            val model = connection.defaultModel ?: spec.models.firstOrNull()?.id ?: "default"
            val result = UpstreamClient.testConnection(spec, connection, model)
            providerDao.upsert(
                connection.copy(
                    testStatus = if (result.first) "active" else "error",
                    lastError = if (result.first) null else result.second,
                    lastErrorAt = if (result.first) null else Instant.now().toString(),
                    updatedAt = Instant.now().toString()
                )
            )
            result
        }

    suspend fun probeProvider(spec: ProviderSpec, apiKey: String, model: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val temp = ProviderConnection(
                id = "probe",
                provider = spec.id,
                name = "probe",
                email = null,
                authType = if (spec.authType == "oauth") "apikey" else "apikey",
                apiKey = apiKey,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            )
            UpstreamClient.testConnection(spec, temp, model)
        }

    // --------------------------------------------------------------- combos

    suspend fun saveCombo(id: String?, name: String, models: List<String>, kind: String? = null): Combo =
        withContext(Dispatchers.IO) {
            val now = Instant.now().toString()
            val existing = id?.let { comboDao.getById(it) }
            val combo = Combo(
                id = existing?.id ?: Ids.connectionId(),
                name = name.trim(),
                kind = kind,
                models = kotlinx.serialization.json.JsonArray(
                    models.map { kotlinx.serialization.json.JsonPrimitive(it) }
                ).toString(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            comboDao.upsert(combo)
            combo
        }

    suspend fun deleteCombo(id: String) = withContext(Dispatchers.IO) { comboDao.delete(id) }

    // ----------------------------------------------------------------- keys

    suspend fun createKey(name: String): ApiKey = withContext(Dispatchers.IO) {
        val key = ApiKey(
            id = Ids.connectionId(),
            name = name.ifBlank { "API Key" },
            key = Ids.apiKey(),
            isActive = true,
            createdAt = Instant.now().toString()
        )
        apiKeyDao.upsert(key)
        key
    }

    suspend fun setKeyActive(key: ApiKey, active: Boolean) = withContext(Dispatchers.IO) {
        apiKeyDao.upsert(key.copy(isActive = active))
    }

    suspend fun deleteKey(id: String) = withContext(Dispatchers.IO) { apiKeyDao.delete(id) }

    // ---------------------------------------------------------------- stats

    fun statsFlow(): Flow<DashboardStats> = requests.map { list ->
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = list.filter { it.timestamp >= startOfDay }
        val success = today.count { (it.status ?: 0) in 200..299 }
        DashboardStats(
            todayRequests = today.size,
            todayTokens = today.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() },
            successRate = if (today.isEmpty()) 0.0 else success * 100.0 / today.size,
            avgLatencyMs = if (today.isEmpty()) 0 else today.sumOf { it.latencyMs ?: 0 } / today.size,
            activeConnections = 0,
            totalConnections = 0,
            lockedConnections = 0
        )
    }

    fun providerUsageFlow(): Flow<List<ProviderUsage>> = requests.map { list ->
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        list.filter { it.timestamp >= startOfDay }
            .groupBy { it.provider ?: "unknown" }
            .map { (provider, rows) ->
                ProviderUsage(
                    providerId = provider,
                    name = ProviderCatalog.byId(provider)?.name ?: provider,
                    requests = rows.size,
                    tokens = rows.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() },
                    errors = rows.count { (it.status ?: 0) >= 400 }
                )
            }
            .sortedByDescending { it.requests }
    }

    suspend fun clearRequests() = withContext(Dispatchers.IO) {
        detailDao.prune(System.currentTimeMillis() + 1)
    }

    suspend fun pruneOldRequests(days: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        detailDao.prune(cutoff)
    }

    suspend fun sendTelegram(token: String, chatId: String, text: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text.take(4000))
                    put("disable_web_page_preview", true)
                }
                val response = UpstreamClient.simpleJson(
                    "https://api.telegram.org/bot$token/sendMessage",
                    body,
                    mapOf("Content-Type" to "application/json")
                )
                val json = ai.ninerouter.util.Json.lenient
                    .parseToJsonElement(response) as kotlinx.serialization.json.JsonObject
                val ok = (json["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                ok to if (ok) "Message sent" else (json["description"]?.toString() ?: "Telegram error")
            }.getOrElse { false to (it.message ?: "Failed") }
        }

    suspend fun chatInternal(model: String, messages: List<Pair<String, String>>, system: String?): String {
        val array = kotlinx.serialization.json.buildJsonArray {
            if (!system.isNullOrBlank()) {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            messages.forEach { (role, text) ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", role)
                    put("content", text)
                })
            }
        }
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", model)
            put("messages", array)
            put("stream", false)
        }
        return when (val result = engine.handleChat(payload, internalBypass = true)) {
            is ai.ninerouter.engine.EngineResult.Buffered -> {
                if (result.status !in 200..299) {
                    ai.ninerouter.engine.UpstreamClient.extractError(result.body)
                } else {
                    runCatching {
                        val json = ai.ninerouter.util.Json.lenient
                            .parseToJsonElement(result.body) as kotlinx.serialization.json.JsonObject
                        val choices = json["choices"] as kotlinx.serialization.json.JsonArray
                        val message = (choices.first() as kotlinx.serialization.json.JsonObject)["message"]
                            as kotlinx.serialization.json.JsonObject
                        (message["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    }.getOrDefault("No response")
                }
            }
            is ai.ninerouter.engine.EngineResult.Stream -> "Streaming is not supported in Telegram chat."
        }
    }
}
