package ai.ninerouter.engine

import ai.ninerouter.data.model.ProviderConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FallbackPolicyTest {

    private fun connection(
        provider: String = "antigravity",
        status: String? = "active",
        specific: String? = null
    ) = ProviderConnection(
        id = "conn-1",
        provider = provider,
        name = "Test",
        email = null,
        authType = "apikey",
        apiKey = "enc:key",
        testStatus = status,
        providerSpecificData = specific,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    )

    @Test
    fun rateLimitRaisesBackoffLevel() {
        val decision = FallbackPolicy.check(429, "slow down", backoffLevel = 0, provider = "antigravity")
        assertTrue(decision.shouldFallback)
        assertEquals(1, decision.newBackoffLevel)
        assertEquals(FallbackPolicy.quotaCooldown(1), decision.cooldownMs)
    }

    @Test
    fun quotaCooldownIsCapped() {
        assertEquals(FallbackPolicy.BACKOFF_MAX_MS, FallbackPolicy.quotaCooldown(15))
        assertEquals(FallbackPolicy.BACKOFF_BASE_MS, FallbackPolicy.quotaCooldown(1))
    }

    @Test
    fun requestErrorsDoNotFallback() {
        val decision = FallbackPolicy.check(404, "model not found", backoffLevel = 0, provider = null)
        assertFalse(decision.shouldFallback)
        assertTrue(decision.requestScoped)
    }

    @Test
    fun authErrorsCooldownTheAccount() {
        val decision = FallbackPolicy.check(401, null, backoffLevel = 0, provider = null)
        assertTrue(decision.shouldFallback)
        assertEquals(FallbackPolicy.LONG_COOLDOWN_MS, decision.cooldownMs)
    }

    @Test
    fun serverErrorsAreTransient() {
        val decision = FallbackPolicy.check(502, "bad gateway", backoffLevel = 0, provider = null)
        assertTrue(decision.shouldFallback)
        assertEquals(FallbackPolicy.TRANSIENT_COOLDOWN_MS, decision.cooldownMs)
    }

    @Test
    fun rateLimitTextRuleAppliesToAnyProvider() {
        val decision = FallbackPolicy.check(400, "Rate limit exceeded", backoffLevel = 2, provider = "openai")
        assertTrue(decision.shouldFallback)
        assertEquals(3, decision.newBackoffLevel)
    }

    @Test
    fun providerScopedTextRuleIsIgnoredForOtherProviders() {
        val decision = FallbackPolicy.check(400, "request illegal", backoffLevel = 0, provider = "openai")
        assertFalse(decision.shouldFallback)
        assertTrue(decision.requestScoped)
    }

    @Test
    fun modelLocksExpire() {
        val locked = FallbackPolicy.applyModelLock(connection(), "claude-sonnet-4-6", 60_000)
        assertTrue(FallbackPolicy.isModelLockActive(locked, "claude-sonnet-4-6"))

        val past = Instant.now().minusSeconds(30).toString()
        val expired = connection(specific = """{"modelLock_claude-sonnet-4-6":"$past"}""")
        assertFalse(FallbackPolicy.isModelLockActive(expired, "claude-sonnet-4-6"))
    }

    @Test
    fun clearingLocksLeavesOtherData() {
        val future = Instant.now().plusSeconds(120).toString()
        val conn = connection(
            specific = """{"projectId":"p1","modelLock___all":"$future"}"""
        )
        val cleared = FallbackPolicy.clearModelLocks(conn)
        assertFalse(FallbackPolicy.isModelLockActive(cleared, null))
        assertEquals("p1", cleared.specificData()["projectId"])
    }

    @Test
    fun unavailableHonoursRateLimitedUntil() {
        val future = Instant.now().plusSeconds(60).toString()
        val past = Instant.now().minusSeconds(60).toString()
        assertTrue(FallbackPolicy.isUnavailable(connection(status = "error", specific = """{"rateLimitedUntil":"$future"}""")))
        assertFalse(FallbackPolicy.isUnavailable(connection(status = "error", specific = """{"rateLimitedUntil":"$past"}""")))
        assertFalse(FallbackPolicy.isUnavailable(connection(status = "active", specific = """{"rateLimitedUntil":"$future"}""")))
    }

    @Test
    fun catalogResolvesAliasAndModel() {
        val spec = ProviderCatalog.byAliasOrId("AG")
        assertNotNull(spec)
        assertEquals("antigravity", spec?.id)

        val (provider, model) = ProviderCatalog.resolve("ag/gemini-3.8-flash-medium")
        assertEquals("antigravity", provider?.id)
        assertEquals("gemini-3.8-flash-medium", model)
    }

    @Test
    fun upstreamModelUsesRegistryMapping() {
        assertEquals(
            "gemini-3.8-flash-medium(medium)",
            ProviderCatalog.upstreamModel("antigravity", "gemini-3.8-flash-medium")
        )
    }

    @Test
    fun selectorHasModels() {
        assertTrue(ProviderCatalog.modelsForSelection().isNotEmpty())
    }
}
