package ai.ninerouter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamModelParsingTest {
    @Test
    fun `parses openai style catalogs and drops non chat models`() {
        val models = UpstreamClient.parseModels(
            """
            {"object":"list","data":[
              {"id":"gpt-5.5","name":"GPT-5.5"},
              {"id":"o4-mini"},
              {"id":"text-embedding-3-small"},
              {"id":"whisper-1"}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf("gpt-5.5", "o4-mini"), models.map { it.id })
        assertEquals("GPT-5.5", models.first().name)
    }

    @Test
    fun `parses anthropic display names`() {
        val models = UpstreamClient.parseModels(
            """{"data":[{"id":"claude-opus-5","display_name":"Claude Opus 5"}]}"""
        )
        assertEquals(1, models.size)
        assertEquals("claude-opus-5", models[0].id)
        assertEquals("Claude Opus 5", models[0].name)
    }

    @Test
    fun `parses ollama tags`() {
        val models = UpstreamClient.parseModels(
            """{"models":[{"name":"llama4:latest"},{"model":"qwen3-coder"}]}"""
        )
        assertEquals(listOf("llama4:latest", "qwen3-coder"), models.map { it.id })
    }

    @Test
    fun `parses gemini models and keeps generateContent only`() {
        val models = UpstreamClient.parseModels(
            """
            {"models":[
              {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
            ]}
            """.trimIndent(),
            gemini = true
        )
        assertEquals(listOf("gemini-3.8-flash"), models.map { it.id })
        assertEquals("Gemini 3.8 Flash", models[0].name)
    }

    @Test
    fun `parses gemini cli map and skips internal models`() {
        val models = UpstreamClient.parseModels(
            """
            {"models":{
              "gemini-3.1-pro":{"displayName":"Gemini 3.1 Pro"},
              "secret-internal":{"isInternal":true}
            }}
            """.trimIndent(),
            geminiCli = true
        )
        assertEquals(listOf("gemini-3.1-pro"), models.map { it.id })
        assertTrue(models.none { it.id == "secret-internal" })
    }
}
