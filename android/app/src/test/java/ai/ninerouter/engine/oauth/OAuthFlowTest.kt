package ai.ninerouter.engine.oauth

import ai.ninerouter.util.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OAuthFlowTest {

    @Test
    fun `oauth registry exposes antigravity gemini cli and claude`() {
        val antigravity = OAuthProviders.byId("antigravity")
        val gemini = OAuthProviders.byId("gemini-cli")
        val claude = OAuthProviders.byId("claude")

        assertNotNull(antigravity)
        assertNotNull(gemini)
        assertNotNull(claude)
        assertEquals(CodeAssistKind.ANTIGRAVITY, antigravity!!.codeAssist)
        assertEquals(CodeAssistKind.GEMINI_CLI, gemini!!.codeAssist)
        assertEquals(false, antigravity.pkce)
        assertEquals(false, gemini.pkce)
        assertEquals(true, claude!!.pkce)
        assertEquals(OAuthTokenFormat.JSON, claude.tokenFormat)
        assertEquals(OAuthTokenFormat.JSON, claude.refreshFormat)
        assertEquals(false, claude.refreshWithSecret)
        assertEquals(3, OAuthProviders.all.size)
        assertTrue(OAuthProviders.supports("CLAUDE"))
        assertTrue(!OAuthProviders.supports("openai"))
    }

    @Test
    fun `loopback callback delivers a valid authorization code`() {
        val server = OAuthCallbackServer(timeoutMs = 10_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Map<String, String>> { server.awaitCode("state-123") }
            val connection = URL("${server.redirectUri}?code=abc%2Fdef&state=state-123")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                assertEquals(200, connection.responseCode)
                connection.inputStream.close()
            } finally {
                connection.disconnect()
            }
            val params = future.get(5, TimeUnit.SECONDS)
            assertEquals("abc/def", params["code"])
            assertEquals("state-123", params["state"])
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    @Test
    fun `extracts gemini cli project from string and object responses`() {
        val stringProject = Json.lenient.parseToJsonElement(
            """{"cloudaicompanionProject":"  my-project-123  "}"""
        ).jsonObject
        val objectProject = Json.lenient.parseToJsonElement(
            """{"cloudaicompanionProject":{"id":"object-project"}}"""
        ).jsonObject
        val directProject = Json.lenient.parseToJsonElement(
            """{"projectId":"direct-project"}"""
        ).jsonObject

        assertEquals("my-project-123", OAuthClient.extractProjectId(stringProject))
        assertEquals("object-project", OAuthClient.extractProjectId(objectProject))
        assertEquals("direct-project", OAuthClient.extractProjectId(directProject))
        assertNull(OAuthClient.extractProjectId(buildJsonObject { }))
        assertNull(
            OAuthClient.extractProjectId(
                Json.lenient.parseToJsonElement("""{"cloudaicompanionProject":"   "}""").jsonObject
            )
        )
    }

    @Test
    fun `gemini cli project provisioning uses the google api client fingerprint`() {
        val metadata = buildJsonObject {
            put("ideType", 9)
            put("platform", 4)
            put("pluginType", 2)
        }

        val gemini = OAuthClient.codeAssistHeaders(CodeAssistKind.GEMINI_CLI, metadata)
        val antigravity = OAuthClient.codeAssistHeaders(CodeAssistKind.ANTIGRAVITY, metadata)

        assertEquals("google-api-nodejs-client/9.15.1", gemini["User-Agent"])
        assertEquals(
            "google-cloud-sdk vscode_cloudshelleditor/0.1",
            gemini["X-Goog-Api-Client"]
        )
        assertEquals(metadata.toString(), gemini["Client-Metadata"])
        assertEquals("antigravity/hub/2.24.0 darwin/arm64", antigravity["User-Agent"])
        assertNull(antigravity["X-Goog-Api-Client"])
    }
}
