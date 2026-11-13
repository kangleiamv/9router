package ai.ninerouter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogExpansionTest {
    @Test
    fun `registry exposes every visible web provider`() {
        val providers = ProviderCatalog.all
        assertTrue("expected a large registry, got ${providers.size}", providers.size >= 130)
        assertNotNull(ProviderCatalog.byId("cohere"))
        assertNotNull(ProviderCatalog.byId("perplexity"))
        assertNotNull(ProviderCatalog.byId("ollama-cloud"))
        assertNotNull(ProviderCatalog.byId("opencode-go"))
        assertEquals(providers.size, providers.map { it.id }.distinct().size)
    }

    @Test
    fun `usable providers have endpoint and model metadata`() {
        val usable = ProviderCatalog.available
        assertTrue("expected dozens of usable providers, got ${usable.size}", usable.size >= 55)
        usable.forEach { spec ->
            assertNotNull("${spec.id} has no base URL", spec.baseUrl)
        }
        val cohere = ProviderCatalog.byId("cohere")!!
        assertTrue(cohere.available)
        assertEquals("https://api.cohere.ai/v1", cohere.baseUrl)
        assertEquals("https://api.cohere.ai/v1/models", cohere.modelsUrl)
        assertTrue(cohere.models.isNotEmpty())
    }

    @Test
    fun `unsupported transports are visible but cannot be routed`() {
        val github = ProviderCatalog.byId("github")!!
        assertFalse(github.available)
        assertNull(ProviderCatalog.resolve("github/gpt-5.5").first)
        assertTrue(ProviderCatalog.modelsForSelection().none { it.first == "github" })
    }

    @Test
    fun `fetched models merge into the catalog and model selection`() {
        try {
            ProviderCatalog.setDynamicModels(
                mapOf(
                    "cohere" to listOf(
                        ModelSpec("command-a-live", "Command A Live"),
                        ModelSpec("command-r-live", "Command R Live")
                    )
                )
            )
            val cohere = ProviderCatalog.byId("cohere")!!
            assertTrue(cohere.models.any { it.id == "command-a-live" })
            assertTrue(cohere.models.any { it.id == "command-r-plus-08-2024" })
            assertTrue(
                ProviderCatalog.modelsForSelection().any {
                    it.first == "cohere" && it.second == "command-a-live"
                }
            )
        } finally {
            ProviderCatalog.setDynamicModels(emptyMap())
        }
        assertFalse(ProviderCatalog.byId("cohere")!!.models.any { it.id == "command-a-live" })
    }

    @Test
    fun `custom compatible providers stay resolvable and available`() {
        val now = "2026-01-01T00:00:00Z"
        val node = ai.ninerouter.data.model.CustomProvider(
            id = "openai-compatible-test",
            type = "openai-compatible",
            name = "Test",
            prefix = "tst",
            baseUrl = "https://example.test/v1",
            models = "[\"live-model\"]",
            createdAt = now,
            updatedAt = now
        )
        val spec = ProviderCatalog.customSpec(node)
        assertTrue(spec.available)
        val (resolved, model) = ProviderCatalog.resolve("tst/live-model", listOf(spec))
        assertEquals(spec.id, resolved?.id)
        assertEquals("live-model", model)
    }
}
