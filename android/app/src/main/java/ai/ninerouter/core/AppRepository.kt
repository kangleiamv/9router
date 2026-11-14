package ai.ninerouter.core

import ai.ninerouter.data.model.ANTHROPIC_COMPATIBLE_TYPE
import ai.ninerouter.data.model.ApiKey
import ai.ninerouter.data.model.Combo
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.OPENAI_COMPATIBLE_TYPE
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.data.model.Setting
import ai.ninerouter.engine.ChatContext
import ai.ninerouter.engine.ModelSpec
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.UpstreamClient
import ai.ninerouter.engine.WireFormat
import ai.ninerouter.engine.oauth.OAuthTokens
import ai.ninerouter.engine.specificData
import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
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

/** Result of validating a user-defined OpenAI/Anthropic compatible endpoint. */
data class CompatibleProbe(
    val ok: Boolean,
    val message: String,
    val models: List<String> = emptyList()
)

/** Result of refreshing a provider's live model catalog. */
data class ModelFetchResult(
    val ok: Boolean,
    val message: String,
    val count: Int = 0
)

class AppRepository(context: android.content.Context) {
    private val db = ai.ninerouter.data.db.NineRouterDatabase.get(context)
    private val modelCacheFile = File(context.filesDir, "provider_models.json")
    val providerDao = db.providerDao()
    val comboDao = db.comboDao()
    val apiKeyDao = db.apiKeyDao()
    val settingDao = db.settingDao()
    val detailDao = db.requestDetailDao()
    val customProviderDao = db.customProviderDao()

    init {
        // Loaded synchronously so the proxy server, Telegram bot and UI all see
        // the last fetched catalogs even when they start before the first screen.
        runCatching {
            if (modelCacheFile.isFile) {
                ProviderCatalog.setDynamicModels(parseModelCache(modelCacheFile.readText()))
            }
        }
    }

    val engine = ai.ninerouter.engine.ProxyEngine(
        providerDao, comboDao, apiKeyDao, detailDao, customProviderDao
    )

    val connections: Flow<List<ProviderConnection>> = providerDao.observeAll()
    val customProviders: Flow<List<CustomProvider>> = customProviderDao.observeAll()
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
            "telegram_notifications" to "true",
            "tunnel_enabled" to "false",
            "tunnel_mode" to "quick",
            "tunnel_hostname" to "",
            "tunnel_token" to ""
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
        val custom = customProviderDao.getById(providerId)
        val spec = ProviderCatalog.byId(providerId)
            ?: custom?.let(ProviderCatalog::customSpec)
            ?: throw IllegalArgumentException("Unknown provider $providerId")
        if (!spec.available) {
            throw IllegalArgumentException("${spec.name} is not supported by the Android transport yet")
        }
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
            defaultModel = spec.defaultModel ?: spec.models.firstOrNull()?.id,
            displayName = name.ifBlank { spec.name },
            providerSpecificData = mergeData(
                providerSpecificData,
                baseUrl?.takeIf { it.isNotBlank() } ?: custom?.baseUrl
            ),
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
            defaultModel = spec.defaultModel ?: spec.models.firstOrNull()?.id,
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

    // ------------------------------------------------------ custom providers

    suspend fun customSpecs(): List<ProviderSpec> = withContext(Dispatchers.IO) {
        customProviderDao.getAll().map(ProviderCatalog::customSpec)
    }

    suspend fun specById(providerId: String): ProviderSpec? = withContext(Dispatchers.IO) {
        ProviderCatalog.byId(providerId)
            ?: customProviderDao.getById(providerId)?.let(ProviderCatalog::customSpec)
    }

    /**
     * Creates or updates a compatible endpoint. When [createConnection] is true
     * and an API key is provided, a first connection is created in the same step.
     */
    suspend fun saveCustomProvider(
        id: String?,
        type: String,
        name: String,
        prefix: String,
        apiType: String,
        baseUrl: String,
        models: List<String>,
        apiKey: String? = null,
        createConnection: Boolean = false
    ): CustomProvider = withContext(Dispatchers.IO) {
        if (type != OPENAI_COMPATIBLE_TYPE && type != ANTHROPIC_COMPATIBLE_TYPE) {
            throw IllegalArgumentException("Unsupported custom provider type")
        }
        if (type == OPENAI_COMPATIBLE_TYPE && apiType != "chat") {
            throw IllegalArgumentException("Responses API is not supported yet — use Chat Completions")
        }
        val cleanName = name.trim()
        if (cleanName.isBlank()) throw IllegalArgumentException("Name is required")
        val cleanPrefix = prefix.trim().removeSuffix("/")
        if (cleanPrefix.isBlank()) throw IllegalArgumentException("Prefix is required")
        if (cleanPrefix.any { it.isWhitespace() || it == '/' }) {
            throw IllegalArgumentException("Prefix cannot contain spaces or slashes")
        }
        val cleanBase = ProviderCatalog.sanitizeBaseUrl(baseUrl, type)
        if (cleanBase.isBlank()) throw IllegalArgumentException("Base URL is required")
        if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) {
            throw IllegalArgumentException("Base URL must start with http:// or https://")
        }
        val existingNodes = customProviderDao.getAll()
        if (existingNodes.any { it.prefix.equals(cleanPrefix, ignoreCase = true) && it.id != id }) {
            throw IllegalArgumentException("Prefix '$cleanPrefix' is already used by another custom provider")
        }
        ProviderCatalog.byAliasOrId(cleanPrefix)?.let {
            throw IllegalArgumentException("Prefix '$cleanPrefix' is reserved by ${it.name}")
        }
        val existing = id?.let { customProviderDao.getById(it) }
        val now = Instant.now().toString()
        val node = CustomProvider(
            id = existing?.id ?: compatibleId(type, apiType),
            type = type,
            name = cleanName,
            prefix = cleanPrefix,
            apiType = if (type == OPENAI_COMPATIBLE_TYPE) apiType else "chat",
            baseUrl = cleanBase,
            models = existing?.models ?: "[]",
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        ).withModels(models)
        customProviderDao.upsert(node)
        if (createConnection && !apiKey.isNullOrBlank()) {
            addConnection(
                providerId = node.id,
                name = node.name,
                apiKey = apiKey,
                authType = "apikey",
                providerSpecificData = customNodeData(node),
                baseUrl = node.baseUrl
            )
        }
        node
    }

    suspend fun saveCustomModels(nodeId: String, models: List<String>): CustomProvider =
        withContext(Dispatchers.IO) {
            val node = customProviderDao.getById(nodeId)
                ?: throw IllegalArgumentException("Unknown custom provider")
            val updated = node.withModels(models).copy(updatedAt = Instant.now().toString())
            customProviderDao.upsert(updated)
            syncCustomDefaultModels(updated)
            updated
        }

    /** Deletes the endpoint and every connection that points at it. */
    suspend fun deleteCustomProvider(nodeId: String) = withContext(Dispatchers.IO) {
        providerDao.deleteByProvider(nodeId)
        customProviderDao.delete(nodeId)
    }

    /** Checks a base URL/key and returns the model ids reported by `/models`. */
    suspend fun probeCustomModels(type: String, baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val clean = ProviderCatalog.sanitizeBaseUrl(baseUrl, type)
            if (clean.isBlank()) throw IllegalArgumentException("Base URL is required")
            UpstreamClient.listModels(clean, type == ANTHROPIC_COMPATIBLE_TYPE, apiKey)
        }

    /**
     * Validates a compatible endpoint the same way the web dashboard's "Check"
     * button does: first `/models`, then a minimal inference request when the
     * server has no model-listing endpoint and a model id was supplied.
     */
    suspend fun probeCompatible(
        type: String,
        baseUrl: String,
        apiKey: String,
        modelId: String? = null
    ): CompatibleProbe = withContext(Dispatchers.IO) {
        val clean = ProviderCatalog.sanitizeBaseUrl(baseUrl, type)
        if (clean.isBlank()) return@withContext CompatibleProbe(false, "Base URL is required")
        try {
            val models = UpstreamClient.listModels(clean, type == ANTHROPIC_COMPATIBLE_TYPE, apiKey)
            return@withContext CompatibleProbe(
                true,
                "Valid · ${models.size} model${if (models.size == 1) "" else "s"} found",
                models
            )
        } catch (listError: Exception) {
            val probeModel = modelId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@withContext CompatibleProbe(
                    false,
                    listError.message ?: "Could not reach /models"
                )
            val now = Instant.now().toString()
            val node = CustomProvider(
                id = "probe",
                type = type,
                name = "Probe",
                prefix = "probe",
                apiType = "chat",
                baseUrl = clean,
                models = "[]",
                createdAt = now,
                updatedAt = now
            ).withModels(listOf(probeModel))
            val connection = ProviderConnection(
                id = "probe",
                provider = node.id,
                name = "Probe",
                email = null,
                authType = "apikey",
                apiKey = Crypto.encrypt(apiKey),
                priority = 1,
                isActive = true,
                defaultModel = probeModel,
                providerSpecificData = null,
                createdAt = now,
                updatedAt = now
            )
            val (ok, message) = UpstreamClient.testConnection(
                ProviderCatalog.customSpec(node),
                connection,
                probeModel
            )
            if (ok) {
                CompatibleProbe(true, "Valid · inference test passed", listOf(probeModel))
            } else {
                CompatibleProbe(false, message)
            }
        }
    }

    /** Fetches `/models` for an existing custom provider and merges the result. */
    suspend fun discoverCustomModels(
        nodeId: String,
        apiKey: String? = null,
        merge: Boolean = true
    ): List<String> =
        withContext(Dispatchers.IO) {
            val node = customProviderDao.getById(nodeId)
                ?: throw IllegalArgumentException("Unknown custom provider")
            val key = apiKey?.takeIf { it.isNotBlank() }
                ?: providerDao.getByProvider(nodeId).firstOrNull()
                    ?.let { Crypto.decrypt(it.apiKey) }
            val discovered = UpstreamClient.listModels(node.baseUrl, node.isAnthropic, key)
            if (!merge) return@withContext discovered
            val merged = (node.modelIds() + discovered).distinct()
            val updated = node.withModels(merged).copy(updatedAt = Instant.now().toString())
            customProviderDao.upsert(updated)
            syncCustomDefaultModels(updated)
            merged
        }

    /**
     * Fetches a static provider's live model list and caches it. The cache is
     * merged into [ProviderCatalog] immediately, so model pickers, combos,
     * Telegram and `/v1/models` all see the new entries without a restart.
     */
    suspend fun fetchProviderModels(connectionId: String): ModelFetchResult =
        withContext(Dispatchers.IO) {
            val connection = providerDao.getById(connectionId)
                ?: return@withContext ModelFetchResult(false, "Connection not found")
            val spec = specById(connection.provider)
                ?: return@withContext ModelFetchResult(false, "Unknown provider ${connection.provider}")
            runCatching {
                val fetched = UpstreamClient.listProviderModels(spec, connection)
                val cache = LinkedHashMap(ProviderCatalog.dynamicModels())
                cache[spec.id] = fetched
                ProviderCatalog.setDynamicModels(cache)
                saveModelCache(cache)

                val merged = ProviderCatalog.byId(spec.id)?.models ?: fetched
                val current = connection.defaultModel
                if (current.isNullOrBlank() ||
                    merged.none { it.id.equals(current, ignoreCase = true) }
                ) {
                    merged.firstOrNull { it.kind == "llm" }?.let { first ->
                        providerDao.upsert(
                            connection.copy(
                                defaultModel = first.id,
                                updatedAt = Instant.now().toString()
                            )
                        )
                    }
                }
                ModelFetchResult(
                    true,
                    "Fetched ${fetched.count { it.kind == "llm" }} models from ${spec.name}",
                    fetched.size
                )
            }.getOrElse { error ->
                ModelFetchResult(
                    false,
                    error.message ?: "Could not fetch models from ${spec.name}"
                )
            }
        }

    private fun parseModelCache(raw: String): Map<String, List<ModelSpec>> {
        val root = runCatching {
            ai.ninerouter.util.Json.lenient.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return emptyMap()
        return root.mapNotNull { (providerId, element) ->
            val array = element as? JsonArray ?: return@mapNotNull null
            val models = array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (id.isBlank()) return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val upstream = (obj["upstreamId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ModelSpec(id = id, name = name ?: id, upstreamId = upstream, kind = kind ?: "llm")
            }
            if (models.isEmpty()) null else providerId to models
        }.toMap()
    }

    private fun saveModelCache(cache: Map<String, List<ModelSpec>>) {
        runCatching {
            val root = buildJsonObject {
                cache.forEach { (providerId, models) ->
                    putJsonArray(providerId) {
                        models.forEach { model ->
                            add(buildJsonObject {
                                put("id", model.id)
                                put("name", model.name)
                                model.upstreamId?.let { put("upstreamId", it) }
                                put("kind", model.kind)
                            })
                        }
                    }
                }
            }
            modelCacheFile.writeText(root.toString())
        }
    }

    /** Keeps existing connections pointed at a model that still exists. */
    private suspend fun syncCustomDefaultModels(node: CustomProvider) {
        val ids = node.modelIds()
        val first = ids.firstOrNull() ?: return
        val now = Instant.now().toString()
        providerDao.getByProvider(node.id).forEach { connection ->
            val current = connection.defaultModel
            if (current.isNullOrBlank() || ids.none { it.equals(current, ignoreCase = true) }) {
                providerDao.upsert(
                    connection.copy(defaultModel = first, updatedAt = now)
                )
            }
        }
    }

    private fun compatibleId(type: String, apiType: String): String =
        if (type == ANTHROPIC_COMPATIBLE_TYPE) {
            "anthropic-compatible-${Ids.connectionId()}"
        } else {
            "openai-compatible-$apiType-${Ids.connectionId()}"
        }

    private fun customNodeData(node: CustomProvider): String = buildJsonObject {
        put("baseUrl", node.baseUrl)
        put("prefix", node.prefix)
        put("apiType", node.apiType)
        put("nodeName", node.name)
    }.toString()

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
                ?: customProviderDao.getById(connection.provider)?.let(ProviderCatalog::customSpec)
                ?: return@withContext false to "Unknown provider"
            // Use the stored row so tokens or a project id persisted by another
            // coroutine are not overwritten by this test.
            val current = providerDao.getById(connection.id) ?: connection
            val model = current.defaultModel
                ?: spec.defaultModel
                ?: spec.models.firstOrNull()?.id
                ?: "default"
            var result = UpstreamClient.testConnection(spec, current, model)
            var repairedModel: String? = null
            // Antigravity and Gemini CLI use the same 404 for a model that is
            // in the catalog but not enabled for the account. Verify the
            // credential on the provider's known-good model before declaring
            // the connection broken.
            if (!result.first &&
                (spec.format == WireFormat.ANTIGRAVITY || spec.format == WireFormat.GEMINI_CLI) &&
                UpstreamClient.isUnknownEntityMessage(result.second)
            ) {
                val fallback = spec.defaultModel
                if (!fallback.isNullOrBlank() && !fallback.equals(model, ignoreCase = true)) {
                    val retry = UpstreamClient.testConnection(spec, current, fallback)
                    result = when {
                        retry.first -> {
                            repairedModel = fallback
                            true to
                                "Connected · $fallback (selected $model is unavailable for this account)"
                        }
                        UpstreamClient.isUnknownEntityMessage(retry.second) -> false to
                            "${retry.second} · neither $model nor $fallback is enabled for this account"
                        else -> retry
                    }
                }
            }
            val latest = providerDao.getById(connection.id) ?: current
            providerDao.upsert(
                latest.copy(
                    defaultModel = repairedModel ?: latest.defaultModel,
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

    fun providerUsageFlow(): Flow<List<ProviderUsage>> =
        combine(requests, customProviders) { list, customNodes ->
            val customNames = customNodes.associate { it.id to it.name }
            val startOfDay =
                LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.filter { it.timestamp >= startOfDay }
                .groupBy { it.provider ?: "unknown" }
                .map { (provider, rows) ->
                    ProviderUsage(
                        providerId = provider,
                        name = ProviderCatalog.byId(provider)?.name
                            ?: customNames[provider]
                            ?: provider,
                        requests = rows.size,
                        tokens = rows.sumOf {
                            ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong()
                        },
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
