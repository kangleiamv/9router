package ai.ninerouter.engine

import ai.ninerouter.data.model.ANTHROPIC_COMPATIBLE_TYPE
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.OPENAI_COMPATIBLE_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderTest {
    private fun node(
        type: String = OPENAI_COMPATIBLE_TYPE,
        id: String = if (type == ANTHROPIC_COMPATIBLE_TYPE) {
            "anthropic-compatible-test"
        } else {
            "openai-compatible-chat-test"
        },
        name: String = if (type == ANTHROPIC_COMPATIBLE_TYPE) {
            "Anthropic Compatible"
        } else {
            "OpenAI Compatible"
        },
        prefix: String = if (type == ANTHROPIC_COMPATIBLE_TYPE) "ac-prod" else "oc-prod",
        baseUrl: String = if (type == ANTHROPIC_COMPATIBLE_TYPE) {
            "https://api.anthropic.com/v1"
        } else {
            "https://api.openai.com/v1"
        },
        models: List<String> = listOf("gpt-4o", "gpt-4o-mini")
    ): CustomProvider = CustomProvider(
        id = id,
        type = type,
        name = name,
        prefix = prefix,
        apiType = "chat",
        baseUrl = baseUrl,
        models = "[]",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    ).withModels(models)

    @Test
    fun `openai compatible spec exposes prefix and chat completions`() {
        val spec = ProviderCatalog.customSpec(node())

        assertEquals("openai-compatible-chat-test", spec.id)
        assertEquals("OpenAI Compatible", spec.name)
        assertEquals("oc-prod", spec.alias)
        assertEquals(WireFormat.OPENAI, spec.format)
        assertEquals("/chat/completions", spec.chatPath)
        assertEquals("https://api.openai.com/v1", spec.baseUrl)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), spec.models.map { it.id })
        assertTrue(spec.isCompatible)
    }

    @Test
    fun `anthropic compatible spec uses messages path and anthropic auth`() {
        val spec = ProviderCatalog.customSpec(node(type = ANTHROPIC_COMPATIBLE_TYPE))

        assertEquals(WireFormat.ANTHROPIC, spec.format)
        assertEquals("/messages", spec.chatPath)
        assertEquals("https://api.anthropic.com/v1", spec.baseUrl)
        assertEquals("apikey", spec.authType)
        assertTrue(spec.isCompatible)
    }

    @Test
    fun `sanitize base url removes endpoint suffixes`() {
        assertEquals(
            "https://api.openai.com/v1",
            ProviderCatalog.sanitizeBaseUrl(
                " https://api.openai.com/v1/chat/completions/ ",
                OPENAI_COMPATIBLE_TYPE
            )
        )
        assertEquals(
            "https://api.anthropic.com/v1",
            ProviderCatalog.sanitizeBaseUrl(
                "https://api.anthropic.com/v1/messages",
                ANTHROPIC_COMPATIBLE_TYPE
            )
        )
        assertEquals(
            "https://localhost:11434/v1",
            ProviderCatalog.sanitizeBaseUrl(
                "https://localhost:11434/v1/",
                OPENAI_COMPATIBLE_TYPE
            )
        )
    }

    @Test
    fun `resolve matches custom prefix`() {
        val spec = ProviderCatalog.customSpec(node())
        val (resolved, modelId) = ProviderCatalog.resolve("oc-prod/gpt-4o", listOf(spec))

        assertEquals(spec, resolved)
        assertEquals("gpt-4o", modelId)
    }

    @Test
    fun `resolve finds a bare custom model id`() {
        val spec = ProviderCatalog.customSpec(node(models = listOf("llama-3.3-70b")))
        val (resolved, modelId) = ProviderCatalog.resolve("llama-3.3-70b", listOf(spec))

        assertEquals(spec, resolved)
        assertEquals("llama-3.3-70b", modelId)
    }

    @Test
    fun `resolve prefers a built in provider over custom extras`() {
        val spec = ProviderCatalog.customSpec(node(prefix = "openai"))
        val (resolved, _) = ProviderCatalog.resolve("openai/gpt-4o", listOf(spec))

        assertFalse(resolved?.isCompatible ?: true)
        assertEquals("openai", resolved?.id)
    }

    @Test
    fun `unknown custom prefix stays unresolved`() {
        val spec = ProviderCatalog.customSpec(node())
        val (resolved, modelId) = ProviderCatalog.resolve("nope/gpt-4o", listOf(spec))

        assertNull(resolved)
        assertEquals("nope/gpt-4o", modelId)
    }

    @Test
    fun `model ref uses prefix for compatible providers`() {
        val custom = ProviderCatalog.customSpec(node())
        val builtin = ProviderCatalog.byId("openai") ?: ProviderSpec(
            id = "openai",
            name = "OpenAI",
            models = listOf(ModelSpec("gpt-4o", "GPT-4o"))
        )

        assertEquals("oc-prod/gpt-4o", ProviderCatalog.modelRef(custom, "gpt-4o"))
        assertEquals("openai/gpt-4o", ProviderCatalog.modelRef(builtin, "gpt-4o"))
    }

    @Test
    fun `custom provider model ids round trip and deduplicate`() {
        val stored = node().withModels(listOf(" gpt-4o ", "", "gpt-4o", "gpt-4o-mini"))

        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), stored.modelIds())
    }

    @Test
    fun `models for selection includes custom models`() {
        val spec = ProviderCatalog.customSpec(node(models = listOf("custom-llm")))
        val selection = ProviderCatalog.modelsForSelection(listOf(spec))

        assertTrue(
            selection.any { (provider, id, name) ->
                provider == spec.id && id == "custom-llm" && name == "custom-llm"
            }
        )
    }
}
