package ai.ninerouter.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeFingerprintTest {

    private val liveFree = listOf(
        "deepseek-v4.1-flash-free",
        "big-pickle",
        "glm-5.3-flash-free",
        "mimo-v2.6-flash-free",
        "minimax-m3.1-flash-free",
        "longcat-2.5-free",
        "iceman-free",
        "inkling-2-small-free",
        "beam-free",
        "flint-40b-free",
        "nemotron-3.5-lightning-free",
        "nemotron-3.5-super-vl-free"
    )

    @Test
    fun `android catalog carries the live free ids and v2 endpoint`() {
        val spec = ProviderCatalog.byId("opencode")!!
        assertEquals("https://opencode.ai/inference/openai/v1", spec.baseUrl)
        assertEquals("https://opencode.ai/inference/v1/models", spec.modelsUrl)
        assertEquals("deepseek-v4.1-flash-free", spec.defaultModel)
        liveFree.forEach { id ->
            assertNotNull("missing $id", spec.models.firstOrNull { it.id == id })
        }
        listOf(
            "muse-spark-1.2-contributor-free",
            "muse-spark-1.3-contributor-free",
            "union-alpha",
            "jev-1.13-free"
        ).forEach { retired ->
            assertTrue("retired $retired is still listed", spec.models.none { it.id == retired })
        }
    }

    @Test
    fun `zen catalog uses the same v2 gateway and a paid default`() {
        val spec = ProviderCatalog.byId("opencode-zen")!!
        assertEquals("https://opencode.ai/inference/openai/v1", spec.baseUrl)
        assertEquals("https://opencode.ai/inference/v1/models", spec.modelsUrl)
        assertEquals("deepseek-v4.1-flash", spec.defaultModel)
        assertTrue(spec.models.any { it.id == "deepseek-v4.1-flash-free" })
        assertTrue(spec.models.any { it.id == "kimi-k3" })
    }

    @Test
    fun `free lane detection covers suffixed and stealth ids`() {
        liveFree.forEach { assertTrue(it, OpenCodeFingerprint.isFreeLaneModel(it)) }
        assertTrue(OpenCodeFingerprint.isFreeLaneModel("glm-5.3-flash-free(max)"))
        assertTrue(OpenCodeFingerprint.isFreeLaneModel("ocz/mimo-v2.6-flash-free"))
        listOf("deepseek-v4.1-flash", "kimi-k3", "gpt-6.1-sol").forEach {
            assertFalse(it, OpenCodeFingerprint.isFreeLaneModel(it))
        }
    }

    @Test
    fun `session id has the upstream shape and is stable`() {
        val first = OpenCodeFingerprint.sessionId("connection-a:deepseek-v4.1-flash-free")
        val again = OpenCodeFingerprint.sessionId("connection-a:deepseek-v4.1-flash-free")
        val other = OpenCodeFingerprint.sessionId("connection-b:deepseek-v4.1-flash-free")
        assertEquals(first, again)
        assertTrue(first != other)
        assertTrue(first, Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$").matches(first))
    }

    @Test
    fun `prepare injects fingerprint tools env marker and streaming`() {
        val body = Json.parseToJsonElement(
            """
            {
              "model": "mimo-v2.6-flash-free",
              "stream": false,
              "messages": [
                {"role": "user", "content": "hi"},
                {"role": "assistant", "content": "hello"},
                {"role": "tool", "content": "ok"},
                {"role": "user", "content": "again"}
              ],
              "tool_choice": "required"
            }
            """.trimIndent()
        ).jsonObject

        val out = OpenCodeFingerprint.prepare(body, "mimo-v2.6-flash-free")
        assertTrue(out["stream"]!!.jsonPrimitive.boolean)
        assertEquals("auto", out["tool_choice"]!!.jsonPrimitive.contentOrNull)

        val names = out["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.contentOrNull
        }
        assertEquals(listOf("read", "shell"), names)

        val messages = out["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("system", messages.first()["role"]!!.jsonPrimitive.contentOrNull)
        assertTrue(messages.first()["content"]!!.jsonPrimitive.contentOrNull!!.contains("<env>"))
        val assistant = messages.first { it["role"]!!.jsonPrimitive.contentOrNull == "assistant" }
        assertEquals(" ", assistant["reasoning_content"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `prepare keeps an existing env marker and auto choice`() {
        val body = Json.parseToJsonElement(
            """
            {
              "messages": [{"role": "system", "content": "keep <env> me"}, {"role": "user", "content": "hi"}],
              "tools": [{"type": "function", "function": {"name": "read", "description": "custom"}}],
              "tool_choice": "auto"
            }
            """.trimIndent()
        ).jsonObject
        val out = OpenCodeFingerprint.prepare(body, "deepseek-v4.1-flash-free")
        val messages = out["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, messages.size)
        val system = messages.first()["content"]!!.jsonPrimitive.contentOrNull!!
        assertTrue(system.startsWith("keep <env> me"))
        assertTrue(system.contains("Do not call the `shell` tool"))
        assertEquals("auto", out["tool_choice"]!!.jsonPrimitive.contentOrNull)
        // The caller's custom read schema is replaced in place; shell is appended.
        val tools = out["tools"]!!.jsonArray.map { it.jsonObject }
        val names = tools.map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.contentOrNull }
        assertEquals(listOf("read", "shell"), names)
        assertTrue(
            tools.first()["function"]!!.jsonObject["description"]!!.jsonPrimitive.contentOrNull!!
                .startsWith("Read the contents of a file or directory")
        )
    }
}
