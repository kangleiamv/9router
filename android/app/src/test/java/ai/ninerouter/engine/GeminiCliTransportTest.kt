package ai.ninerouter.engine

import ai.ninerouter.util.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCliTransportTest {
    @Test
    fun `user agent matches the gemini cli client fingerprint`() {
        assertEquals(
            "GeminiCLI/0.34.0/gemini-3-flash-preview (linux; arm64; terminal)",
            UpstreamClient.geminiCliUserAgent("gemini-3-flash-preview", "linux", "arm64")
        )
    }

    @Test
    fun `user agent falls back to unknown for a blank model`() {
        assertEquals(
            "GeminiCLI/0.34.0/unknown (linux; x64; terminal)",
            UpstreamClient.geminiCliUserAgent("", "linux", "x64")
        )
    }

    @Test
    fun `maps host architectures to gemini cli names`() {
        assertEquals("arm64", UpstreamClient.geminiCliArch("aarch64"))
        assertEquals("arm64", UpstreamClient.geminiCliArch("arm64"))
        assertEquals("arm", UpstreamClient.geminiCliArch("armv7l"))
        assertEquals("x64", UpstreamClient.geminiCliArch("x86_64"))
        assertEquals("x86", UpstreamClient.geminiCliArch("i686"))
        assertEquals("riscv64", UpstreamClient.geminiCliArch("riscv64"))
        assertEquals("unknown", UpstreamClient.geminiCliArch(""))
    }

    @Test
    fun `chat headers use the gemini cli api client and stream accept`() {
        val headers = UpstreamClient.geminiCliHeaders(
            model = "gemini-3-flash-preview",
            accessToken = "access-token",
            stream = true
        )

        assertEquals("Bearer access-token", headers["Authorization"])
        assertEquals(
            "GeminiCLI/0.34.0/gemini-3-flash-preview (linux; arm64; terminal)",
            headers["User-Agent"]
        )
        assertEquals(
            "google-genai-sdk/1.41.0 gl-node/v22.19.0",
            headers["X-Goog-Api-Client"]
        )
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `non streaming chat asks for json`() {
        val headers = UpstreamClient.geminiCliHeaders(
            model = "gemini-2.5-flash",
            accessToken = "token",
            stream = false
        )

        assertEquals("application/json", headers["Accept"])
        assertTrue(headers["User-Agent"]!!.startsWith("GeminiCLI/0.34.0/gemini-2.5-flash ("))
    }

    @Test
    fun `gemini cli defaults to the free tier flash model`() {
        assertEquals("gemini-3-flash-preview", ProviderCatalog.geminiCli.defaultModel)
        assertTrue(
            ProviderCatalog.geminiCli.models.any { it.id == "gemini-3-flash-preview" }
        )
    }

    @Test
    fun `cloud code envelope carries cli identity session and safety settings`() {
        val ctx = ChatContext.from(
            Json.lenient.parseToJsonElement(
                """
                {"model":"gemini-3-flash-preview","stream":false,
                 "messages":[{"role":"user","content":"Hello"}]}
                """.trimIndent()
            ).jsonObject
        )

        val envelope = UpstreamClient.geminiCliEnvelope(
            project = "my-project",
            model = "gemini-3-flash-preview",
            sessionId = "session-123",
            ctx = ctx
        )

        assertEquals("my-project", envelope["project"]?.jsonPrimitive?.contentOrNull)
        assertEquals("gemini-3-flash-preview", envelope["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("gemini-cli", envelope["userAgent"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            envelope["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .startsWith("agent-")
        )

        val request = envelope["request"]?.jsonObject
        assertNotNull(request)
        assertEquals("session-123", request!!["sessionId"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(request["contents"])
        assertNotNull(request["safetySettings"])
        assertNull(request["tools"])
        assertNull(request["toolConfig"])
    }

    @Test
    fun `cloud code envelope validates tool calls when tools are supplied`() {
        val ctx = ChatContext.from(
            Json.lenient.parseToJsonElement(
                """
                {"model":"gemini-3-flash-preview","stream":false,
                 "messages":[{"role":"user","content":"Weather?"}],
                 "tools":[{"type":"function","function":{
                   "name":"get_weather","description":"Get weather",
                   "parameters":{"type":"object","properties":{}}}}]}
                """.trimIndent()
            ).jsonObject
        )

        val request = UpstreamClient.geminiCliEnvelope(
            project = "my-project",
            model = "gemini-3-flash-preview",
            sessionId = "session-123",
            ctx = ctx
        )["request"]?.jsonObject

        assertNotNull(request)
        assertNotNull(request!!["tools"])
        val mode = request["toolConfig"]
            ?.jsonObject?.get("functionCallingConfig")
            ?.jsonObject?.get("mode")
            ?.jsonPrimitive?.contentOrNull
        assertEquals("VALIDATED", mode)
    }
}
