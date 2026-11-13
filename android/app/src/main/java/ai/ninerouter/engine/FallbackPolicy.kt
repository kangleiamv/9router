package ai.ninerouter.engine

import ai.ninerouter.data.model.ProviderConnection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Direct port of open-sse/services/accountFallback.js and open-sse/config/errorConfig.js.
 * `shouldFallback` answers "is the credential at fault?"; `requestScoped` answers
 * "is the request itself what this member rejected?" — combo rotation uses the latter.
 */
data class FallbackDecision(
    val shouldFallback: Boolean,
    val cooldownMs: Long,
    val newBackoffLevel: Int = 0,
    val modelScoped: Boolean = false,
    val requestScoped: Boolean = false
)

object FallbackPolicy {
    const val BACKOFF_BASE_MS = 2_000L
    const val BACKOFF_MAX_MS = 5 * 60 * 1000L
    const val BACKOFF_MAX_LEVEL = 15
    const val TRANSIENT_COOLDOWN_MS = 30_000L
    const val MAX_RATE_LIMIT_COOLDOWN_MS = 30 * 60 * 1000L
    const val LONG_COOLDOWN_MS = 2 * 60 * 1000L
    const val SHORT_COOLDOWN_MS = 5_000L
    const val MODEL_LOCK_PREFIX = "modelLock_"
    const val MODEL_LOCK_ALL = "modelLock___all"

    private data class TextRule(
        val text: String,
        val provider: String? = null,
        val cooldownMs: Long = 0,
        val backoff: Boolean = false,
        val modelScoped: Boolean = false
    )

    private val textRules = listOf(
        TextRule("model is not supported when using codex with a chatgpt account", provider = "codex", cooldownMs = MAX_RATE_LIMIT_COOLDOWN_MS),
        TextRule("request illegal", provider = "codebuddy-intl", backoff = true),
        TextRule("conversation id missing", provider = "codebuddy-intl", backoff = true),
        TextRule("degraded", provider = "nvidia", cooldownMs = 15 * 60 * 1000L, modelScoped = true),
        TextRule("no credentials", cooldownMs = LONG_COOLDOWN_MS),
        TextRule("request not allowed", cooldownMs = SHORT_COOLDOWN_MS),
        TextRule("improperly formed request", cooldownMs = LONG_COOLDOWN_MS),
        TextRule("rate limit", backoff = true),
        TextRule("too many requests", backoff = true),
        TextRule("quota exceeded", backoff = true),
        TextRule("insufficient credit", backoff = true),
        TextRule("credit balance is too low", backoff = true),
        TextRule("capacity", backoff = true),
        TextRule("overloaded", backoff = true)
    )

    fun quotaCooldown(backoffLevel: Int): Long {
        val level = maxOf(0, backoffLevel - 1)
        val cooldown = BACKOFF_BASE_MS * (1L shl level.coerceAtMost(20))
        return minOf(cooldown, BACKOFF_MAX_MS)
    }

    fun check(status: Int, errorText: String?, backoffLevel: Int, provider: String?): FallbackDecision {
        val lower = errorText.orEmpty().lowercase()
        for (rule in textRules) {
            if (rule.provider != null && rule.provider != provider) continue
            if (lower.isNotEmpty() && lower.contains(rule.text)) {
                return if (rule.backoff) {
                    val newLevel = minOf(backoffLevel + 1, BACKOFF_MAX_LEVEL)
                    FallbackDecision(true, quotaCooldown(newLevel), newLevel)
                } else {
                    FallbackDecision(true, rule.cooldownMs, modelScoped = rule.modelScoped)
                }
            }
        }
        return when (status) {
            401, 402, 403 -> FallbackDecision(true, LONG_COOLDOWN_MS)
            429 -> {
                val newLevel = minOf(backoffLevel + 1, BACKOFF_MAX_LEVEL)
                FallbackDecision(true, quotaCooldown(newLevel), newLevel)
            }
            in 400..499 -> FallbackDecision(false, 0, requestScoped = true)
            else -> FallbackDecision(true, TRANSIENT_COOLDOWN_MS)
        }
    }

    // ----- model locks (stored flat inside providerSpecificData, like the web app) -----

    fun lockKey(model: String?): String =
        if (model.isNullOrBlank()) MODEL_LOCK_ALL else MODEL_LOCK_PREFIX + model

    fun isModelLockActive(connection: ProviderConnection, model: String?): Boolean {
        val data = connection.specificData()
        val now = Instant.now().toEpochMilli()
        val keys = listOfNotNull(lockKey(model), MODEL_LOCK_ALL).distinct()
        return keys.any { key ->
            val expiry = data[key]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            expiry != null && expiry > now
        }
    }

    fun earliestModelLock(connection: ProviderConnection): Long? {
        val data = connection.specificData()
        val now = Instant.now().toEpochMilli()
        return data.entries
            .filter { it.key.startsWith(MODEL_LOCK_PREFIX) }
            .mapNotNull { runCatching { Instant.parse(it.value).toEpochMilli() }.getOrNull() }
            .filter { it > now }
            .minOrNull()
    }

    fun applyModelLock(connection: ProviderConnection, model: String?, cooldownMs: Long): ProviderConnection {
        val updated = connection.specificData().toMutableMap()
        updated[lockKey(model)] = Instant.now().plusMillis(cooldownMs).toString()
        return connection.withSpecificData(updated)
    }

    fun clearModelLocks(connection: ProviderConnection): ProviderConnection {
        val updated = connection.specificData().filterKeys { !it.startsWith(MODEL_LOCK_PREFIX) }
        return connection.withSpecificData(updated)
    }

    /** Reads the connection-level cooldown (`rateLimitedUntil`) kept in providerSpecificData. */
    fun isUnavailable(connection: ProviderConnection): Boolean {
        if (connection.testStatus !in listOf("unavailable", "rate_limited", "error")) return false
        val until = connection.specificData()["rateLimitedUntil"] ?: return false
        val expiry = runCatching { Instant.parse(until).toEpochMilli() }.getOrNull() ?: return false
        return expiry > Instant.now().toEpochMilli()
    }
}

fun ProviderConnection.specificData(): Map<String, String> {
    val raw = providerSpecificData ?: return emptyMap()
    return runCatching {
        val obj = ai.ninerouter.util.Json.lenient.parseToJsonElement(raw) as JsonObject
        obj.entries.mapNotNull { (k, v) ->
            val text = (v as? JsonPrimitive)?.content
            if (text != null) k to text else null
        }.toMap()
    }.getOrDefault(emptyMap())
}

fun ProviderConnection.withSpecificData(values: Map<String, String>): ProviderConnection {
    val json = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }
    return copy(providerSpecificData = json.toString())
}
