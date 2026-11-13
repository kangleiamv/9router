package ai.ninerouter.service.server

import ai.ninerouter.BuildConfig
import ai.ninerouter.core.AppContainer
import ai.ninerouter.core.AppRepository
import ai.ninerouter.core.ProxyRuntime
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.engine.ChatContext
import ai.ninerouter.engine.EngineResult
import ai.ninerouter.engine.FallbackPolicy
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.UpstreamClient
import ai.ninerouter.engine.boolean
import ai.ninerouter.engine.int
import ai.ninerouter.engine.string
import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Embedded OpenAI-compatible gateway. Binds to loopback only; the native Compose
 * dashboard talks to the repository directly while external clients (Termux,
 * `adb forward`, automation apps) use this HTTP surface.
 */
object KtorProxyServer {
    private var server: ApplicationEngine? = null

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(port: Int, repo: AppRepository): Boolean {
        if (server != null) return true
        return try {
            val engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                module(repo)
            }.start(wait = false)
            server = engine
            ProxyRuntime.markStarted(port)
            true
        } catch (t: Throwable) {
            ProxyRuntime.markError(t.message ?: "Unable to start proxy server")
            server = null
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.stop(500, 1500) }
        server = null
        ProxyRuntime.markStopped()
    }

    private fun Application.module(repo: AppRepository) {
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowHeader("x-api-key")
            allowHeader("anthropic-version")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Options)
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                runCatching {
                    call.respondText(
                        errorJson(cause.message ?: "Internal server error", "server_error", "internal_error"),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
        }
        routing {
            get("/") {
                call.respondText(
                    buildJsonObject {
                        put("name", "9Router Android")
                        put("version", BuildConfig.VERSION_NAME)
                        put("endpoint", ProxyRuntime.endpoint)
                        put("status", if (ProxyRuntime.running.value) "running" else "stopped")
                        put("docs", "/api/status")
                    }.toString(),
                    ContentType.Application.Json
                )
            }
            get("/api/health") {
                call.respondText(
                    buildJsonObject {
                        put("status", "ok")
                        put("uptime", ProxyRuntime.startedAt.value?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0)
                    }.toString(),
                    ContentType.Application.Json
                )
            }
            get("/api/status") {
                val connections = repo.providerDao.getAll()
                val keys = repo.apiKeyDao.getAll()
                val combos = repo.comboDao.getAll()
                call.respondText(
                    buildJsonObject {
                        put("running", ProxyRuntime.running.value)
                        put("port", ProxyRuntime.port.value)
                        put("endpoint", ProxyRuntime.endpoint)
                        put("lastError", ProxyRuntime.lastError.value)
                        put("connections", connections.size)
                        put("activeConnections", connections.count { it.isActive })
                        put("keys", keys.size)
                        put("combos", combos.size)
                        put("version", BuildConfig.VERSION_NAME)
                    }.toString(),
                    ContentType.Application.Json
                )
            }

            // ------------------------------------------------------- connections
            route("/api/connections") {
                get {
                    val list = repo.providerDao.getAll()
                    call.respondText(connectionsJson(list), ContentType.Application.Json)
                }
                post {
                    val body = readJson(call.receiveText())
                    val provider = body.string("provider") ?: return@post call.badRequest("provider is required")
                    val spec = ProviderCatalog.byId(provider)
                        ?: repo.customProviderDao.getById(provider)?.let(ProviderCatalog::customSpec)
                        ?: return@post call.badRequest("Unknown provider '$provider'")
                    val connection = repo.addConnection(
                        providerId = spec.id,
                        name = body.string("name").orEmpty(),
                        apiKey = body.string("apiKey"),
                        email = body.string("email"),
                        authType = body.string("authType") ?: spec.authType,
                        providerSpecificData = body.string("providerSpecificData"),
                        baseUrl = body.string("baseUrl")
                    )
                    call.respondText(connectionJson(connection), ContentType.Application.Json, HttpStatusCode.Created)
                }
                patch("/{id}") {
                    val id = call.parameters["id"] ?: return@patch call.badRequest("id is required")
                    val existing = repo.providerDao.getById(id)
                        ?: return@patch call.notFound("Connection not found")
                    val body = readJson(call.receiveText())
                    val updated = existing.copy(
                        name = body.string("name") ?: existing.name,
                        displayName = body.string("displayName") ?: existing.displayName,
                        defaultModel = body.string("defaultModel") ?: existing.defaultModel,
                        priority = body.int("priority") ?: existing.priority,
                        isActive = body.boolean("isActive") ?: existing.isActive,
                        apiKey = body.string("apiKey")?.let { Crypto.encrypt(it) } ?: existing.apiKey
                    )
                    repo.updateConnection(updated)
                    call.respondText(connectionJson(updated), ContentType.Application.Json)
                }
                delete("/{id}") {
                    val id = call.parameters["id"] ?: return@delete call.badRequest("id is required")
                    repo.deleteConnection(id)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
                post("/{id}/test") {
                    val id = call.parameters["id"] ?: return@post call.badRequest("id is required")
                    val connection = repo.providerDao.getById(id)
                        ?: return@post call.notFound("Connection not found")
                    val (ok, message) = repo.testConnection(connection)
                    call.respondText(
                        buildJsonObject {
                            put("ok", ok)
                            put("message", message)
                        }.toString(),
                        ContentType.Application.Json
                    )
                }
            }

            // ---------------------------------------------------- custom providers
            route("/api/provider-nodes") {
                get {
                    val nodes = repo.customProviderDao.getAll()
                    call.respondText(
                        buildJsonObject {
                            putJsonArray("nodes") { nodes.forEach { add(nodeJsonElement(it)) } }
                        }.toString(),
                        ContentType.Application.Json
                    )
                }
                get("/{id}") {
                    val id = call.parameters["id"] ?: return@get call.badRequest("id is required")
                    val node = repo.customProviderDao.getById(id)
                        ?: return@get call.notFound("Provider node not found")
                    call.respondText(
                        buildJsonObject { put("node", nodeJsonElement(node)) }.toString(),
                        ContentType.Application.Json
                    )
                }
                post {
                    val body = readJson(call.receiveText())
                    val apiKey = body.string("apiKey")
                    val node = repo.saveCustomProvider(
                        id = null,
                        type = body.string("type")
                            ?: return@post call.badRequest("type is required"),
                        name = body.string("name").orEmpty(),
                        prefix = body.string("prefix").orEmpty(),
                        apiType = body.string("apiType") ?: "chat",
                        baseUrl = body.string("baseUrl").orEmpty(),
                        models = (body["models"] as? JsonArray)
                            ?.mapNotNull { extractModel(it) }.orEmpty(),
                        apiKey = apiKey,
                        createConnection = body.boolean("createConnection") ?: !apiKey.isNullOrBlank()
                    )
                    call.respondText(
                        buildJsonObject { put("node", nodeJsonElement(node)) }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Created
                    )
                }
                put("/{id}") {
                    val id = call.parameters["id"] ?: return@put call.badRequest("id is required")
                    val existing = repo.customProviderDao.getById(id)
                        ?: return@put call.notFound("Provider node not found")
                    val body = readJson(call.receiveText())
                    val models = (body["models"] as? JsonArray)
                        ?.mapNotNull { extractModel(it) } ?: existing.modelIds()
                    val node = repo.saveCustomProvider(
                        id = existing.id,
                        type = body.string("type") ?: existing.type,
                        name = body.string("name") ?: existing.name,
                        prefix = body.string("prefix") ?: existing.prefix,
                        apiType = body.string("apiType") ?: existing.apiType,
                        baseUrl = body.string("baseUrl") ?: existing.baseUrl,
                        models = models
                    )
                    call.respondText(
                        buildJsonObject { put("node", nodeJsonElement(node)) }.toString(),
                        ContentType.Application.Json
                    )
                }
                delete("/{id}") {
                    val id = call.parameters["id"] ?: return@delete call.badRequest("id is required")
                    repo.deleteCustomProvider(id)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
                post("/{id}/discover") {
                    val id = call.parameters["id"] ?: return@post call.badRequest("id is required")
                    val body = readJson(call.receiveText())
                    val models = repo.discoverCustomModels(id, body.string("apiKey"))
                    call.respondText(
                        buildJsonObject {
                            putJsonArray("models") { models.forEach { add(JsonPrimitive(it)) } }
                        }.toString(),
                        ContentType.Application.Json
                    )
                }
                post("/validate") {
                    val body = readJson(call.receiveText())
                    val type = body.string("type") ?: "openai-compatible"
                    if (type != "openai-compatible" && type != "anthropic-compatible") {
                        return@post call.badRequest(
                            "type must be openai-compatible or anthropic-compatible"
                        )
                    }
                    val result = repo.probeCompatible(
                        type = type,
                        baseUrl = body.string("baseUrl").orEmpty(),
                        apiKey = body.string("apiKey").orEmpty(),
                        modelId = body.string("modelId")
                    )
                    call.respondText(
                        buildJsonObject {
                            put("valid", result.ok)
                            put("message", result.message)
                            if (result.models.isNotEmpty()) {
                                putJsonArray("models") {
                                    result.models.forEach { add(JsonPrimitive(it)) }
                                }
                            }
                        }.toString(),
                        ContentType.Application.Json
                    )
                }
            }

            // ------------------------------------------------------------ combos
            route("/api/combos") {
                get {
                    val list = repo.comboDao.getAll()
                    call.respondText(combosJson(list), ContentType.Application.Json)
                }
                post {
                    val body = readJson(call.receiveText())
                    val name = body.string("name")?.trim().orEmpty()
                    if (name.isBlank()) return@post call.badRequest("name is required")
                    val models = (body["models"] as? JsonArray)?.mapNotNull { extractModel(it) }.orEmpty()
                    if (models.isEmpty()) return@post call.badRequest("models must not be empty")
                    val combo = repo.saveCombo(null, name, models, body.string("kind"))
                    call.respondText(comboJson(combo), ContentType.Application.Json, HttpStatusCode.Created)
                }
                put("/{id}") {
                    val id = call.parameters["id"] ?: return@put call.badRequest("id is required")
                    val existing = repo.comboDao.getById(id) ?: return@put call.notFound("Combo not found")
                    val body = readJson(call.receiveText())
                    val models = (body["models"] as? JsonArray)?.mapNotNull { extractModel(it) }
                    val combo = repo.saveCombo(
                        id = id,
                        name = body.string("name") ?: existing.name,
                        models = models ?: runCatching {
                            (Json.lenient.parseToJsonElement(existing.models) as JsonArray)
                                .mapNotNull { extractModel(it) }
                        }.getOrDefault(emptyList()),
                        kind = body.string("kind") ?: existing.kind
                    )
                    call.respondText(comboJson(combo), ContentType.Application.Json)
                }
                delete("/{id}") {
                    val id = call.parameters["id"] ?: return@delete call.badRequest("id is required")
                    repo.deleteCombo(id)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
            }

            // -------------------------------------------------------------- keys
            route("/api/keys") {
                get {
                    val keys = repo.apiKeyDao.getAll()
                    call.respondText(keysJson(keys), ContentType.Application.Json)
                }
                post {
                    val body = readJson(call.receiveText())
                    val key = repo.createKey(body.string("name").orEmpty())
                    call.respondText(keyJson(key), ContentType.Application.Json, HttpStatusCode.Created)
                }
                patch("/{id}") {
                    val id = call.parameters["id"] ?: return@patch call.badRequest("id is required")
                    val key = repo.apiKeyDao.getAll().firstOrNull { it.id == id }
                        ?: return@patch call.notFound("Key not found")
                    val body = readJson(call.receiveText())
                    val updated = key.copy(
                        name = body.string("name") ?: key.name,
                        isActive = body.boolean("isActive") ?: key.isActive
                    )
                    repo.apiKeyDao.upsert(updated)
                    call.respondText(keyJson(updated), ContentType.Application.Json)
                }
                delete("/{id}") {
                    val id = call.parameters["id"] ?: return@delete call.badRequest("id is required")
                    repo.deleteKey(id)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
            }

            // ---------------------------------------------------------- settings
            route("/api/settings") {
                get {
                    val map = repo.settingsMap()
                    call.respondText(
                        buildJsonObject {
                            putJsonObject("settings") { map.forEach { (k, v) -> put(k, v) } }
                        }.toString(),
                        ContentType.Application.Json
                    )
                }
                put {
                    val body = readJson(call.receiveText())
                    body.forEach { (key, value) ->
                        val text = when (value) {
                            is JsonPrimitive -> value.content
                            else -> value.toString()
                        }
                        repo.putSetting(key, text)
                    }
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
            }

            // ------------------------------------------------------------- usage
            get("/api/usage") {
                val requests = repo.requests
                val list = requests.first()
                call.respondText(requestsJson(list), ContentType.Application.Json)
            }
            get("/api/models") {
                call.respondText(repo.engine.listModels(), ContentType.Application.Json)
            }

            // ---------------------------------------------------------- telegram
            post("/api/telegram/test") {
                val body = readJson(call.receiveText())
                val settings = repo.settingsMap()
                val token = body.string("token") ?: settings["telegram_bot_token"].orEmpty()
                val chatId = body.string("chatId") ?: settings["telegram_chat_id"].orEmpty()
                if (token.isBlank() || chatId.isBlank()) {
                    return@post call.badRequest("Bot token and chat id are required")
                }
                val (ok, message) = repo.sendTelegram(token, chatId, "✅ 9Router Android is connected.")
                call.respondText(
                    buildJsonObject {
                        put("ok", ok)
                        put("message", message)
                    }.toString(),
                    ContentType.Application.Json
                )
            }

            // --------------------------------------------------------- OpenAI API
            post("/v1/chat/completions") { handleChat(call, repo) }
            post("/api/v1/chat/completions") { handleChat(call, repo) }
            post("/chat/completions") { handleChat(call, repo) }
            get("/v1/models") {
                call.respondText(repo.engine.listModels(), ContentType.Application.Json)
            }
            get("/models") {
                call.respondText(repo.engine.listModels(), ContentType.Application.Json)
            }
        }
    }

    private suspend fun handleChat(call: io.ktor.server.application.ApplicationCall, repo: AppRepository) {
        val raw = call.receiveText()
        val body = runCatching { readJson(raw) }.getOrElse {
            return call.badRequest("Invalid JSON body: ${it.message}")
        }
        val (authorized, _) = repo.engine.authenticate(
            call.request.headers[HttpHeaders.Authorization],
            call.request.headers["x-api-key"]
        )
        if (!authorized) {
            return call.respondText(
                errorJson("Invalid API key provided", "authentication_error", "invalid_api_key"),
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
        }
        when (val result = repo.engine.handleChat(body)) {
            is EngineResult.Buffered -> call.respondText(
                result.body,
                ContentType.parse(result.contentType),
                HttpStatusCode.fromValue(result.status)
            )
            is EngineResult.Stream -> {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header("X-Accel-Buffering", "no")
                call.respondOutputStream(ContentType.Text.EventStream, HttpStatusCode.OK) {
                    result.producer(this)
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun readJson(text: String): JsonObject =
        Json.lenient.parseToJsonElement(text).jsonObject

    private fun extractModel(el: JsonElement): String? = when (el) {
        is JsonPrimitive -> el.content
        is JsonObject -> el.string("model")
            ?: el.string("id")
            ?: (el.string("provider")?.let { p -> el.string("modelId")?.let { "$p/$it" } })
        else -> null
    }

    private suspend fun io.ktor.server.application.ApplicationCall.badRequest(message: String) {
        respondText(errorJson(message, "invalid_request_error", "bad_request"), ContentType.Application.Json, HttpStatusCode.BadRequest)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.notFound(message: String) {
        respondText(errorJson(message, "invalid_request_error", "not_found"), ContentType.Application.Json, HttpStatusCode.NotFound)
    }

    private fun errorJson(message: String, type: String, code: String): String = buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", type)
            put("code", code)
        }
    }.toString()

    private fun connectionJson(connection: ProviderConnection): String = buildJsonObject {
        put("id", connection.id)
        put("provider", connection.provider)
        put("name", connection.name)
        put("email", connection.email)
        put("authType", connection.authType)
        put("apiKey", Crypto.mask(connection.apiKey))
        put("priority", connection.priority)
        put("isActive", connection.isActive)
        put("testStatus", connection.testStatus)
        put("lastError", connection.lastError)
        put("lastErrorAt", connection.lastErrorAt)
        put("backoffLevel", connection.backoffLevel)
        put("lastUsedAt", connection.lastUsedAt)
        put("defaultModel", connection.defaultModel)
        put("displayName", connection.displayName)
        put("cooldownUntil", FallbackPolicy.earliestModelLock(connection)?.let {
            Instant.ofEpochMilli(it).toString()
        })
        put("createdAt", connection.createdAt)
        put("updatedAt", connection.updatedAt)
    }.toString()

    private fun connectionsJson(list: List<ProviderConnection>): String =
        "[${list.joinToString(",") { connectionJson(it) }}]"

    private fun nodeJsonElement(node: CustomProvider): JsonObject = buildJsonObject {
        put("id", node.id)
        put("type", node.type)
        put("name", node.name)
        put("prefix", node.prefix)
        put("apiType", node.apiType)
        put("baseUrl", node.baseUrl)
        putJsonArray("models") { node.modelIds().forEach { add(JsonPrimitive(it)) } }
        put("createdAt", node.createdAt)
        put("updatedAt", node.updatedAt)
    }

    private fun comboJson(combo: ai.ninerouter.data.model.Combo): String = buildJsonObject {
        put("id", combo.id)
        put("name", combo.name)
        put("kind", combo.kind)
        put("models", runCatching { Json.lenient.parseToJsonElement(combo.models) }.getOrDefault(JsonArray(emptyList())))
        put("createdAt", combo.createdAt)
        put("updatedAt", combo.updatedAt)
    }.toString()

    private fun combosJson(list: List<ai.ninerouter.data.model.Combo>): String =
        "[${list.joinToString(",") { comboJson(it) }}]"

    private fun keyJson(key: ai.ninerouter.data.model.ApiKey): String = buildJsonObject {
        put("id", key.id)
        put("name", key.name)
        put("key", key.key)
        put("isActive", key.isActive)
        put("createdAt", key.createdAt)
        put("lastUsedAt", key.lastUsedAt)
    }.toString()

    private fun keysJson(list: List<ai.ninerouter.data.model.ApiKey>): String =
        "[${list.joinToString(",") { keyJson(it) }}]"

    private fun requestsJson(list: List<ai.ninerouter.data.model.RequestDetail>): String = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            list.forEach { detail ->
                add(buildJsonObject {
                    put("id", detail.id)
                    put("timestamp", detail.timestamp)
                    put("provider", detail.provider)
                    put("model", detail.model)
                    put("connectionId", detail.connectionId)
                    put("connectionName", detail.connectionName)
                    put("status", detail.status)
                    put("latencyMs", detail.latencyMs)
                    put("promptTokens", detail.promptTokens)
                    put("completionTokens", detail.completionTokens)
                    put("error", detail.error)
                })
            }
        }
    }.toString()
}
