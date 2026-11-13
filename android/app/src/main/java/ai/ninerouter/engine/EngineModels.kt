package ai.ninerouter.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Parsed view over an incoming OpenAI-compatible chat request. */
data class ChatContext(
    val raw: JsonObject,
    val model: String,
    val stream: Boolean,
    val messages: List<JsonObject>
) {
    fun withModel(newModel: String): JsonObject = buildJsonObject {
        raw.forEach { (key, value) -> if (key != "model") put(key, value) }
        put("model", newModel)
    }

    companion object {
        fun from(raw: JsonObject): ChatContext {
            val model = raw["model"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            val stream = raw["stream"]?.jsonPrimitive?.booleanOrNull ?: false
            val messages = (raw["messages"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            return ChatContext(raw, model, stream, messages)
        }
    }
}

internal fun JsonPrimitive.contentOrNullSafe(): String? =
    try {
        content
    } catch (_: Throwable) {
        null
    }

fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull()

fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()?.toBooleanStrictOrNull()

fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? =
    this[key] as? JsonArray

fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNullSafe()

/** Extracts plain text from an OpenAI message content field (string or parts array). */
fun contentText(content: JsonElement?): String {
    return when (content) {
        null -> ""
        is JsonPrimitive -> content.contentOrNullSafe().orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNullSafe()
                is JsonObject -> part.string("text")
                    ?: part.obj("image_url")?.string("url")?.let { "[image] $it" }
                else -> null
            }
        }.joinToString("")
        else -> ""
    }
}
