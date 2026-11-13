package ai.ninerouter.engine

import ai.ninerouter.data.db.ApiKeyDao
import ai.ninerouter.data.db.ComboDao
import ai.ninerouter.data.db.ProviderDao
import ai.ninerouter.data.db.RequestDetailDao
import ai.ninerouter.data.model.ApiKey
import ai.ninerouter.data.model.Combo
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Ids
import ai.ninerouter.util.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.time.Instant

sealed class EngineResult {
    data class Buffered(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8"
    ) : EngineResult()

    data class Stream(
        val status: Int,
        val producer: (OutputStream) -> Unit
    ) : EngineResult()
}

private data class Target(
    val provider: ProviderSpec,
    val modelId: String,
    val label: String
)

/** Lightweight process-wide hooks used for proactive Telegram alerts. */
object EngineEvents {
    @Volatile
    var onAllUnavailable: ((String) -> Unit)? = null
}

class ProxyEngine(
    private val providerDao: ProviderDao,
    private val comboDao: ComboDao,
    private val apiKeyDao: ApiKeyDao,
    private val detailDao: RequestDetailDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------- auth

    suspend fun authenticate(header: String?, xApiKey: String? = null): Pair<Boolean, ApiKey?> {
        val keys = withContext(Dispatchers.IO) { apiKeyDao.getAll() }
        if (keys.none { it.isActive }) return true to null
        val token = header?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: xApiKey?.trim().orEmpty()
        if (token.isEmpty()) return false to null
        val match = keys.firstOrNull { it.isActive && it.key == token }
        if (match != null) {
            scope.launch {
                runCatching {
                    apiKeyDao.upsert(match.copy(lastUsedAt = Instant.now().toString()))
                }
            }
        }
        return (match != null) to match
    }

    // -------------------------------------------------------------- chat

    suspend fun handleChat(raw: JsonObject, internalBypass: Boolean = false): EngineResult {
        val ctx = ChatContext.from(raw)
        if (ctx.model.isBlank()) {
            return errorResult(400, "model is required", "invalid_request_error", "bad_request")
        }
        val started = System.currentTimeMillis()
        val targets = resolveTargets(ctx.model)
        if (targets.isEmpty()) {
            record(
                provider = null, model = ctx.model, connection = null, status = 404,
                latency = System.currentTimeMillis() - started, error = "unknown provider/model",
                requestBody = raw.toString(), responseBody = null
            )
            return errorResult(
                404,
                "Model '${ctx.model}' is not available. Add a connection for its provider or create a combo with that name.",
                "invalid_request_error", "model_not_found"
            )
        }

        var lastStatus = 0
        var lastMessage = "No upstream connection available"
        val attempted = mutableSetOf<String>()

        for (target in targets) {
            val connections = withContext(Dispatchers.IO) {
                providerDao.getByProvider(target.provider.id)
            }.filter { it.isActive }
            if (connections.isEmpty()) {
                lastMessage = "No ${target.provider.name} connection configured"
                continue
            }
            for (connection in connections) {
                if (!attempted.add(connection.id + ":" + target.modelId)) continue
                if (FallbackPolicy.isUnavailable(connection)) continue
                if (FallbackPolicy.isModelLockActive(connection, target.modelId)) continue

                val call = runCatching {
                    UpstreamClient.call(
                        target.provider,
                        connection,
                        target.modelId,
                        ctx.copy(model = target.modelId)
                    )
                }.getOrElse { error ->
                    lastMessage = error.message ?: "Connection failed"
                    lastStatus = 502
                    continue
                }

                if (call.response.isSuccess) {
                    if (ctx.stream) {
                        return EngineResult.Stream(200) { out ->
                            try {
                                UpstreamClient.relayStream(call, target.label, out)
                            } catch (t: Throwable) {
                                runCatching {
                                    out.write("data: {\"error\":{\"message\":\"stream interrupted\"}}\n\n".toByteArray())
                                }
                            } finally {
                                call.response.close()
                                scope.launch { onSuccess(connection, target.modelId) }
                                record(
                                    provider = target.provider.id,
                                    model = target.modelId,
                                    connection = connection,
                                    status = 200,
                                    latency = System.currentTimeMillis() - started,
                                    error = null,
                                    requestBody = raw.toString(),
                                    responseBody = null,
                                    promptTokens = estimateTokens(ctx),
                                    completionTokens = null
                                )
                            }
                        }
                    }
                    val body = call.response.text()
                    val converted = UpstreamClient.toOpenAiJson(call, body, ctx.model)
                    call.response.close()
                    onSuccess(connection, target.modelId)
                    record(
                        provider = target.provider.id,
                        model = target.modelId,
                        connection = connection,
                        status = 200,
                        latency = System.currentTimeMillis() - started,
                        error = null,
                        requestBody = raw.toString(),
                        responseBody = converted.take(8000),
                        promptTokens = extractUsage(converted, "prompt_tokens"),
                        completionTokens = extractUsage(converted, "completion_tokens")
                    )
                    return EngineResult.Buffered(200, converted)
                }

                val errorBody = call.response.text()
                call.response.close()
                val errorText = UpstreamClient.extractError(errorBody)
                lastStatus = call.response.status
                lastMessage = "HTTP ${call.response.status}: $errorText"
                val decision = FallbackPolicy.check(
                    call.response.status, errorText, connection.backoffLevel, target.provider.id
                )
                record(
                    provider = target.provider.id, model = target.modelId, connection = connection,
                    status = call.response.status,
                    latency = System.currentTimeMillis() - started,
                    error = errorText.take(500), requestBody = raw.toString(),
                    responseBody = errorBody.take(8000)
                )
                if (decision.shouldFallback) {
                    applyFailure(connection, target.modelId, decision)
                    // Account-scoped failure: try the next account for this member.
                    continue
                }
                // Request-scoped failure: credential is healthy; don't lock it. Move to
                // the next combo member (if any); otherwise return the upstream error.
                if (targets.size == 1) {
                    return EngineResult.Buffered(
                        call.response.status,
                        openAiError(call.response.status, errorText),
                        "application/json"
                    )
                }
                break
            }
        }

        val status = 429
        record(
            provider = targets.firstOrNull()?.provider?.id, model = ctx.model, connection = null,
            status = status, latency = System.currentTimeMillis() - started,
            error = lastMessage, requestBody = raw.toString(), responseBody = null
        )
        runCatching {
            EngineEvents.onAllUnavailable?.invoke(
                "⚠️ All connections are unavailable for ${ctx.model}\n$lastMessage"
            )
        }
        return errorResult(
            status,
            "All connections are temporarily unavailable for '${ctx.model}' | lastError=[$lastStatus]: $lastMessage",
            "rate_limit_error",
            "rate_limit_exceeded"
        )
    }

    /** Proactive chat probe used by the Telegram bridge (bypasses API-key auth). */
    suspend fun handleInternalChat(raw: JsonObject): EngineResult = handleChat(raw, internalBypass = true)

    private suspend fun onSuccess(connection: ProviderConnection, model: String) {
        runCatching {
            val now = Instant.now().toString()
            val cleaned = connection.specificData().toMutableMap()
            cleaned.remove("rateLimitedUntil")
            cleaned.keys.filter { it.startsWith(FallbackPolicy.MODEL_LOCK_PREFIX) }
                .forEach { cleaned.remove(it) }
            providerDao.upsert(
                connection.copy(
                    testStatus = "active",
                    lastError = null,
                    errorCode = null,
                    backoffLevel = 0,
                    lastUsedAt = now,
                    consecutiveUseCount = connection.consecutiveUseCount + 1,
                    providerSpecificData = JsonObject(cleaned.mapValues { JsonPrimitive(it.value) }).toString(),
                    updatedAt = now
                )
            )
        }
    }

    private suspend fun applyFailure(
        connection: ProviderConnection,
        model: String,
        decision: FallbackDecision
    ) {
        runCatching {
            val now = Instant.now().toString()
            val data = connection.specificData().toMutableMap()
            if (decision.modelScoped || decision.newBackoffLevel > 0) {
                data[FallbackPolicy.lockKey(model)] =
                    Instant.now().plusMillis(decision.cooldownMs).toString()
            }
            if (!decision.modelScoped) {
                data["rateLimitedUntil"] =
                    Instant.now().plusMillis(decision.cooldownMs).toString()
            }
            providerDao.upsert(
                connection.copy(
                    testStatus = "unavailable",
                    lastError = "cooldown ${decision.cooldownMs / 1000}s",
                    lastErrorAt = now,
                    errorCode = connection.errorCode,
                    backoffLevel = decision.newBackoffLevel,
                    providerSpecificData = JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString(),
                    updatedAt = now
                )
            )
        }
    }

    // ------------------------------------------------------- target mapping

    private suspend fun resolveTargets(model: String): List<Target> {
        val trimmed = model.trim()
        val comboName = trimmed.removePrefix("combo/")
        val combos = withContext(Dispatchers.IO) { comboDao.getAll() }
        val combo = combos.firstOrNull { it.name.equals(comboName, ignoreCase = true) }
        if (combo != null) {
            return parseComboModels(combo).mapNotNull { member ->
                val (spec, modelId) = ProviderCatalog.resolve(member)
                spec?.let {
                    Target(it, normalizeModel(it, modelId), if (member.contains('/')) member else "${it.id}/$modelId")
                }
            }
        }
        if (trimmed == "vision" || trimmed == "9router-vision") {
            val visionCombo = combos.firstOrNull { it.kind == "vision" }
            if (visionCombo != null) {
                return parseComboModels(visionCombo).mapNotNull { member ->
                    val (spec, modelId) = ProviderCatalog.resolve(member)
                    spec?.let { Target(it, normalizeModel(it, modelId), member) }
                }
            }
        }

        val (spec, modelId) = ProviderCatalog.resolve(trimmed)
        if (spec != null) return listOf(Target(spec, normalizeModel(spec, modelId), trimmed))

        // Unknown model: try every configured provider that speaks OpenAI in priority order.
        val connections = withContext(Dispatchers.IO) { providerDao.getAll() }
        val providers = connections.mapNotNull { ProviderCatalog.byId(it.provider) }.distinctBy { it.id }
        return providers.map { Target(it, trimmed, "${it.id}/$trimmed") }
    }

    private fun normalizeModel(spec: ProviderSpec, modelId: String): String {
        val hit = spec.models.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
        return hit?.id ?: modelId
    }

    private fun parseComboModels(combo: Combo): List<String> {
        return runCatching {
            val array = Json.lenient.parseToJsonElement(combo.models) as JsonArray
            array.mapNotNull { el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull
                    is JsonObject -> el.string("model")
                        ?: el.string("id")
                        ?: (el.string("provider")?.let { p -> el.string("modelId")?.let { "$p/$it" } })
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- records

    private fun record(
        provider: String?,
        model: String,
        connection: ProviderConnection?,
        status: Int,
        latency: Long,
        error: String?,
        requestBody: String?,
        responseBody: String?,
        promptTokens: Int? = null,
        completionTokens: Int? = null
    ) {
        scope.launch {
            runCatching {
                detailDao.insert(
                    RequestDetail(
                        id = Ids.requestId(),
                        timestamp = System.currentTimeMillis(),
                        provider = provider,
                        model = model,
                        connectionId = connection?.id,
                        connectionName = connection?.displayName ?: connection?.name ?: connection?.email,
                        status = status,
                        latencyMs = latency,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        error = error,
                        requestBody = requestBody,
                        responseBody = responseBody
                    )
                )
            }
        }
    }

    private fun estimateTokens(ctx: ChatContext): Int =
        minOf(200_000, ctx.messages.sumOf { contentText(it["content"]).length } / 4)

    private fun extractUsage(body: String, key: String): Int? {
        return runCatching {
            val json = Json.lenient.parseToJsonElement(body).jsonObject
            json.obj("usage")?.let { usage ->
                (usage[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            }
        }.getOrNull()
    }

    // --------------------------------------------------------------- utils

    fun modelList(): String {
        val combosJson = buildJsonArray { }
        return buildJsonObject {
            put("object", "list")
            put("data", combosJson)
        }.toString()
    }

    suspend fun listModels(): String {
        val combos = withContext(Dispatchers.IO) { comboDao.getAll() }
        return buildJsonObject {
            put("object", "list")
            putJsonArray("data") {
                combos.forEach { combo ->
                    add(buildJsonObject {
                        put("id", combo.name)
                        put("object", "model")
                        put("owned_by", "9router")
                    })
                }
                ProviderCatalog.all.forEach { spec ->
                    spec.models.forEach { model ->
                        add(buildJsonObject {
                            put("id", "${spec.id}/${model.id}")
                            put("object", "model")
                            put("owned_by", spec.id)
                            put("name", model.name)
                        })
                    }
                }
            }
        }.toString()
    }

    fun errorResult(status: Int, message: String, type: String, code: String): EngineResult.Buffered =
        EngineResult.Buffered(status, openAiErrorText(message, type, code))

    private fun openAiError(status: Int, message: String): String =
        openAiErrorText(
            message,
            when (status) {
                401 -> "authentication_error"
                403 -> "permission_error"
                404 -> "invalid_request_error"
                429 -> "rate_limit_error"
                else -> "server_error"
            },
            when (status) {
                401 -> "invalid_api_key"
                403 -> "insufficient_quota"
                404 -> "model_not_found"
                429 -> "rate_limit_exceeded"
                else -> "upstream_error"
            }
        )

    private fun openAiErrorText(message: String, type: String, code: String): String =
        buildJsonObject {
            putJsonObject("error") {
                put("message", message)
                put("type", type)
                put("code", code)
            }
        }.toString()

    fun maskConnectionKey(connection: ProviderConnection): String =
        Crypto.mask(connection.apiKey)
}
