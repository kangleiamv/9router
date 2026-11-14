package ai.ninerouter.engine

import ai.ninerouter.util.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiSchemaTest {
    @Test
    fun `strips exclusiveMinimum and other unsupported keywords recursively`() {
        val cleaned = GeminiSchema.clean(
            Json.lenient.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {
                    "count": {
                      "type": "integer",
                      "exclusiveMinimum": 0,
                      "minimum": 1,
                      "description": "Count"
                    },
                    "nested": {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string", "minLength": 2}
                      },
                      "additionalProperties": false
                    }
                  },
                  "required": ["count", "nested"],
                  "${'$'}schema": "https://json-schema.org/draft/2020-12/schema"
                }
                """.trimIndent(),
            ),
        )

        val properties = cleaned["properties"]!!.jsonObject
        val count = properties["count"]!!.jsonObject
        assertNull(count["exclusiveMinimum"])
        assertEquals(1, count["minimum"]!!.jsonPrimitive.int)
        assertEquals("Count", count["description"]!!.jsonPrimitive.contentOrNull)

        val nested = properties["nested"]!!.jsonObject
        assertNull(nested["additionalProperties"])
        assertNull(nested["properties"]!!.jsonObject["name"]!!.jsonObject["minLength"])
        assertNull(cleaned["${'$'}schema"])
        assertEquals(2, cleaned["required"]!!.jsonArray.size)
    }

    @Test
    fun `converts const to a string enum`() {
        val cleaned = GeminiSchema.clean(
            Json.lenient.parseToJsonElement(
                """{"type":"object","properties":{"mode":{"const":"fast"}}}""",
            ),
        )
        val mode = cleaned["properties"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals("fast", mode["enum"]!!.jsonArray[0].jsonPrimitive.contentOrNull)
        assertEquals("string", mode["type"]!!.jsonPrimitive.contentOrNull)
        assertNull(mode["const"])
    }

    @Test
    fun `selects an object branch from anyOf and removes the combinator`() {
        val cleaned = GeminiSchema.clean(
            Json.lenient.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {
                    "payload": {
                      "anyOf": [
                        {"type": "string"},
                        {"type": "object", "properties": {"id": {"type": "string"}}}
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        val payload = cleaned["properties"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("object", payload["type"]!!.jsonPrimitive.contentOrNull)
        assertNotNull(payload["properties"])
        assertNull(payload["anyOf"])
    }

    @Test
    fun `accepts parameters supplied as a json string`() {
        val cleaned = GeminiSchema.clean(
            JsonPrimitive(
                """{"type":"object","properties":{"n":{"type":"number","exclusiveMaximum":10}}}""",
            ),
        )
        val number = cleaned["properties"]!!.jsonObject["n"]!!.jsonObject
        assertNull(number["exclusiveMaximum"])
        assertEquals("number", number["type"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `missing parameters become an object schema`() {
        val cleaned = GeminiSchema.clean(null)
        assertEquals("object", cleaned["type"]!!.jsonPrimitive.contentOrNull)
        assertNotNull(cleaned["properties"])
    }

    @Test
    fun `gemini envelope strips exclusiveMinimum from tool declarations`() {
        val ctx = ChatContext.from(
            Json.lenient.parseToJsonElement(
                """
                {
                  "model": "gemini-3-flash-preview",
                  "stream": false,
                  "messages": [{"role": "user", "content": "Count"}],
                  "tools": [{
                    "type": "function",
                    "function": {
                      "name": "count",
                      "description": "Count things",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "max": {"type": "integer", "exclusiveMinimum": 0}
                        },
                        "required": ["max"]
                      }
                    }
                  }]
                }
                """.trimIndent(),
            ).jsonObject,
        )

        val envelope = UpstreamClient.geminiCliEnvelope(
            project = "project",
            model = "gemini-3-flash-preview",
            sessionId = "session",
            ctx = ctx,
        )
        val declaration = envelope["request"]!!
            .jsonObject["tools"]!!
            .jsonArray[0]
            .jsonObject["functionDeclarations"]!!
            .jsonArray[0]
            .jsonObject
        val parameters = declaration["parameters"]!!.jsonObject
        assertNull(parameters["properties"]!!.jsonObject["max"]!!.jsonObject["exclusiveMinimum"])
        assertEquals("max", parameters["required"]!!.jsonArray[0].jsonPrimitive.contentOrNull)
    }
}
