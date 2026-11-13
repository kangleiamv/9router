package ai.ninerouter.service

import ai.ninerouter.core.AppRepository
import ai.ninerouter.engine.EngineEvents
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.util.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Telegram bot bridge: Bot API long-polling runs inside the foreground service,
 * so no webhook, no external server and no WebView are involved. Conversations are
 * routed through the same ProxyEngine used by the OpenAI endpoint.
 */
class TelegramBot(private val repo: AppRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val histories = mutableMapOf<Long, MutableList<Pair<String, String>>>()
    @Volatile private var cachedSettings: Map<String, String> = emptyMap()
    @Volatile private var lastToken: String = ""

    fun start() {
        if (job?.isActive == true) return
        EngineEvents.onAllUnavailable = { message ->
            scope.launch { notifyOwner(message) }
        }
        job = scope.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        EngineEvents.onAllUnavailable = null
    }

    fun refresh() {
        scope.launch { cachedSettings = repo.settingsMap() }
    }

    private suspend fun pollLoop() {
        var offset = 0L
        var backoff = 1_000L
        while (scope.isActive) {
            val settings = runCatching { repo.settingsMap() }.getOrDefault(cachedSettings)
            cachedSettings = settings
            val enabled = settings["telegram_enabled"] == "true"
            val token = settings["telegram_bot_token"].orEmpty()
            if (!enabled || token.isBlank()) {
                delay(2_000)
                continue
            }
            if (token != lastToken) {
                lastToken = token
                deleteWebhook(token)
                offset = 0
            }
            val result = runCatching { getUpdates(token, offset) }
            val body = result.getOrNull()
            if (body == null) {
                delay(backoff)
                backoff = minOf(backoff * 2, 15_000)
                continue
            }
            backoff = 1_000
            val json = runCatching { Json.lenient.parseToJsonElement(body).jsonObject }.getOrNull()
            if (json == null) {
                delay(1_000)
                continue
            }
            if (json["ok"]?.jsonPrimitive?.contentOrNull != "true") {
                val description = json["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (description.contains("webhook", ignoreCase = true)) {
                    deleteWebhook(token)
                }
                delay(3_000)
                continue
            }
            val updates = (json["result"] as? JsonArray).orEmpty()
            for (update in updates) {
                val updateJson = update as? JsonObject ?: continue
                val updateId = (updateJson["update_id"] as? JsonPrimitive)?.intOrNull ?: 0
                if (updateId >= offset) offset = updateId + 1L
                runCatching { handleUpdate(token, updateJson) }
            }
            if (updates.isEmpty()) delay(500)
        }
    }

    private suspend fun getUpdates(token: String, offset: Long): String = withContext(Dispatchers.IO) {
        ai.ninerouter.engine.UpstreamClient.simpleJson(
            "https://api.telegram.org/bot$token/getUpdates?timeout=25&offset=$offset&allowed_updates=%5B%22message%22%5D"
        )
    }

    private suspend fun deleteWebhook(token: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                ai.ninerouter.engine.UpstreamClient.simpleJson(
                    "https://api.telegram.org/bot$token/deleteWebhook?drop_pending_updates=false"
                )
            }
        }
    }

    private suspend fun handleUpdate(token: String, update: JsonObject) {
        val message = update.objSafe("message") ?: return
        val chat = message.objSafe("chat") ?: return
        val chatId = (chat["id"] as? JsonPrimitive)?.contentOrNull ?: return
        val text = message.stringSafe("text")?.trim().orEmpty()
        if (text.isEmpty()) return
        val firstName = message.objSafe("from")?.stringSafe("first_name").orEmpty()

        if (text.startsWith("/")) {
            handleCommand(chatId, text, firstName)
        } else {
            handleChat(chatId, text)
        }
    }

    private suspend fun handleCommand(chatId: String, raw: String, firstName: String) {
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val command = parts[0].substringAfter('/').substringBefore('@').lowercase()
        val argument = parts.getOrNull(1)?.trim().orEmpty()
        when (command) {
            "start", "help" -> send(
                chatId,
                buildString {
                    append("👋 Hello")
                    if (firstName.isNotBlank()) append(", $firstName")
                    append("!\n\n9Router Android is running on your device.\n\n")
                    append("*Commands*\n")
                    append("/model — list models or /model provider/model\n")
                    append("/combo — list combos or /combo name\n")
                    append("/vision — use the vision combo\n")
                    append("/status — proxy and connection status\n")
                    append("/quota — today's usage\n")
                    append("/clear — reset this conversation\n\n")
                    append("Send any text to chat.")
                }
            )
            "model" -> if (argument.isBlank()) {
                send(chatId, modelListText())
            } else {
                repo.putSetting("telegram_default_model", argument)
                repo.putSetting("telegram_combo", "")
                send(chatId, "✅ Model set to `$argument`.")
            }
            "combo" -> if (argument.isBlank()) {
                val combos = repo.comboDao.getAll()
                val text = if (combos.isEmpty()) {
                    "No combos yet. Create one in the Combos tab."
                } else {
                    "🎯 *Combos*\n" + combos.joinToString("\n") { "• ${it.name}" } +
                        "\n\nUse /combo <name> to activate one."
                }
                send(chatId, text)
            } else {
                val combo = repo.comboDao.getByName(argument)
                if (combo == null) {
                    send(chatId, "Combo `$argument` not found.")
                } else {
                    repo.putSetting("telegram_combo", combo.name)
                    repo.putSetting("telegram_default_model", "")
                    send(chatId, "✅ Combo `${combo.name}` activated.")
                }
            }
            "vision" -> {
                val vision = repo.comboDao.getAll().firstOrNull { it.kind == "vision" }
                if (vision == null) {
                    send(chatId, "No vision combo configured. Create one with kind `vision` in the Combos tab.")
                } else {
                    repo.putSetting("telegram_combo", vision.name)
                    send(chatId, "👁 Vision combo `${vision.name}` activated.")
                }
            }
            "status" -> {
                val connections = repo.providerDao.getAll()
                val active = connections.count { it.isActive && it.testStatus != "unavailable" }
                val settings = repo.settingsMap()
                val combo = settings["telegram_combo"].orEmpty()
                val model = settings["telegram_default_model"].orEmpty()
                send(
                    chatId,
                    buildString {
                        append("📊 *9Router status*\n")
                        append("Proxy: ${if (ai.ninerouter.core.ProxyRuntime.running.value) "running" else "stopped"}\n")
                        append("Endpoint: ${ai.ninerouter.core.ProxyRuntime.endpoint}\n")
                        append("Connections: $active/${connections.size} available\n")
                        append("Combos: ${repo.comboDao.getAll().size}\n")
                        append("Chat route: ${combo.ifBlank { model.ifBlank { "not configured" } }}")
                    }
                )
            }
            "quota" -> {
                val usage = repo.providerUsageFlow().first()
                val text = if (usage.isEmpty()) {
                    "No requests recorded today."
                } else {
                    "📈 *Today's usage*\n" + usage.joinToString("\n") {
                        "• ${it.name}: ${it.requests} req · ${it.tokens} tokens" +
                            if (it.errors > 0) " · ${it.errors} errors" else ""
                    }
                }
                send(chatId, text)
            }
            "clear" -> {
                histories.remove(chatId.toLongOrNull() ?: 0L)
                send(chatId, "🧹 Conversation cleared.")
            }
            else -> send(chatId, "Unknown command. Try /help.")
        }
    }

    private suspend fun handleChat(chatId: String, text: String) {
        val settings = repo.settingsMap()
        val combo = settings["telegram_combo"].orEmpty()
        val configuredModel = settings["telegram_default_model"].orEmpty()
        val customSpecs = repo.customSpecs()
        val route = combo.ifBlank { configuredModel }.ifBlank {
            repo.comboDao.getAll().firstOrNull()?.name
                ?: repo.providerDao.getAll().firstOrNull()?.let { connection ->
                    val spec = ProviderCatalog.byId(connection.provider)
                        ?: customSpecs.firstOrNull { it.id == connection.provider }
                    val prefix = if (spec?.isCompatible == true) {
                        spec.alias ?: connection.provider
                    } else {
                        connection.provider
                    }
                    spec?.models?.firstOrNull()?.let { "$prefix/${it.id}" }
                }.orEmpty()
        }
        if (route.isBlank()) {
            send(
                chatId,
                "No model available yet.\n\nAdd a provider in the Providers tab, then use /model to pick one."
            )
            return
        }
        sendChatAction(chatId)
        val history = histories.getOrPut(chatId.toLongOrNull() ?: 0L) { mutableListOf() }
        history += "user" to text
        if (history.size > 24) {
            while (history.size > 24) history.removeAt(0)
        }
        val reply = withTimeoutOrNull(180_000) {
            repo.chatInternal(route, history.toList(), settings["telegram_system_prompt"])
        }
        if (reply.isNullOrBlank()) {
            send(chatId, "⚠️ No response from `$route`. Check the connection in the app.")
            return
        }
        history += "assistant" to reply
        send(chatId, reply)
    }

    private suspend fun modelListText(): String {
        val customSpecs = repo.customSpecs()
        val specs = ProviderCatalog.allWith(customSpecs)
        val grouped = ProviderCatalog.modelsForSelection(customSpecs).groupBy { it.first }
        return buildString {
            append("🤖 *Available models*\n")
            grouped.forEach { (provider, models) ->
                val spec = specs.firstOrNull { it.id == provider }
                val prefix = if (spec?.isCompatible == true) spec.alias ?: provider else provider
                append("\n*${spec?.name ?: provider}*\n")
                models.take(8).forEach { (_, id, name) ->
                    append("• `$prefix/$id` — $name\n")
                }
            }
            append("\nUse /model prefix/model to select one.")
        }
    }

    private suspend fun send(chatId: String, text: String) {
        val settings = cachedSettings.ifEmpty { repo.settingsMap() }
        val token = settings["telegram_bot_token"].orEmpty()
        if (token.isBlank()) return
        cachedSettings = settings
        repo.sendTelegram(token, chatId, text)
    }

    private suspend fun sendChatAction(chatId: String) {
        val token = cachedSettings["telegram_bot_token"].orEmpty()
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                ai.ninerouter.engine.UpstreamClient.simpleJson(
                    "https://api.telegram.org/bot$token/sendChatAction?chat_id=$chatId&action=typing"
                )
            }
        }
    }

    private suspend fun notifyOwner(message: String) {
        val settings = runCatching { repo.settingsMap() }.getOrDefault(cachedSettings)
        if (settings["telegram_enabled"] != "true") return
        if (settings["telegram_notifications"] != "true") return
        val token = settings["telegram_bot_token"].orEmpty()
        val chatId = settings["telegram_chat_id"].orEmpty()
        if (token.isBlank() || chatId.isBlank()) return
        repo.sendTelegram(token, chatId, message)
    }

    private fun JsonObject.objSafe(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.stringSafe(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
