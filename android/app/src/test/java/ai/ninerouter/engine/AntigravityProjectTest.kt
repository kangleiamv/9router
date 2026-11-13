package ai.ninerouter.engine

import ai.ninerouter.util.Ids
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityProjectTest {
    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 404) "Not Found" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    @Test
    fun `generated fallback project matches antigravity shape`() {
        val ids = (0 until 50).map { Ids.generatedProjectId() }

        ids.forEach { id ->
            assertTrue("unexpected project id: $id", id.matches(Regex("^[a-z]+-[a-z]+-[0-9a-f]{5}$")))
        }
        assertTrue("project ids should not repeat", ids.toSet().size > 1)
    }

    @Test
    fun `detects google unknown entity 404`() {
        val response = response(
            404,
            """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND"}}"""
        )

        assertTrue(UpstreamClient.isUnknownEntityResponse(response))
        response.close()
    }

    @Test
    fun `ignores other 404 responses`() {
        val response = response(404, """{"error":{"code":404,"message":"Model not found"}}""")

        assertFalse(UpstreamClient.isUnknownEntityResponse(response))
        response.close()
    }

    @Test
    fun `ignores unknown entity text outside 404`() {
        val response = response(500, """{"error":{"message":"Requested entity was not found."}}""")

        assertFalse(UpstreamClient.isUnknownEntityResponse(response))
        response.close()
    }

    @Test
    fun `matches unknown entity text in test connection errors`() {
        assertTrue(
            UpstreamClient.isUnknownEntityMessage(
                "HTTP 404: Requested entity was not found."
            )
        )
        assertFalse(
            UpstreamClient.isUnknownEntityMessage("HTTP 429: Resource exhausted")
        )
    }

    @Test
    fun `strips antigravity tier suffix from wire model`() {
        assertTrue(
            UpstreamClient.antigravityWireModel("gemini-4-carbon-high(high)") ==
                "gemini-4-carbon-high"
        )
        assertTrue(
            UpstreamClient.antigravityWireModel("gemini-3.7-flash-tiered(medium)") ==
                "gemini-3.7-flash-tiered"
        )
        assertTrue(
            UpstreamClient.antigravityWireModel("claude-sonnet-5-5(high)") ==
                "claude-sonnet-5-5"
        )
        assertTrue(UpstreamClient.antigravityWireModel("gemini-pro-agent") == "gemini-pro-agent")
    }

    @Test
    fun `extracts antigravity thinking level from model id`() {
        assertTrue(UpstreamClient.antigravityThinkingLevel("gemini-4-argon-low(low)") == "low")
        assertTrue(UpstreamClient.antigravityThinkingLevel("gemini-4-argon-medium(medium)") == "medium")
        assertTrue(UpstreamClient.antigravityThinkingLevel("gemini-4-argon-high(high)") == "high")
        assertTrue(UpstreamClient.antigravityThinkingLevel("gemini-pro-agent") == null)
        assertTrue(UpstreamClient.antigravityThinkingLevel("model(auto)") == null)
    }

    @Test
    fun `antigravity defaults to the web mandatory model`() {
        assertEquals("gemini-3.8-flash-medium", ProviderCatalog.antigravity.defaultModel)
        assertTrue(
            ProviderCatalog.antigravity.models.any { it.id == "gemini-3.8-flash-medium" }
        )
    }
}
