package ai.ninerouter.engine

import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.engine.oauth.OAuthClient
import ai.ninerouter.engine.oauth.OAuthProviders
import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Ids
import ai.ninerouter.util.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpstreamResponse(
    val status: Int,
    val contentType: String,
    private val response: Response
) : AutoCloseable {
    private var consumed = false

    fun text(): String {
        if (consumed) return ""
        consumed = true
        return runCatching { response.body?.string().orEmpty() }.getOrDefault("")
    }

    fun byteStream(): java.io.InputStream {
        consumed = true
        return response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
    }

    fun source(): BufferedSource? {
        consumed = true
        return response.body?.source()
    }

    val isSuccess: Boolean get() = status in 200..299

    override fun close() {
        runCatching { response.close() }
    }
}

data class PreparedCall(
    val provider: ProviderSpec,
    val model: String,
    val upstreamModel: String,
    val response: UpstreamResponse
) : AutoCloseable {
    override fun close() = response.close()
}

class UpstreamException(message: String) : Exception(message)

object UpstreamClient {
    private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val plainClient: OkHttpClient = client.newBuilder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Called on a background thread after an OAuth access token is refreshed so
     * the new token (and any rotated refresh token) can be persisted.
     */
    @Volatile
    var tokenRefreshSink: ((connectionId: String, tokens: Map<String, String>) -> Unit)? = null

    // ------------------------------------------------------------------ auth

    suspend fun resolveCredentials(connection: ProviderConnection): Map<String, String> {
        val data = connection.specificData().toMutableMap()
        val key = Crypto.decrypt(connection.apiKey) ?: data["apiKey"]
        if (!key.isNullOrBlank()) data["apiKey"] = key
        if (data["accessToken"].isNullOrBlank() && !key.isNullOrBlank()) {
            data["accessToken"] = key
        }
        val oauth = OAuthProviders.byId(connection.provider)
        if (oauth != null) {
            val access = data["accessToken"]
            val refresh = data["refreshToken"]
            val expires = data["expiresAt"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            val expired = expires == 0L || expires - 60_000 < System.currentTimeMillis()
            if (!refresh.isNullOrBlank() && (access.isNullOrBlank() || expired)) {
                val refreshed = OAuthClient.refresh(oauth, refresh)
                if (refreshed != null) {
                    data["accessToken"] = refreshed.accessToken
                    data["apiKey"] = refreshed.accessToken
                    refreshed.refreshToken?.let { data["refreshToken"] = it }
                    refreshed.expiresAt?.let { data["expiresAt"] = it.toString() }
                    refreshed.scope?.let { data["scope"] = it }
                    tokenRefreshSink?.invoke(connection.id, data.toMap())
                }
            }
        }
        val codeAssistKind = when (connection.provider) {
            "antigravity" -> ai.ninerouter.engine.oauth.CodeAssistKind.ANTIGRAVITY
            "gemini-cli" -> ai.ninerouter.engine.oauth.CodeAssistKind.GEMINI_CLI
            else -> null
        }
        if (codeAssistKind != null) {
            ensureProject(connection, codeAssistKind, data)
        }
        return data
    }

    /** Project lookups are cached as a failure backoff so a broken account does not stall every request. */
    private val projectLookupBlockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Recently resolved project ids, so an immediate retry does not hit loadCodeAssist again. */
    private val resolvedProjects = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Ensures an Antigravity/Gemini CLI connection has a Cloud project. Older
     * connections and manually imported access tokens often have none, which
     * makes Google answer `404 Requested entity was not found.` to every call.
     * The resolved id is persisted through [tokenRefreshSink].
     */
    private suspend fun ensureProject(
        connection: ProviderConnection,
        kind: ai.ninerouter.engine.oauth.CodeAssistKind,
        data: MutableMap<String, String>,
        force: Boolean = false
    ): String? {
        val existing = data["projectId"].orEmpty()
        if (!force && existing.isNotBlank()) return existing
        if (!force) resolvedProjects[connection.id]?.let {
            data["projectId"] = it
            return it
        }
        val now = System.currentTimeMillis()
        if (!force && (projectLookupBlockedUntil[connection.id] ?: 0L) > now) return null
        val access = data["accessToken"].orEmpty()
        if (access.isBlank()) return null
        val resolved = withContext(Dispatchers.IO) {
            OAuthClient.resolveProjectId(kind, access)
        }
        if (resolved.isNullOrBlank()) {
            projectLookupBlockedUntil[connection.id] = now + 5 * 60_000L
            return null
        }
        projectLookupBlockedUntil.remove(connection.id)
        resolvedProjects[connection.id] = resolved
        data["projectId"] = resolved
        tokenRefreshSink?.invoke(connection.id, mapOf("projectId" to resolved))
        return resolved
    }

    // ------------------------------------------------------------------ call

    suspend fun call(
        spec: ProviderSpec,
        connection: ProviderConnection,
        modelId: String,
        ctx: ChatContext,
        baseUrlOverride: String? = null
    ): PreparedCall {
        val creds = resolveCredentials(connection).toMutableMap()
        val upstreamModel = ProviderCatalog.upstreamModel(spec.id, modelId)
        val baseUrl = baseUrlOverride?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: when {
                // Free OpenCode models are served by the inference lane, not Zen.
                spec.id == "opencode-zen" && upstreamModel.endsWith("-free") ->
                    "https://opencode.ai/inference/v1"
                else -> spec.baseUrl
            }
            ?: throw UpstreamException("Provider ${spec.id} has no base URL configured")
        fun buildRequest(): Request = when (spec.format) {
            WireFormat.OPENAI, WireFormat.OLLAMA -> openAiRequest(
                spec, baseUrl, creds, upstreamModel, ctx
            )
            WireFormat.ANTHROPIC -> anthropicRequest(spec, baseUrl, creds, upstreamModel, ctx)
            WireFormat.GEMINI -> geminiRequest(spec, baseUrl, creds, upstreamModel, ctx)
            WireFormat.GEMINI_CLI -> geminiCliRequest(spec, baseUrl, creds, upstreamModel, ctx)
            WireFormat.ANTIGRAVITY -> antigravityRequest(spec, baseUrl, creds, connection, upstreamModel, ctx)
        }
        fun execute(request: Request): Response =
            runCatching { client.newCall(request).execute() }
                .getOrElse { throw UpstreamException("Connection to ${spec.name} failed: ${it.message}") }

        var response = execute(buildRequest())
        // A missing or stale Cloud project makes Google answer 404 with this
        // exact message. Re-resolve the project once and retry instead of
        // surfacing a dead connection to the client. Google also returns this
        // 404 for a model that is not enabled for the account, so only retry
        // when the lookup produced a different project id.
        if (spec.format == WireFormat.ANTIGRAVITY && isUnknownEntityResponse(response)) {
            val previous = creds["projectId"].orEmpty()
            val resolved = ensureProject(
                connection,
                ai.ninerouter.engine.oauth.CodeAssistKind.ANTIGRAVITY,
                creds,
                force = true
            )
            if (resolved != null && resolved != previous) {
                runCatching { response.close() }
                creds["projectId"] = resolved
                response = execute(buildRequest())
            }
        }
        return PreparedCall(
            provider = spec,
            model = modelId,
            upstreamModel = upstreamModel,
            response = UpstreamResponse(
                status = response.code,
                contentType = response.header("Content-Type").orEmpty(),
                response = response
            )
        )
    }

    /** True for Google's generic "unknown project/model" 404 from Code Assist. */
    fun isUnknownEntityResponse(response: Response): Boolean {
        if (response.code != 404) return false
        val body = runCatching { response.peekBody(32 * 1024).string() }.getOrDefault("")
        return isUnknownEntityMessage(body)
    }

    /** Matches the raw message inside [isUnknownEntityResponse] for test results. */
    fun isUnknownEntityMessage(text: String): Boolean =
        text.contains("Requested entity was not found", ignoreCase = true)

    private fun authHeaders(spec: ProviderSpec, creds: Map<String, String>): MutableMap<String, String> {
        val headers = mutableMapOf("Content-Type" to "application/json", "Accept" to "application/json")
        val key = creds["apiKey"].orEmpty()
        when (spec.id) {
            "openrouter" -> {
                headers["Authorization"] = "Bearer $key"
                headers["HTTP-Referer"] = "https://github.com/ninerouter/android"
                headers["X-Title"] = "9Router Android"
            }
            "opencode-zen" -> {
                headers["Authorization"] = "Bearer ${key.ifBlank { "public" }}"
                headers["User-Agent"] = "opencode/latest/2.0.26/cli"
                headers["x-opencode-client"] = "cli"
            }
            "anthropic" -> {
                headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
                headers.remove("Authorization")
            }
            "claude" -> {
                headers["Authorization"] = "Bearer ${creds["accessToken"] ?: key}"
                headers["anthropic-version"] = "2023-06-01"
                headers["anthropic-beta"] =
                    "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14," +
                        "context-management-2025-06-27,prompt-caching-scope-2026-01-05," +
                        "advanced-tool-use-2025-11-20,effort-2025-11-24,structured-outputs-2025-12-15," +
                        "fast-mode-2026-02-01,redact-thinking-2026-02-12,token-efficient-tools-2026-03-28"
                headers["User-Agent"] = "claude-cli/2.1.322 (external, sdk-cli)"
                headers["x-app"] = "cli"
                headers.remove("x-api-key")
            }
            else -> {
                if (spec.isCompatible && spec.format == WireFormat.ANTHROPIC) {
                    if (key.isNotBlank()) headers["x-api-key"] = key
                    headers["anthropic-version"] = "2023-06-01"
                    headers.remove("Authorization")
                } else if (!spec.isCompatible || key.isNotBlank()) {
                    headers["Authorization"] = "Bearer $key"
                }
            }
        }
        return headers
    }

    private fun openAiRequest(
        spec: ProviderSpec,
        baseUrl: String,
        creds: Map<String, String>,
        upstreamModel: String,
        ctx: ChatContext
    ): Request {
        val payload = buildJsonObject {
            ctx.raw.forEach { (k, v) -> if (k != "model") put(k, v) }
            put("model", upstreamModel)
        }
        val path = if (spec.chatPath.startsWith("http")) spec.chatPath else baseUrl + spec.chatPath
        val builder = Request.Builder().url(path).post(payload.toString().toRequestBody(MEDIA_JSON))
        authHeaders(spec, creds).forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    // ------------------------------------------------------------- anthropic

    private fun anthropicRequest(
        spec: ProviderSpec,
        baseUrl: String,
        creds: Map<String, String>,
        upstreamModel: String,
        ctx: ChatContext
    ): Request {
        val system = StringBuilder()
        val messages = mutableListOf<JsonObject>()
        for (message in ctx.messages) {
            val role = message.string("role") ?: "user"
            when (role) {
                "system", "developer" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(contentText(message["content"]))
                }
                "tool" -> {
                    val toolCallId = message.string("tool_call_id") ?: "tool"
                    messages += buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", toolCallId)
                                put("content", contentText(message["content"]))
                            })
                        }
                    }
                }
                "assistant" -> {
                    val blocks = mutableListOf<JsonElement>()
                    val text = contentText(message["content"])
                    if (text.isNotEmpty()) blocks += buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                    (message["tool_calls"] as? JsonArray)?.forEach { callEl ->
                        val call = callEl as? JsonObject ?: return@forEach
                        val fn = call.obj("function") ?: return@forEach
                        blocks += buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.string("id") ?: "tool_${Ids.requestId()}")
                            put("name", fn.string("name") ?: "tool")
                            put("input", parseArguments(fn["arguments"]))
                        }
                    }
                    messages += buildJsonObject {
                        put("role", "assistant")
                        if (blocks.isEmpty()) put("content", "") else put("content", JsonArray(blocks))
                    }
                }
                else -> {
                    messages += buildJsonObject {
                        put("role", "user")
                        put("content", anthropicContent(message["content"]))
                    }
                }
            }
        }
        if (messages.none { it.string("role") == "user" }) {
            messages += buildJsonObject {
                put("role", "user")
                put("content", "Continue.")
            }
        }
        val payload = buildJsonObject {
            put("model", upstreamModel)
            put("messages", JsonArray(messages))
            if (system.isNotEmpty()) put("system", system.toString())
            put("max_tokens", ctx.raw.int("max_tokens") ?: ctx.raw.int("max_completion_tokens") ?: 4096)
            put("stream", ctx.stream)
            ctx.raw["temperature"]?.let { put("temperature", it) }
            ctx.raw["top_p"]?.let { put("top_p", it) }
            ctx.raw["stop"]?.let { put("stop_sequences", if (it is JsonArray) it else buildJsonArray { add(it) }) }
            val toolArray = anthropicTools(ctx.raw["tools"])
            if (toolArray.isNotEmpty()) put("tools", toolArray)
        }
        val url = if (spec.id == "claude") "$baseUrl/messages?beta=true" else "$baseUrl/messages"
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(MEDIA_JSON))
        authHeaders(spec, creds).forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun anthropicContent(content: JsonElement?): JsonElement {
        if (content !is JsonArray) return JsonPrimitive(contentText(content))
        val blocks = buildJsonArray {
            content.forEach { part ->
                when (part) {
                    is JsonObject -> {
                        val type = part.string("type")
                        when (type) {
                            "image_url" -> {
                                val url = part.obj("image_url")?.string("url").orEmpty()
                                val parsed = parseDataUrl(url)
                                if (parsed != null) add(buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", parsed.first)
                                        put("data", parsed.second)
                                    }
                                })
                            }
                            "text" -> add(buildJsonObject {
                                put("type", "text")
                                put("text", part.string("text").orEmpty())
                            })
                            else -> Unit
                        }
                    }
                    is JsonPrimitive -> add(buildJsonObject {
                        put("type", "text")
                        put("text", part.contentOrNull.orEmpty())
                    })
                    else -> Unit
                }
            }
        }
        return if (blocks.isEmpty()) JsonPrimitive("") else blocks
    }

    private fun anthropicTools(tools: JsonElement?): JsonArray {
        val array = tools as? JsonArray ?: return JsonArray(emptyList())
        return buildJsonArray {
            array.forEach { el ->
                val tool = el as? JsonObject ?: return@forEach
                val fn = tool.obj("function") ?: tool
                add(buildJsonObject {
                    put("name", fn.string("name") ?: return@forEach)
                    put("description", fn.string("description").orEmpty())
                    put("input_schema", fn["parameters"] ?: buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject { })
                    })
                })
            }
        }
    }

    // ---------------------------------------------------------------- gemini

    private fun geminiRequest(
        spec: ProviderSpec,
        baseUrl: String,
        creds: Map<String, String>,
        upstreamModel: String,
        ctx: ChatContext
    ): Request {
        val stream = ctx.stream
        val action = if (stream) "streamGenerateContent?alt=sse&key=" else "generateContent?key="
        val url = "$baseUrl/models/$upstreamModel:$action${creds["apiKey"].orEmpty()}"
        val payload = geminiPayload(ctx)
        val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(MEDIA_JSON))
        builder.header("Content-Type", "application/json")
        return builder.build()
    }

    private fun geminiCliRequest(
        spec: ProviderSpec,
        baseUrl: String,
        creds: Map<String, String>,
        upstreamModel: String,
        ctx: ChatContext
    ): Request {
        val project = creds["projectId"].orEmpty()
        if (project.isBlank()) {
            throw UpstreamException("Gemini CLI connection has no Cloud project. Reconnect the account in Providers.")
        }
        val action = if (ctx.stream) ":streamGenerateContent?alt=sse" else ":generateContent"
        val payload = buildJsonObject {
            put("project", project)
            put("model", upstreamModel)
            put("request", geminiPayload(ctx))
        }
        val builder = Request.Builder()
            .url(baseUrl + action)
            .post(payload.toString().toRequestBody(MEDIA_JSON))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${creds["accessToken"].orEmpty()}")
            .header("User-Agent", "google-genai-sdk/1.41.0 gl-node/v22.19.0")
            .header("X-Goog-Api-Client", "gl-node/22.19.0")
            .header("Accept", if (ctx.stream) "text/event-stream" else "application/json")
        return builder.build()
    }

    private fun geminiPayload(ctx: ChatContext): JsonObject {
        val system = StringBuilder()
        val contents = mutableListOf<JsonObject>()
        for (message in ctx.messages) {
            when (message.string("role")) {
                "system", "developer" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(contentText(message["content"]))
                }
                "assistant" -> contents += buildJsonObject {
                    put("role", "model")
                    put("parts", geminiParts(message["content"], message["tool_calls"]))
                }
                else -> contents += buildJsonObject {
                    put("role", "user")
                    put("parts", geminiParts(message["content"], null))
                }
            }
        }
        if (contents.isEmpty()) contents += buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", "Continue.") }) })
        }
        return buildJsonObject {
            put("contents", JsonArray(contents))
            if (system.isNotEmpty()) putJsonObject("systemInstruction") {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system.toString()) }) })
            }
            putJsonObject("generationConfig") {
                ctx.raw["temperature"]?.let { put("temperature", it) }
                ctx.raw["top_p"]?.let { put("topP", it) }
                (ctx.raw.int("max_tokens") ?: ctx.raw.int("max_completion_tokens"))?.let { put("maxOutputTokens", it) }
                ctx.raw.string("stop")?.let { putJsonArray("stopSequences") { add(JsonPrimitive(it)) } }
            }
            put("tools", geminiTools(ctx.raw["tools"]))
        }
    }

    private fun geminiParts(content: JsonElement?, toolCalls: JsonElement?): JsonArray {
        val parts = buildJsonArray {
            when (content) {
                is JsonArray -> content.forEach { part ->
                    when (part) {
                        is JsonObject -> {
                            if (part.string("type") == "image_url") {
                                val parsed = parseDataUrl(part.obj("image_url")?.string("url").orEmpty())
                                if (parsed != null) add(buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", parsed.first)
                                        put("data", parsed.second)
                                    }
                                })
                            } else {
                                add(buildJsonObject { put("text", part.string("text").orEmpty()) })
                            }
                        }
                        is JsonPrimitive -> add(buildJsonObject { put("text", part.contentOrNull.orEmpty()) })
                        else -> Unit
                    }
                }
                else -> {
                    val text = contentText(content)
                    if (text.isNotEmpty()) add(buildJsonObject { put("text", text) })
                }
            }
            (toolCalls as? JsonArray)?.forEach { callEl ->
                val call = callEl as? JsonObject ?: return@forEach
                val fn = call.obj("function") ?: return@forEach
                add(buildJsonObject {
                    putJsonObject("functionCall") {
                        put("name", fn.string("name") ?: "tool")
                        put("args", parseArguments(fn["arguments"]))
                    }
                })
            }
        }
        return if (parts.isEmpty()) {
            buildJsonArray { add(buildJsonObject { put("text", "") }) }
        } else {
            parts
        }
    }
    private fun geminiTools(tools: JsonElement?): JsonArray {
        val array = tools as? JsonArray ?: return JsonArray(emptyList())
        val declarations = buildJsonArray {
            array.forEach { el ->
                val tool = el as? JsonObject ?: return@forEach
                val fn = tool.obj("function") ?: tool
                add(buildJsonObject {
                    put("name", fn.string("name") ?: return@forEach)
                    put("description", fn.string("description").orEmpty())
                    put("parameters", fn["parameters"] ?: buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject { })
                    })
                })
            }
        }
        if (declarations.isEmpty()) return JsonArray(emptyList())
        return buildJsonArray { add(buildJsonObject { put("functionDeclarations", declarations) }) }
    }

    // ----------------------------------------------------------- antigravity

    private fun antigravityRequest(
        spec: ProviderSpec,
        baseUrl: String,
        creds: Map<String, String>,
        connection: ProviderConnection,
        upstreamModel: String,
        ctx: ChatContext
    ): Request {
        val project = creds["projectId"].orEmpty().ifBlank { Ids.generatedProjectId() }
        val sessionId = creds["sessionId"] ?: connection.id
        // Catalog entries encode the thinking tier as a "(high|medium|low)"
        // suffix. Google only knows the clean model id; the suffix becomes a
        // thinkingConfig entry, exactly like the web executor.
        val wireModel = antigravityWireModel(upstreamModel)
        val thinkingLevel = antigravityThinkingLevel(upstreamModel)
            ?.takeIf { wireModel.contains("gemini", ignoreCase = true) }
        val requestBody = geminiPayload(ctx)
        // Antigravity expects the Gemini payload nested under `request`, with its own wrapper.
        val inner = buildJsonObject {
            requestBody.forEach { (key, value) ->
                if (key == "generationConfig" && thinkingLevel != null) {
                    val generationConfig = value as? JsonObject
                    put("generationConfig", buildJsonObject {
                        generationConfig?.forEach { (configKey, configValue) ->
                            put(configKey, configValue)
                        }
                        // Gemini thinking needs room for its reasoning budget.
                        val floor = when (thinkingLevel) {
                            "minimal" -> 4_096
                            "low" -> 8_192
                            "medium" -> 16_384
                            else -> 64_000
                        }
                        val requested = (generationConfig?.get("maxOutputTokens") as? JsonPrimitive)
                            ?.intOrNull ?: 0
                        if (requested < floor) put("maxOutputTokens", floor)
                        putJsonObject("thinkingConfig") {
                            put("thinkingLevel", thinkingLevel)
                            put("includeThoughts", thinkingLevel != "minimal")
                        }
                    })
                } else {
                    put(key, value)
                }
            }
            put("sessionId", sessionId)
        }
        val payload = buildJsonObject {
            put("project", project)
            put("model", wireModel)
            put("userAgent", "antigravity")
            put("requestId", "agent/${connection.id}/${System.currentTimeMillis()}/${Ids.requestId()}/1")
            put("request", inner)
        }
        val action = if (ctx.stream) "v1internal:streamGenerateContent?alt=sse" else "v1internal:generateContent"
        val builder = Request.Builder()
            .url("$baseUrl/$action")
            .post(payload.toString().toRequestBody(MEDIA_JSON))
        builder.header("Content-Type", "application/json")
        builder.header("Authorization", "Bearer ${creds["accessToken"].orEmpty()}")
        // Google rejects newer Antigravity models (Gemini 4 Carbon/Argon) with
        // 404 "Requested entity was not found" when the client fingerprint is
        // old. Match the current web/IDE hub fingerprint.
        builder.header("User-Agent", "antigravity/hub/2.24.0 darwin/arm64")
        return builder.build()
    }

    // -------------------------------------------------------------- helpers

    private val antigravityTierSuffix = Regex("\\(([^()]+)\\)\\s*$")

    /** Removes the catalog's `(high|medium|low)` tier marker from a wire model id. */
    fun antigravityWireModel(upstreamModel: String): String =
        upstreamModel.replace(antigravityTierSuffix, "").trim()

    /** Extracts the thinking tier encoded in a catalog model id, if any. */
    fun antigravityThinkingLevel(upstreamModel: String): String? =
        antigravityTierSuffix.find(upstreamModel)
            ?.groupValues?.getOrNull(1)?.trim()?.lowercase()
            ?.takeIf { it in setOf("high", "medium", "low", "minimal") }

    private fun parseArguments(raw: JsonElement?): JsonElement {
        val text = (raw as? JsonPrimitive)?.contentOrNull
        if (text.isNullOrBlank()) return buildJsonObject { }
        return runCatching { Json.lenient.parseToJsonElement(text) }.getOrDefault(buildJsonObject { })
    }

    fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        val data = url.substring(comma + 1)
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        return mime to data
    }

    // -------------------------------------------------------- response shape

    /** Converts a buffered upstream payload into an OpenAI chat.completion object. */
    fun toOpenAiJson(prepared: PreparedCall, raw: String, requestModel: String): String {
        if (prepared.response.status !in 200..299) return raw
        return when (prepared.provider.format) {
            WireFormat.OPENAI, WireFormat.OLLAMA -> raw
            WireFormat.ANTHROPIC -> anthropicToOpenAi(prepared, raw, requestModel)
            WireFormat.GEMINI -> geminiToOpenAi(prepared, raw, requestModel, wrapped = false)
            WireFormat.GEMINI_CLI -> geminiToOpenAi(prepared, raw, requestModel, wrapped = true)
            WireFormat.ANTIGRAVITY -> geminiToOpenAi(prepared, raw, requestModel, wrapped = true)
        }
    }

    private fun anthropicToOpenAi(prepared: PreparedCall, raw: String, requestModel: String): String {
        val json = runCatching { Json.lenient.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return raw
        val text = (json["content"] as? JsonArray)?.mapNotNull { el ->
            val part = el as? JsonObject ?: return@mapNotNull null
            if (part.string("type") == "text") part.string("text") else null
        }.orEmpty().joinToString("")
        val usage = json.obj("usage")
        return buildJsonObject {
            put("id", json.string("id") ?: Ids.requestId())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", requestModel)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", mapFinish(json.string("stop_reason")))
                })
            }
            putJsonObject("usage") {
                put("prompt_tokens", usage?.int("input_tokens") ?: 0)
                put("completion_tokens", usage?.int("output_tokens") ?: 0)
                put("total_tokens", (usage?.int("input_tokens") ?: 0) + (usage?.int("output_tokens") ?: 0))
            }
        }.toString()
    }

    private fun geminiToOpenAi(
        prepared: PreparedCall,
        raw: String,
        requestModel: String,
        wrapped: Boolean
    ): String {
        val root = runCatching { Json.lenient.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
        val json = if (wrapped) root.obj("response") ?: root else root
        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = candidate?.obj("content")?.arr("parts")
        val text = parts?.mapNotNull { (it as? JsonObject)?.string("text") }.orEmpty().joinToString("")
        val usage = json.obj("usageMetadata")
        val prompt = usage?.int("promptTokenCount") ?: 0
        val completion = usage?.int("candidatesTokenCount") ?: 0
        return buildJsonObject {
            put("id", Ids.requestId())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", requestModel)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", mapFinish(candidate?.string("finishReason")))
                })
            }
            putJsonObject("usage") {
                put("prompt_tokens", prompt)
                put("completion_tokens", completion)
                put("total_tokens", prompt + completion)
            }
        }.toString()
    }

    private fun mapFinish(reason: String?): String = when (reason?.lowercase()) {
        "max_tokens", "length", "max_output_tokens" -> "length"
        "tool_use", "tool_calls" -> "tool_calls"
        "content_filter" -> "content_filter"
        else -> "stop"
    }

    // ------------------------------------------------------------- streaming

    /**
     * Writes an OpenAI-compatible SSE stream to [out]. OpenAI/Ollama responses are
     * relayed untouched; Anthropic and Gemini payloads are translated on the fly.
     */
    fun relayStream(prepared: PreparedCall, requestModel: String, out: OutputStream) {
        val stream = prepared.response.byteStream()
        if (prepared.provider.format == WireFormat.OPENAI || prepared.provider.format == WireFormat.OLLAMA) {
            stream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    out.flush()
                }
            }
            return
        }
        val id = Ids.requestId()
        val created = System.currentTimeMillis() / 1000
        var started = false
        var finish = "stop"
        fun emit(delta: JsonObject, finishReason: String? = null) {
            val chunk = buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", requestModel)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        put("finish_reason", finishReason)
                    })
                }
            }
            out.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            var data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                    line.isBlank() -> {
                        if (data.isNotEmpty()) {
                            val payload = data.toString()
                            data = StringBuilder()
                            val parsed = runCatching { Json.lenient.parseToJsonElement(payload).jsonObject }.getOrNull()
                            if (parsed != null) {
                                if (!started) {
                                    started = true
                                    emit(buildJsonObject { put("role", "assistant") })
                                }
                                val deltaText = extractDelta(prepared.provider.format, parsed)
                                if (deltaText.isNotEmpty()) {
                                    emit(buildJsonObject { put("content", deltaText) })
                                }
                                val reason = extractFinish(prepared.provider.format, parsed)
                                if (reason != null) finish = mapFinish(reason)
                            }
                        }
                    }
                }
            }
        }
        if (!started) emit(buildJsonObject { put("role", "assistant") })
        emit(buildJsonObject { }, finish)
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun extractDelta(format: WireFormat, json: JsonObject): String {
        return when (format) {
            WireFormat.OPENAI, WireFormat.OLLAMA -> ""
            WireFormat.ANTHROPIC -> {
                when (json.string("type")) {
                    "content_block_delta" -> json.obj("delta")?.string("text").orEmpty()
                    else -> ""
                }
            }
            WireFormat.GEMINI, WireFormat.GEMINI_CLI, WireFormat.ANTIGRAVITY -> {
                val root = if (format == WireFormat.ANTIGRAVITY || format == WireFormat.GEMINI_CLI) {
                    json.obj("response") ?: json
                } else {
                    json
                }
                val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                candidate?.obj("content")?.arr("parts")
                    ?.mapNotNull { (it as? JsonObject)?.string("text") }
                    .orEmpty()
                    .joinToString("")
            }
        }
    }

    private fun extractFinish(format: WireFormat, json: JsonObject): String? {
        return when (format) {
            WireFormat.ANTHROPIC -> json.obj("delta")?.string("stop_reason") ?: json.string("stop_reason")
            WireFormat.GEMINI, WireFormat.GEMINI_CLI, WireFormat.ANTIGRAVITY -> {
                val root = if (format == WireFormat.ANTIGRAVITY || format == WireFormat.GEMINI_CLI) {
                    json.obj("response") ?: json
                } else {
                    json
                }
                ((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("finishReason")
            }
            else -> null
        }
    }

    /** Non-streaming fallback for clients that asked for a stream but upstream cannot stream. */
    fun streamFromBuffered(prepared: PreparedCall, requestModel: String, raw: String, out: OutputStream) {
        val converted = toOpenAiJson(prepared, raw, requestModel)
        val json = runCatching { Json.lenient.parseToJsonElement(converted).jsonObject }.getOrNull()
        val text = json?.arr("choices")?.firstOrNull()?.let { (it as? JsonObject) }
            ?.obj("message")?.string("content").orEmpty()
        val id = Ids.requestId()
        val created = System.currentTimeMillis() / 1000
        val first = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", requestModel)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("delta") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", JsonNull)
                })
            }
        }
        out.write("data: $first\n\n".toByteArray(Charsets.UTF_8))
        val last = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", requestModel)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject { })
                    put("finish_reason", "stop")
                })
            }
        }
        out.write("data: $last\n\n".toByteArray(Charsets.UTF_8))
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Fetches model ids from a compatible endpoint's `/models`. Works for
     * OpenAI-style (`Authorization: Bearer`) and Anthropic-style
     * (`x-api-key` + version) servers.
     */
    suspend fun listModels(baseUrl: String, anthropic: Boolean, apiKey: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/models"
            val builder = Request.Builder().url(url).get()
                .header("Accept", "application/json")
            if (anthropic) {
                if (!apiKey.isNullOrBlank()) builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            } else if (!apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            plainClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw UpstreamException(
                        "HTTP ${response.code}: ${extractError(text).take(240)}"
                    )
                }
                val root = runCatching { Json.lenient.parseToJsonElement(text) }.getOrNull()
                val array = when (root) {
                    is JsonArray -> root
                    is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
                    else -> null
                } ?: throw UpstreamException("No model list returned by /models")
                array.mapNotNull { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull
                        is JsonObject -> element.string("id") ?: element.string("name")
                        else -> null
                    }
                }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
        }

    /** Probes a connection with a minimal request; used by the Provider test button. */
    suspend fun testConnection(
        spec: ProviderSpec,
        connection: ProviderConnection,
        model: String
    ): Pair<Boolean, String> {
        val ctx = ChatContext.from(
            buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "Reply with OK.")
                    })
                }
                put("max_tokens", 16)
                put("stream", false)
            }
        )
        return runCatching {
            call(spec, connection, model, ctx).use { prepared ->
                val body = prepared.response.text()
                if (prepared.response.isSuccess) {
                    true to "Connected · ${prepared.model}"
                } else {
                    false to "HTTP ${prepared.response.status}: ${extractError(body).take(220)}"
                }
            }
        }.getOrElse { false to (it.message ?: "Connection failed") }
    }

    fun extractError(body: String): String {
        if (body.isBlank()) return "empty response"
        return runCatching {
            val json = Json.lenient.parseToJsonElement(body).jsonObject
            val err = json["error"]
            when (err) {
                is JsonObject -> err.string("message") ?: err.toString()
                is JsonPrimitive -> err.contentOrNull ?: err.toString()
                else -> json.string("message") ?: body
            }
        }.getOrDefault(body)
    }

    /**
     * Uses the plain (finite timeout) client for short metadata calls such as the
     * Telegram API, so a dead network never blocks a coroutine forever.
     */
    fun simpleJson(url: String, body: JsonObject? = null, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.post(body.toString().toRequestBody(MEDIA_JSON))
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return plainClient.newCall(builder.build()).execute().use { response ->
            response.body?.string().orEmpty()
        }
    }
}
