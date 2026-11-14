package ai.ninerouter.engine

import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** One quota window returned by a provider's usage API. */
data class QuotaWindow(
    val name: String,
    val used: Double,
    val total: Double,
    val remainingPercent: Double,
    val resetAt: String? = null,
    val unlimited: Boolean = false,
    val credit: Boolean = false,
    val unit: String? = null
) {
    val usedPercent: Double get() = if (total > 0) (used / total) * 100.0 else 0.0
}

/** Live quota snapshot for one provider connection. */
data class ProviderQuota(
    val connectionId: String,
    val provider: String,
    val plan: String? = null,
    val windows: List<QuotaWindow> = emptyList(),
    val message: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val hasWindows: Boolean get() = windows.isNotEmpty()
}

/**
 * Native provider-quota client. It mirrors the usage handlers used by the web
 * dashboard for the transports the Android app can connect to, so the Quota
 * screen can show Antigravity/Gemini/Claude limits instead of only cooldowns.
 */
object ProviderQuotaClient {
    private const val ANTIGRAVITY_UA = "antigravity/hub/2.24.0 darwin/arm64"
    private const val ANTIGRAVITY_BASE = "https://daily-cloudcode-pa.googleapis.com"
    private const val CLOUD_CODE_BASE = "https://cloudcode-pa.googleapis.com"
    private const val CLAUDE_UA = "claude-cli/2.1.322 (external, cli)"
    private const val CLAUDE_API_VERSION = "2023-06-01"

    private val mediaJson = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(28, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(connection: ProviderConnection, force: Boolean = false): ProviderQuota =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val credentials = runCatching { UpstreamClient.resolveCredentials(connection) }
                .getOrElse { error ->
                    return@withContext ProviderQuota(
                        connectionId = connection.id,
                        provider = connection.provider,
                        message = error.message ?: "Could not refresh credentials",
                        fetchedAt = startedAt
                    )
                }
            val key = credentials["apiKey"].orEmpty()
            val access = credentials["accessToken"].orEmpty()
            val result = runCatching {
                when (connection.provider) {
                    "antigravity" -> fetchAntigravity(credentials, access.ifBlank { key })
                    "gemini-cli" -> fetchGeminiCli(credentials, access.ifBlank { key })
                    "claude" -> fetchClaude(access.ifBlank { key })
                    "deepseek" -> fetchDeepSeek(key)
                    "groq" -> fetchGroq(key)
                    "glm", "glm-cn" -> fetchGlm(key, connection.provider)
                    "opencode-go" -> fetchOpenCode(key, "https://opencode.ai/inference/go/v1/usage", "OpenCode Go")
                    "opencode-zen" -> fetchOpenCode(key, "https://opencode.ai/zen/v1/usage", "OpenCode Zen")
                    "opencode" -> fetchOpenCode(key, "https://opencode.ai/zen/v1/usage", "OpenCode")
                    "vercel-ai-gateway" -> fetchVercelGateway(key)
                    "ollama-cloud", "ollama" -> fetchOllamaCloud(key)
                    "minimax", "minimax-cn" -> fetchMiniMax(key, connection.provider)
                    else -> ProviderQuota(
                        connectionId = connection.id,
                        provider = connection.provider,
                        message = "Live quota API is not implemented for this provider yet."
                    )
                }
            }.getOrElse { error ->
                ProviderQuota(
                    connectionId = connection.id,
                    provider = connection.provider,
                    message = error.message ?: "Could not fetch provider quota"
                )
            }
            result.copy(
                connectionId = connection.id,
                provider = connection.provider,
                fetchedAt = System.currentTimeMillis()
            )
        }

    // ------------------------------------------------------------- Antigravity

    private fun fetchAntigravity(credentials: Map<String, String>, token: String): ProviderQuota {
        if (token.isBlank()) {
            return message("Connect Antigravity to see its quota.")
        }
        val metadata = buildJsonObject {
            put("ideType", 9)
            put("platform", platformEnum())
            put("pluginType", 2)
        }
        val subscription = runCatching {
            postJson(
                "$CLOUD_CODE_BASE/v1internal:loadCodeAssist",
                buildJsonObject {
                    put("metadata", metadata)
                    put("mode", 1)
                },
                mapOf(
                    "Authorization" to "Bearer $token",
                    "User-Agent" to ANTIGRAVITY_UA
                )
            )
        }.getOrNull()
        val project = subscription?.string("cloudaicompanionProject")
            ?: credentials["projectId"]
        val paidTier = subscription?.obj("paidTier")?.string("id")
        val plan = subscription?.obj("currentTier")?.string("name")
            ?: if (paidTier.isNullOrBlank() || paidTier == "free-tier") "Free" else "Paid"
        val windows = linkedMapOf<String, QuotaWindow>()

        // Weekly / 5h family summary works for both free and paid accounts and
        // is far more readable than dozens of per-model buckets.
        val summaryBody = buildJsonObject {
            if (!project.isNullOrBlank()) put("project", project)
        }
        val summary = runCatching {
            postJson(
                "$ANTIGRAVITY_BASE/v1internal:retrieveUserQuotaSummary",
                summaryBody,
                mapOf(
                    "Authorization" to "Bearer $token",
                    "User-Agent" to ANTIGRAVITY_UA,
                    "X-Client-Name" to "antigravity",
                    "X-Client-Version" to "2.24.0"
                )
            )
        }.getOrNull()
        parseQuotaGroups(summary).forEach { (name, window) -> windows[name] = window }

        // Paid fallback: if the summary is unavailable, surface the tightest
        // per-model 5h buckets instead of showing an empty card.
        if (windows.isEmpty() && !paidTier.isNullOrBlank() && paidTier != "free-tier") {
            val body = buildJsonObject {
                if (!project.isNullOrBlank()) put("project", project)
            }
            val models = runCatching {
                postJson(
                    "$ANTIGRAVITY_BASE/v1internal:fetchAvailableModels",
                    body,
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "User-Agent" to ANTIGRAVITY_UA,
                        "X-Client-Name" to "antigravity",
                        "X-Client-Version" to "2.24.0"
                    )
                ).obj("models")
            }.getOrNull()
            models?.forEach { (modelId, raw) ->
                val info = raw as? JsonObject ?: return@forEach
                if (info.boolean("isInternal") == true) return@forEach
                val quota = info.obj("quotaInfo") ?: return@forEach
                val fraction = quota.double("remainingFraction") ?: return@forEach
                val name = info.string("displayName") ?: modelId
                windows[name] = quotaWindow(
                    name = name,
                    remainingPercent = fraction * 100.0,
                    resetAt = parseResetAt(quota["resetTime"])
                )
            }
        }

        val familyOrder = listOf("Gemini 4 Argon", "Gemini", "Claude & GPT")
        val ordered = windows.values.sortedWith(
            compareBy<QuotaWindow> { window ->
                familyOrder.indexOfFirst { prefix -> window.name.startsWith(prefix) }
                    .let { if (it < 0) familyOrder.size else it }
            }.thenBy { it.name.contains("Weekly", ignoreCase = true) }
                .thenBy { it.remainingPercent }
        ).take(8)
        return ProviderQuota(
            connectionId = "",
            provider = "antigravity",
            plan = plan,
            windows = ordered,
            message = if (ordered.isEmpty()) "Antigravity connected. No quota buckets were returned." else null
        )
    }

    internal fun parseQuotaGroups(root: JsonObject?): Map<String, QuotaWindow> {
        if (root == null) return emptyMap()
        val groups = root.arr("groups")
            ?: root.obj("quotaSummary")?.arr("groups")
            ?: return emptyMap()
        val out = LinkedHashMap<String, QuotaWindow>()
        groups.forEach { rawGroup ->
            val group = rawGroup as? JsonObject ?: return@forEach
            val groupName = group.string("displayName").orEmpty()
            val family = when {
                groupName.contains("argon", ignoreCase = true) -> "Gemini 4 Argon"
                groupName.contains("claude", ignoreCase = true) ||
                    groupName.contains("gpt", ignoreCase = true) -> "Claude & GPT"
                groupName.contains("gemini", ignoreCase = true) -> "Gemini"
                else -> groupName.ifBlank { "Quota" }
            }
            group.arr("buckets")?.forEach { rawBucket ->
                val bucket = rawBucket as? JsonObject ?: return@forEach
                val windowName = bucket.string("window").orEmpty().lowercase()
                val marker = "${bucket.string("bucketId").orEmpty()} ${bucket.string("displayName").orEmpty()}".lowercase()
                val weekly = windowName == "weekly" || marker.contains("weekly")
                val session = windowName == "5h" || windowName == "daily" ||
                    marker.contains("five hour") || marker.contains("5h") ||
                    marker.contains("daily")
                if (!weekly && !session) return@forEach
                if (weekly && bucket.boolean("disabled") == true) return@forEach
                val fraction = if (bucket.boolean("disabled") == true) 0.0
                else bucket.double("remainingFraction") ?: return@forEach
                val name = "$family (${if (weekly) "Weekly" else "5h"})"
                out[name] = quotaWindow(
                    name = name,
                    remainingPercent = fraction * 100.0,
                    resetAt = parseResetAt(bucket["resetTime"])
                )
            }
        }
        return out
    }

    // --------------------------------------------------------------- Gemini CLI

    private fun fetchGeminiCli(credentials: Map<String, String>, token: String): ProviderQuota {
        if (token.isBlank()) return message("Reconnect Gemini CLI to see quota.")
        val project = credentials["projectId"]
        if (project.isNullOrBlank()) {
            return message("Gemini CLI project is missing. Tap Test on the connection to retry Code Assist setup.")
        }
        val data = postJson(
            "$CLOUD_CODE_BASE/v1internal:retrieveUserQuota",
            buildJsonObject { put("project", project) },
            mapOf("Authorization" to "Bearer $token")
        )
        val windows = data.arr("buckets").orEmpty().mapNotNull { raw ->
            val bucket = raw as? JsonObject ?: return@mapNotNull null
            val model = bucket.string("modelId") ?: return@mapNotNull null
            val fraction = bucket.double("remainingFraction") ?: return@mapNotNull null
            quotaWindow(
                name = ProviderCatalog.displayName("gemini-cli", model),
                remainingPercent = fraction * 100.0,
                resetAt = parseResetAt(bucket["resetTime"])
            )
        }.sortedBy { it.remainingPercent }.take(8)
        return ProviderQuota(
            connectionId = "",
            provider = "gemini-cli",
            plan = "Gemini Code Assist",
            windows = windows,
            message = if (windows.isEmpty()) "Gemini CLI connected. No quota buckets were returned." else null
        )
    }

    // ------------------------------------------------------------------- Claude

    private fun fetchClaude(token: String): ProviderQuota {
        if (token.isBlank()) return message("Reconnect Claude to see its quota.")
        val response = runCatching {
            getJson(
                "https://api.anthropic.com/api/oauth/usage?cedar_ember=1",
                mapOf(
                    "Authorization" to "Bearer $token",
                    "anthropic-beta" to "oauth-2025-04-20",
                    "anthropic-version" to CLAUDE_API_VERSION,
                    "User-Agent" to CLAUDE_UA
                )
            )
        }.getOrElse { error ->
            return message(error.message ?: "Claude usage request failed.")
        }
        val windows = mutableListOf<QuotaWindow>()
        response.obj("five_hour")?.let { window ->
            utilizationWindow("Session (5h)", window)?.let(windows::add)
        }
        response.obj("seven_day")?.let { window ->
            utilizationWindow("Weekly (7d)", window)?.let(windows::add)
        }
        response.entries
            .filter { it.key.startsWith("seven_day_") && it.key != "seven_day" }
            .sortedBy { it.key }
            .forEach { (name, raw) ->
                val window = raw as? JsonObject ?: return@forEach
                val label = name.removePrefix("seven_day_").replace('_', ' ')
                utilizationWindow("Weekly $label (7d)", window)?.let(windows::add)
            }
        response.arr("limits").orEmpty().forEach { raw ->
            val limit = raw as? JsonObject ?: return@forEach
            if (limit.string("kind") != "weekly_scoped") return@forEach
            val label = limit.obj("scope")?.obj("model")?.string("display_name")
                ?: return@forEach
            val percent = limit.double("percent") ?: return@forEach
            windows += quotaWindow(
                name = "Weekly ${label.lowercase()} (7d)",
                remainingPercent = (100.0 - percent).coerceIn(0.0, 100.0),
                resetAt = parseResetAt(limit["resets_at"])
            )
        }
        return ProviderQuota(
            connectionId = "",
            provider = "claude",
            plan = "Claude Code",
            windows = windows.distinctBy { it.name }.sortedBy { it.remainingPercent }.take(8),
            message = if (windows.isEmpty()) "Claude connected. No quota windows were returned." else null
        )
    }

    internal fun utilizationWindow(name: String, window: JsonObject): QuotaWindow? {
        val used = window.double("utilization") ?: return null
        return quotaWindow(
            name = name,
            remainingPercent = (100.0 - used).coerceIn(0.0, 100.0),
            resetAt = parseResetAt(window["resets_at"])
        )
    }

    // ------------------------------------------------------------------ Key APIs

    private fun fetchDeepSeek(key: String): ProviderQuota {
        if (key.isBlank()) return message("Add a DeepSeek API key to see its balance.")
        val data = getJson(
            "https://api.deepseek.com/user/balance",
            mapOf("Authorization" to "Bearer $key")
        )
        val windows = data.arr("balance_infos").orEmpty().mapNotNull { raw ->
            val info = raw as? JsonObject ?: return@mapNotNull null
            val currency = info.string("currency")?.uppercase() ?: return@mapNotNull null
            val balance = info.double("total_balance") ?: return@mapNotNull null
            QuotaWindow(
                name = "Balance ($currency)",
                used = 0.0,
                total = balance,
                remainingPercent = if (balance > 0) 100.0 else 0.0,
                credit = true,
                unit = currency
            )
        }
        val available = data.boolean("is_available") != false
        return ProviderQuota(
            connectionId = "",
            provider = "deepseek",
            plan = if (available) "DeepSeek" else "DeepSeek · insufficient balance",
            windows = windows,
            message = if (windows.isEmpty()) "DeepSeek connected. No balance data returned." else null
        )
    }

    private fun fetchGroq(key: String): ProviderQuota {
        if (key.isBlank()) return message("Add a Groq API key to see rate limits.")
        val (requests, tokens) = getJsonWithHeaders(
            "https://api.groq.com/openai/v1/models",
            mapOf("Authorization" to "Bearer $key")
        ).let { (_, headers) ->
            buildRateWindow(headers["x-ratelimit-limit-requests"], headers["x-ratelimit-remaining-requests"], headers["x-ratelimit-reset-requests"], "Requests") to
                buildRateWindow(headers["x-ratelimit-limit-tokens"], headers["x-ratelimit-remaining-tokens"], headers["x-ratelimit-reset-tokens"], "Tokens")
        }
        val windows = listOfNotNull(requests, tokens)
        return ProviderQuota(
            connectionId = "",
            provider = "groq",
            plan = "Groq",
            windows = windows,
            message = if (windows.isEmpty()) "Groq connected. No rate-limit headers were reported." else null
        )
    }

    private fun buildRateWindow(limitRaw: String?, remainingRaw: String?, resetRaw: String?, name: String): QuotaWindow? {
        val limit = limitRaw?.toDoubleOrNull() ?: return null
        val remaining = remainingRaw?.toDoubleOrNull() ?: return null
        if (limit <= 0) return null
        val reset = parseDuration(resetRaw)?.let { Instant.now().plusMillis(it).toString() }
        return quotaWindow(
            name = name,
            remainingPercent = (remaining / limit * 100.0).coerceIn(0.0, 100.0),
            resetAt = reset
        )
    }

    private fun fetchGlm(key: String, provider: String): ProviderQuota {
        if (key.isBlank()) return message("Add a GLM API key to see plan limits.")
        val url = if (provider == "glm-cn")
            "https://open.bigmodel.cn/api/monitor/usage/quota/limit"
        else "https://api.z.ai/api/monitor/usage/quota/limit"
        val data = getJson(url, mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"))
        val body = data.obj("data") ?: data
        val windows = body.arr("limits").orEmpty().mapNotNull { raw ->
            val limit = raw as? JsonObject ?: return@mapNotNull null
            val type = limit.string("type") ?: return@mapNotNull null
            if (type != "TOKENS_LIMIT" && type != "CREDIT_LIMIT") return@mapNotNull null
            val used = limit.double("percentage") ?: 0.0
            val unit = limit.int("unit")
            val name = when (unit) {
                3 -> "Session (${limit.int("number") ?: 5}h)"
                6 -> "Weekly (7d)"
                else -> if (type == "TOKENS_LIMIT") "Tokens" else "Credits"
            }
            quotaWindow(
                name = name,
                remainingPercent = (100.0 - used).coerceIn(0.0, 100.0),
                resetAt = limit.double("nextResetTime")?.toLong()?.let {
                    if (it > 0) Instant.ofEpochMilli(it).toString() else null
                }
            )
        }
        val level = body.string("level")?.replaceFirstChar { it.uppercase() }
        return ProviderQuota(
            connectionId = "",
            provider = provider,
            plan = level ?: "GLM Coding",
            windows = windows,
            message = if (windows.isEmpty()) "GLM connected. No plan limits were returned." else null
        )
    }

    private fun fetchOpenCode(key: String, url: String, plan: String): ProviderQuota {
        if (key.isBlank()) return message("Add an API key to see $plan usage.")
        val data = getJson(url, mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"))
        val usage = data.obj("usage")
            ?: return message("$plan connected. No usage payload was returned.")
        val windows = listOf(
            "rolling" to "Rolling",
            "weekly" to "Weekly",
            "monthly" to "Monthly"
        ).mapNotNull { (keyName, label) ->
            val item = usage.obj(keyName) ?: return@mapNotNull null
            val used = item.double("percent") ?: return@mapNotNull null
            quotaWindow(
                name = label,
                remainingPercent = (100.0 - used).coerceIn(0.0, 100.0),
                resetAt = parseResetAt(item["resetsAt"])
            )
        }
        return ProviderQuota(
            connectionId = "",
            provider = "",
            plan = plan,
            windows = windows,
            message = if (windows.isEmpty()) "$plan connected. No quota windows were returned." else null
        )
    }

    private fun fetchVercelGateway(key: String): ProviderQuota {
        if (key.isBlank()) return message("Add a Vercel AI Gateway API key to see credits.")
        val data = getJson(
            "https://ai-gateway.vercel.sh/v1/credits",
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        )
        val balance = data.double("balance") ?: 0.0
        val used = data.double("total_used") ?: 0.0
        if (balance <= 0 && used <= 0) {
            return ProviderQuota(
                connectionId = "",
                provider = "vercel-ai-gateway",
                plan = "Pay-as-you-go",
                message = "No credit allocation found (BYOK or unfunded account)."
            )
        }
        val monthly = 5.0
        return ProviderQuota(
            connectionId = "",
            provider = "vercel-ai-gateway",
            plan = "Pay-as-you-go",
            windows = listOf(
                QuotaWindow(
                    name = "Remaining (USD)",
                    used = (monthly - balance).coerceIn(0.0, monthly),
                    total = monthly,
                    remainingPercent = (balance / monthly * 100.0).coerceIn(0.0, 100.0),
                    unit = "USD"
                ),
                QuotaWindow(
                    name = "Used (USD)",
                    used = used,
                    total = 0.0,
                    remainingPercent = 100.0,
                    unlimited = true,
                    unit = "USD"
                )
            )
        )
    }

    private fun fetchOllamaCloud(key: String): ProviderQuota {
        if (key.isBlank()) return message("Add an Ollama Cloud API key to see usage limits.")
        val data = getJson(
            "https://ollama.com/api/usage",
            mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        )
        val limits = data.obj("limits") ?: JsonObject(emptyMap())
        val labels = listOf(
            "session" to "Session (5h)",
            "weekly" to "Weekly (7d)",
            "monthly" to "Monthly"
        )
        var windows = labels.mapNotNull { (keyName, label) ->
            val ratio = limits.obj(keyName)?.double("usage") ?: return@mapNotNull null
            QuotaWindow(
                name = label,
                used = (ratio * 100).coerceIn(0.0, 100.0),
                total = 100.0,
                remainingPercent = ((1 - ratio) * 100).coerceIn(0.0, 100.0)
            )
        }
        if (windows.isEmpty()) {
            val balance = runCatching {
                getJson(
                    "https://ollama.com/api/balance",
                    mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
                )
            }.getOrNull()
            val included = balance?.obj("included")
            if (included != null) {
                listOf("session" to "Session (5h)", "weekly" to "Weekly (7d)").forEach { (keyName, label) ->
                    val item = included.obj(keyName) ?: return@forEach
                    val rem = item.double("remaining_percent") ?: return@forEach
                    windows += quotaWindow(
                        name = label,
                        remainingPercent = rem.coerceIn(0.0, 100.0),
                        resetAt = parseResetAt(item["resets_at"])
                    )
                }
                val allowance = included.double("allowance_usd")
                val remaining = included.double("balance_usd")
                if (allowance != null && allowance > 0 && remaining != null) {
                    windows += QuotaWindow(
                        name = "Allowance (USD)",
                        used = (allowance - remaining).coerceAtLeast(0.0),
                        total = allowance,
                        remainingPercent = (remaining / allowance * 100).coerceIn(0.0, 100.0),
                        resetAt = parseResetAt(included.obj("period")?.get("until")),
                        unit = "USD"
                    )
                }
            }
        }
        return ProviderQuota(
            connectionId = "",
            provider = "ollama-cloud",
            plan = "Ollama Cloud",
            windows = windows,
            message = if (windows.isEmpty()) "Ollama Cloud connected. No usage limits reported." else null
        )
    }

    private fun fetchMiniMax(key: String, provider: String): ProviderQuota {
        if (key.isBlank()) return message("Add a MiniMax API key to see plan limits.")
        val urls = if (provider == "minimax-cn") listOf(
            "https://www.minimaxi.com/v1/api/openplatform/coding_plan/remains",
            "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains"
        ) else listOf(
            "https://www.minimax.io/v1/token_plan/remains",
            "https://api.minimax.io/v1/api/openplatform/coding_plan/remains"
        )
        var lastError: String? = null
        for (url in urls) {
            try {
                val data = getJson(
                    url,
                    mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
                )
                val rows = data.arr("model_remains")
                    ?: data.arr("modelRemains")
                    ?: emptyList()
                val windows = rows.flatMap { raw ->
                    val model = raw as? JsonObject ?: return@flatMap emptyList()
                    val nameRaw = model.string("model_name") ?: model.string("modelName")
                        ?: return@flatMap emptyList()
                    if (nameRaw.isBlank()) return@flatMap emptyList()
                    val name = if (nameRaw == "MiniMax-M*" || nameRaw == "general") "M-series" else nameRaw
                    val countMeansRemaining = url.contains("/coding_plan/remains")
                    val sessionRem = miniMaxPercent(model, "current_interval", countMeansRemaining)
                    val weeklyRem = miniMaxPercent(model, "current_weekly", countMeansRemaining)
                    val sessionReset = parseResetAt(
                        model["end_time"] ?: model["endTime"]
                            ?: model["remains_time"] ?: model["remainsTime"]
                    )
                    val weeklyReset = parseResetAt(
                        model["weekly_end_time"] ?: model["weeklyEndTime"]
                            ?: model["weekly_remains_time"] ?: model["weeklyRemainsTime"]
                    )
                    listOfNotNull(
                        sessionRem?.let {
                            quotaWindow("$name (5h)", remainingPercent = it, resetAt = sessionReset)
                        },
                        weeklyRem?.let {
                            quotaWindow("$name (7d)", remainingPercent = it, resetAt = weeklyReset)
                        }
                    )
                }
                if (windows.isNotEmpty()) {
                    return ProviderQuota(
                        connectionId = "",
                        provider = provider,
                        plan = "MiniMax",
                        windows = windows.sortedBy { it.remainingPercent }.take(8)
                    )
                }
                lastError = "MiniMax connected. No quota data was returned."
            } catch (error: Exception) {
                lastError = error.message
            }
        }
        return message(lastError ?: "MiniMax connected. Unable to fetch usage.")
    }

    /**
     * Normalizes MiniMax percent-only buckets and older count-based buckets.
     * `/coding_plan/remains` reports the count as remaining; `/token_plan/
     * remains` reports it as used.
     */
    internal fun miniMaxPercent(model: JsonObject, prefix: String, countMeansRemaining: Boolean): Double? {
        val camel = if (prefix == "current_interval") "currentInterval" else "currentWeekly"
        val provided = model.double("${prefix}_remaining_percent")
            ?: model.double("${camel}RemainingPercent")
        if (provided != null) return provided.coerceIn(0.0, 100.0)
        val total = model.double("${prefix}_total_count")
            ?: model.double("${camel}TotalCount")
            ?: return null
        val count = model.double("${prefix}_usage_count")
            ?: model.double("${camel}UsageCount")
            ?: return null
        if (total <= 0.0) return null
        val remaining = if (countMeansRemaining) count else total - count
        return (remaining / total * 100.0).coerceIn(0.0, 100.0)
    }

    // ------------------------------------------------------------------ Helpers

    private fun message(text: String) = ProviderQuota(
        connectionId = "",
        provider = "",
        message = text
    )

    private fun quotaWindow(name: String, remainingPercent: Double, resetAt: String?): QuotaWindow {
        val remaining = remainingPercent.coerceIn(0.0, 100.0)
        return QuotaWindow(
            name = name,
            used = 100.0 - remaining,
            total = 100.0,
            remainingPercent = remaining,
            resetAt = resetAt
        )
    }

    private fun getJson(url: String, headers: Map<String, String>): JsonObject =
        getJsonWithHeaders(url, headers).first

    private fun getJsonWithHeaders(url: String, headers: Map<String, String>): Pair<JsonObject, Map<String, String>> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractError(text).take(180).ifBlank { "HTTP ${response.code}" })
            }
            val json = Json.lenient.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("Provider returned invalid JSON")
            val responseHeaders = buildMap {
                response.headers.names().forEach { name ->
                    response.headers.get(name)?.let { put(name.lowercase(), it) }
                }
            }
            return json to responseHeaders
        }
    }

    private fun postJson(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(mediaJson))
            .header("Content-Type", "application/json")
        headers.forEach { (key, value) -> builder.header(key, value) }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(extractError(text).take(180).ifBlank { "HTTP ${response.code}" })
            }
            return Json.lenient.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("Provider returned invalid JSON")
        }
    }

    private fun extractError(raw: String): String {
        if (raw.isBlank()) return ""
        val obj = runCatching { Json.lenient.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        return obj?.obj("error")?.string("message")
            ?: obj?.string("message")
            ?: raw
    }

    private fun platformEnum(): Int {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return if (arch.contains("aarch64") || arch.contains("arm64")) 4 else 3
    }

    internal fun parseResetAt(element: JsonElement?): String? {
        val text = when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element.string("until") ?: element.string("resets_at")
            else -> null
        }?.trim().orEmpty()
        if (text.isBlank()) return null
        text.toDoubleOrNull()?.let { value ->
            val millis = if (value < 1_000_000_000_000.0) (value * 1000).toLong() else value.toLong()
            return runCatching { Instant.ofEpochMilli(millis).toString() }.getOrNull()
        }
        return runCatching { Instant.parse(text).toString() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toString() }
            .getOrNull()
    }

    internal fun parseDuration(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val regex = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h)")
        var total = 0.0
        var matched = false
        regex.findAll(raw).forEach { match ->
            matched = true
            val amount = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = match.groupValues[2]
            total += amount * when (unit) {
                "h" -> 3_600_000.0
                "m" -> 60_000.0
                "ms" -> 1.0
                else -> 1_000.0
            }
        }
        return if (matched) total.toLong() else null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.double(key: String): Double? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return raw.toDoubleOrNull()
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
