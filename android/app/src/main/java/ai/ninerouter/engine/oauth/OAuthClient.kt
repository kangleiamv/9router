package ai.ninerouter.engine.oauth

import ai.ninerouter.util.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Instant? = null,
    val scope: String? = null,
    val email: String? = null,
    val projectId: String? = null
)

/**
 * One in-flight OAuth authorization. The caller opens [authUrl] in a browser
 * and then calls [awaitTokens] on a background dispatcher; the session owns the
 * loopback callback server and closes it in every exit path.
 */
class OAuthSession internal constructor(
    val provider: OAuthProviderConfig,
    val authUrl: String,
    val state: String,
    private val codeVerifier: String?,
    private val server: OAuthCallbackServer
) : AutoCloseable {

    fun awaitTokens(): OAuthTokens {
        try {
            val params = server.awaitCode(state)
            val error = params["error"]
            if (!error.isNullOrBlank()) {
                val description = params["error_description"] ?: error
                throw OAuthException("${provider.providerId}: $description")
            }
            val code = params["code"]
                ?: throw OAuthException("No authorization code received from ${provider.providerId}")
            val tokens = OAuthClient.exchange(provider, code, params["state"] ?: state, server.redirectUri, codeVerifier)
            val email = OAuthClient.fetchEmail(provider, tokens.accessToken)
            val projectId = provider.codeAssist?.let { kind ->
                OAuthClient.loadCodeAssist(provider, kind, tokens.accessToken)
            }
            return tokens.copy(email = email, projectId = projectId)
        } finally {
            close()
        }
    }

    override fun close() {
        server.close()
    }
}

object OAuthClient {
    private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
    private val random = SecureRandom()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun start(provider: OAuthProviderConfig): OAuthSession {
        val server = OAuthCallbackServer()
        val state = randomToken(32)
        val verifier = if (provider.pkce) randomToken(48) else null
        val challenge = verifier?.let { pkceChallenge(it) }
        val params = linkedMapOf(
            "client_id" to provider.clientId,
            "response_type" to "code",
            "redirect_uri" to server.redirectUri,
            "scope" to provider.scopes.joinToString(" "),
            "state" to state
        )
        if (challenge != null) {
            params["code_challenge"] = challenge
            params["code_challenge_method"] = "S256"
        }
        params.putAll(provider.extraAuthParams)
        val authUrl = provider.authorizeUrl + "?" + params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        return OAuthSession(provider, authUrl, state, verifier, server)
    }

    internal fun exchange(
        provider: OAuthProviderConfig,
        code: String,
        state: String,
        redirectUri: String,
        verifier: String?
    ): OAuthTokens {
        var authCode = code
        var authState = state
        if (authCode.contains('#')) {
            val parts = authCode.split('#', limit = 2)
            authCode = parts[0]
            if (parts.size > 1 && parts[1].isNotBlank()) authState = parts[1]
        }
        val request = when (provider.tokenFormat) {
            OAuthTokenFormat.JSON -> {
                val payload = buildJsonObject {
                    put("code", authCode)
                    put("state", authState)
                    put("grant_type", "authorization_code")
                    put("client_id", provider.clientId)
                    put("redirect_uri", redirectUri)
                    if (verifier != null) put("code_verifier", verifier)
                }
                Request.Builder()
                    .url(provider.tokenUrl)
                    .post(payload.toString().toRequestBody(MEDIA_JSON))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build()
            }
            OAuthTokenFormat.FORM -> {
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", provider.clientId)
                    .add("code", authCode)
                    .add("redirect_uri", redirectUri)
                provider.clientSecret?.let { form.add("client_secret", it) }
                if (verifier != null) form.add("code_verifier", verifier)
                Request.Builder()
                    .url(provider.tokenUrl)
                    .post(form.build())
                    .header("Accept", "application/json")
                    .build()
            }
        }
        return parseTokens(execute(request, "Token exchange"))
    }

    /** Refreshes an expired access token. Returns null when the provider rejects it. */
    fun refresh(provider: OAuthProviderConfig, refreshToken: String): OAuthTokens? {
        if (refreshToken.isBlank()) return null
        val request = when (provider.refreshFormat) {
            OAuthTokenFormat.JSON -> {
                val payload = buildJsonObject {
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("client_id", provider.clientId)
                    if (provider.refreshWithSecret) provider.clientSecret?.let { put("client_secret", it) }
                }
                Request.Builder()
                    .url(provider.tokenUrl)
                    .post(payload.toString().toRequestBody(MEDIA_JSON))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build()
            }
            OAuthTokenFormat.FORM -> {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", provider.clientId)
                if (provider.refreshWithSecret) provider.clientSecret?.let { form.add("client_secret", it) }
                Request.Builder()
                    .url(provider.tokenUrl)
                    .post(form.build())
                    .header("Accept", "application/json")
                    .build()
            }
        }
        return runCatching { parseTokens(execute(request, "Token refresh")) }.getOrNull()
    }

    internal fun fetchEmail(provider: OAuthProviderConfig, accessToken: String): String? {
        val url = provider.userInfoUrl ?: return null
        val builder = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
        if (provider.providerId == "claude") {
            builder.header("anthropic-beta", "oauth-2025-04-20")
            builder.header("anthropic-version", "2023-06-01")
            builder.header("User-Agent", "claude-cli/2.1.322 (external, sdk-cli)")
        }
        val json = runCatching {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                Json.lenient.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
        }.getOrNull() ?: return null
        return json.string("email")
            ?: json.obj("account")?.string("email_address")
            ?: json.obj("account")?.string("email")
    }

    internal fun loadCodeAssist(
        provider: OAuthProviderConfig,
        kind: CodeAssistKind,
        accessToken: String
    ): String? {
        val projectId = resolveProjectId(kind, accessToken)
        if (projectId.isNullOrBlank()) {
            throw OAuthException(
                "No Google Cloud project found for this account. Enable Gemini Code Assist for the account and try again."
            )
        }
        return projectId
    }

    /**
     * Resolves the Cloud project for a Code Assist OAuth account. Unlike
     * [loadCodeAssist] this never throws: request-time repair uses it so a
     * connection with a missing or stale project can recover on its own.
     */
    fun resolveProjectId(kind: CodeAssistKind, accessToken: String): String? {
        if (accessToken.isBlank()) return null
        val metadata = buildJsonObject {
            put("ideType", 9)
            put("platform", platformEnum())
            put("pluginType", 2)
        }
        val loaded = loadCodeAssistJson(kind, accessToken, metadata)
        val projectId = loaded?.let { extractProjectId(it) }
        if (!projectId.isNullOrBlank()) return projectId

        val tierId = loaded?.arr("allowedTiers")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["isDefault"] as? JsonPrimitive)?.contentOrNull == "true" }
            ?.string("id")
            ?.trim()
            ?: "legacy-tier"

        for (attempt in 0 until 4) {
            val onboarded = runCatching { onboard(accessToken, tierId, metadata) }.getOrNull()
            val finalProject = onboarded?.let { extractProjectId(it) }
            if (!finalProject.isNullOrBlank()) return finalProject
            if (onboarded?.boolean("done") == true) break
            if (attempt < 3) runCatching { Thread.sleep(2_500) }
        }
        return null
    }

    private fun loadCodeAssistJson(
        kind: CodeAssistKind,
        accessToken: String,
        metadata: JsonObject
    ): JsonObject? {
        val loadPayload = buildJsonObject {
            put("metadata", metadata)
            if (kind == CodeAssistKind.GEMINI_CLI) put("mode", 1)
        }
        val loadBuilder = Request.Builder()
            .url("https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist")
            .post(loadPayload.toString().toRequestBody(MEDIA_JSON))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
        if (kind == CodeAssistKind.ANTIGRAVITY) {
            loadBuilder.header("User-Agent", "antigravity/hub/2.24.0 darwin/arm64")
        } else {
            loadBuilder.header("User-Agent", "google-api-nodejs-client/9.15.1")
            loadBuilder.header("X-Goog-Api-Client", "google-cloud-sdk vscode_cloudshelleditor/0.1")
            loadBuilder.header("Client-Metadata", metadata.toString())
        }
        return runCatching {
            http.newCall(loadBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                Json.lenient.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
        }.getOrNull()
    }

    private fun platformEnum(): Int {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return if (arch.contains("aarch64") || arch.contains("arm64")) 4 else 3
    }

    private fun onboard(accessToken: String, tierId: String, metadata: JsonObject): JsonObject? {
        val payload = buildJsonObject {
            put("tierId", tierId)
            put("metadata", metadata)
        }
        val request = Request.Builder()
            .url("https://cloudcode-pa.googleapis.com/v1internal:onboardUser")
            .post(payload.toString().toRequestBody(MEDIA_JSON))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", "antigravity/hub/2.24.0 darwin/arm64")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = Json.lenient.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            // The final project id can arrive inside response.cloudaicompanionProject.
            val responseObj = json.obj("response")
            if (responseObj != null) {
                val project = extractProjectId(responseObj)
                if (!project.isNullOrBlank()) {
                    return@use buildJsonObject {
                        json.forEach { (key, value) -> put(key, value) }
                        put("projectId", project)
                    }
                }
            }
            json
        }
    }

    private fun extractProjectId(json: JsonObject): String? {
        val value = json["cloudaicompanionProject"] ?: json["projectId"] ?: return null
        return when (value) {
            is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> value.string("id")
            else -> null
        }
    }

    private fun parseTokens(json: JsonObject): OAuthTokens {
        val access = json.string("access_token")
            ?: throw OAuthException(json.string("error_description") ?: json.string("error") ?: "No access token returned")
        val expiresIn = json.string("expires_in")?.toLongOrNull()
        val refresh = json.string("refresh_token")
        return OAuthTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = Instant.now().plusSeconds(expiresIn ?: 3_600L),
            scope = json.string("scope")
        )
    }

    private fun execute(request: Request, label: String): JsonObject {
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { Json.lenient.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val detail = json?.string("error_description")
                    ?: json?.let { obj -> (obj["error"] as? JsonObject)?.string("message") }
                    ?: json?.string("error")
                    ?: "HTTP ${response.code}"
                throw OAuthException("$label failed: $detail")
            }
            json ?: throw OAuthException("$label returned an invalid response")
        }
    }

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

// ---------------------------------------------------------------- json helpers

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): List<kotlinx.serialization.json.JsonElement>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
