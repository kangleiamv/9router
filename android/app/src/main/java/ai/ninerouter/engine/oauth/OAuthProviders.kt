package ai.ninerouter.engine.oauth

/**
 * Registry of providers that can be connected with a real OAuth sign-in on
 * Android. The flow is standard "authorization code" with a loopback redirect
 * (`http://localhost:<port>/callback`), exactly like the desktop 9Router CLI:
 * the browser is opened with the provider's consent page and the app listens on
 * a random loopback port for the one-time code.
 */
enum class OAuthTokenFormat { FORM, JSON }

enum class CodeAssistKind { ANTIGRAVITY, GEMINI_CLI }

data class OAuthProviderConfig(
    val providerId: String,
    val buttonLabel: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String?,
    val scopes: List<String>,
    val pkce: Boolean = false,
    val tokenFormat: OAuthTokenFormat = OAuthTokenFormat.FORM,
    val extraAuthParams: Map<String, String> = emptyMap(),
    val userInfoUrl: String? = null,
    val codeAssist: CodeAssistKind? = null,
    val refreshFormat: OAuthTokenFormat = OAuthTokenFormat.FORM,
    val refreshWithSecret: Boolean = true
)

object OAuthProviders {
    private const val ANTIGRAVITY_CLIENT_ID =
        "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    private const val ANTIGRAVITY_CLIENT_SECRET = "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"
    private const val GEMINI_CLI_CLIENT_ID =
        "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    private const val GEMINI_CLI_CLIENT_SECRET = "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"

    val antigravity = OAuthProviderConfig(
        providerId = "antigravity",
        buttonLabel = "Sign in with Google",
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        clientId = ANTIGRAVITY_CLIENT_ID,
        clientSecret = ANTIGRAVITY_CLIENT_SECRET,
        scopes = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs"
        ),
        extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        userInfoUrl = "https://www.googleapis.com/oauth2/v1/userinfo",
        codeAssist = CodeAssistKind.ANTIGRAVITY
    )

    val geminiCli = OAuthProviderConfig(
        providerId = "gemini-cli",
        buttonLabel = "Sign in with Google",
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        clientId = GEMINI_CLI_CLIENT_ID,
        clientSecret = GEMINI_CLI_CLIENT_SECRET,
        scopes = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile"
        ),
        extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        userInfoUrl = "https://www.googleapis.com/oauth2/v1/userinfo",
        codeAssist = CodeAssistKind.GEMINI_CLI
    )

    val claude = OAuthProviderConfig(
        providerId = "claude",
        buttonLabel = "Sign in with Claude",
        authorizeUrl = "https://claude.ai/oauth/authorize",
        tokenUrl = "https://api.anthropic.com/v1/oauth/token",
        clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
        clientSecret = null,
        scopes = listOf("org:create_api_key", "user:profile", "user:inference"),
        pkce = true,
        tokenFormat = OAuthTokenFormat.JSON,
        extraAuthParams = mapOf("code" to "true"),
        userInfoUrl = "https://api.anthropic.com/api/oauth/profile",
        refreshFormat = OAuthTokenFormat.JSON,
        refreshWithSecret = false
    )

    val all: List<OAuthProviderConfig> = listOf(antigravity, geminiCli, claude)

    fun byId(providerId: String): OAuthProviderConfig? =
        all.firstOrNull { it.providerId.equals(providerId, ignoreCase = true) }

    fun supports(providerId: String): Boolean = byId(providerId) != null
}
