package ai.ninerouter.engine.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
