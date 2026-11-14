package ai.ninerouter.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderQuotaTest {

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun parsesWeeklyAndSessionBuckets() {
        val root = json(
            """
            {
              "groups": [
                {
                  "displayName": "Gemini",
                  "buckets": [
                    {
                      "bucketId": "gemini_weekly",
                      "window": "weekly",
                      "remainingFraction": 0.42,
                      "resetTime": "2026-11-20T10:00:00Z"
                    },
                    {
                      "bucketId": "gemini_session",
                      "window": "5h",
                      "remainingFraction": 0.75,
                      "resetTime": "2026-11-13T18:00:00Z"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val windows = ProviderQuotaClient.parseQuotaGroups(root)

        assertEquals(setOf("Gemini (Weekly)", "Gemini (5h)"), windows.keys)
        assertEquals(42.0, windows.getValue("Gemini (Weekly)").remainingPercent, 0.001)
        assertEquals(75.0, windows.getValue("Gemini (5h)").remainingPercent, 0.001)
        assertNotNull(windows.getValue("Gemini (Weekly)").resetAt)
    }

    @Test
    fun disabledWeeklyBucketIsSkippedButDisabledSessionIsZero() {
        val root = json(
            """
            {
              "quotaSummary": {
                "groups": [
                  {
                    "displayName": "Claude & GPT",
                    "buckets": [
                      { "window": "weekly", "remainingFraction": 0.9, "disabled": true },
                      { "window": "5h", "remainingFraction": 0.8, "disabled": true }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val windows = ProviderQuotaClient.parseQuotaGroups(root)

        assertEquals(1, windows.size)
        assertEquals(0.0, windows.getValue("Claude & GPT (5h)").remainingPercent, 0.001)
    }

    @Test
    fun argonFamilyWinsOverGeminiMatcher() {
        val root = json(
            """
            {
              "groups": [
                {
                  "displayName": "Gemini 4 Argon",
                  "buckets": [
                    { "window": "weekly", "remainingFraction": 0.6 }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val windows = ProviderQuotaClient.parseQuotaGroups(root)
        assertTrue(windows.containsKey("Gemini 4 Argon (Weekly)"))
    }

    @Test
    fun parsesEpochSecondsAndIsoResets() {
        val epoch = ProviderQuotaClient.parseResetAt(JsonPrimitive(1_763_000_000L))
        val iso = ProviderQuotaClient.parseResetAt(JsonPrimitive("2026-11-13T18:00:00Z"))
        assertEquals("2025-11-13T02:13:20Z", epoch)
        assertEquals("2026-11-13T18:00:00Z", iso)
        assertNull(ProviderQuotaClient.parseResetAt(JsonPrimitive("not-a-date")))
    }

    @Test
    fun parsesRateLimitDurationStrings() {
        assertEquals(90_000L, ProviderQuotaClient.parseDuration("1m30s"))
        assertEquals(3_600_000L, ProviderQuotaClient.parseDuration("1h"))
        assertEquals(1_500L, ProviderQuotaClient.parseDuration("1.5s"))
        assertNull(ProviderQuotaClient.parseDuration("unknown"))
    }

    @Test
    fun miniMaxPercentOnlyAndCountFallbacks() {
        val percentOnly = json("""{"current_interval_remaining_percent": 62.5}""")
        assertEquals(
            62.5,
            ProviderQuotaClient.miniMaxPercent(percentOnly, "current_interval", countMeansRemaining = false)!!,
            0.001
        )

        val usedCount = json(
            """{"current_interval_total_count": 100, "current_interval_usage_count": 25}"""
        )
        assertEquals(
            75.0,
            ProviderQuotaClient.miniMaxPercent(usedCount, "current_interval", countMeansRemaining = false)!!,
            0.001
        )
        assertEquals(
            25.0,
            ProviderQuotaClient.miniMaxPercent(usedCount, "current_interval", countMeansRemaining = true)!!,
            0.001
        )

        val camel = json(
            """{"currentWeeklyTotalCount": 200, "currentWeeklyUsageCount": 50}"""
        )
        assertEquals(
            75.0,
            ProviderQuotaClient.miniMaxPercent(camel, "current_weekly", countMeansRemaining = false)!!,
            0.001
        )
    }

    @Test
    fun claudeUtilizationBecomesRemainingPercent() {
        val window = json(
            """{"utilization": 25.5, "resets_at": "2026-11-13T18:00:00Z"}"""
        )

        val quota = ProviderQuotaClient.utilizationWindow("Session (5h)", window)

        assertNotNull(quota)
        assertEquals(74.5, quota!!.remainingPercent, 0.001)
        assertEquals(25.5, quota.usedPercent, 0.001)
    }
}
