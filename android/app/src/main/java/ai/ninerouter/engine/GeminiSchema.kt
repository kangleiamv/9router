package ai.ninerouter.engine

import ai.ninerouter.util.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Gemini's function-declaration schema is a narrow subset of JSON Schema.
 * Client tool definitions routinely carry validation keywords such as
 * `exclusiveMinimum`; Google rejects the entire request with
 *
 *   Invalid JSON payload received. Unknown name "exclusiveMinimum" ...
 *
 * The web translator already sanitizes these fields in
 * open-sse/translator/formats/gemini.js. This is the Kotlin equivalent used for
 * Gemini, Gemini CLI and Antigravity requests.
 */
object GeminiSchema {
    /**
     * Keywords Gemini's Schema protobuf has no field for. They are removed
     * recursively, including inside properties, items and array branches.
     */
    private val UNSUPPORTED = setOf(
        "minLength", "maxLength", "exclusiveMinimum", "exclusiveMaximum",
        "minItems", "maxItems", "minProperties", "maxProperties",
        "format", "multipleOf", "uniqueItems", "contains",
        "unevaluatedProperties", "unevaluatedItems", "contentSchema",
        "prefixItems", "additionalItems",
        "default", "examples", "deprecated", "readOnly", "writeOnly",
        "\$schema", "\$defs", "definitions", "const", "\$ref", "\$comment",
        "additionalProperties", "propertyNames", "patternProperties", "enumDescriptions",
        "anyOf", "oneOf", "allOf", "not",
        "dependencies", "dependentSchemas", "dependentRequired",
        "title", "optional", "~optional", "if", "then", "else",
        "contentMediaType", "contentEncoding",
        "errorMessage", "errorMessages", "x-errorMessage", "x-errorMessages",
        "markdownDescription", "x-intellij-html-description",
        "x-taplo-info", "x-taplo", "doNotSuggest", "suggestSortText",
        "cornerRadius", "fillColor", "fontFamily", "fontSize", "fontWeight",
        "gap", "padding", "strokeColor", "strokeThickness", "textColor",
    )

    /** Return a Gemini-compatible object schema for one tool's parameters. */
    fun clean(schema: JsonElement?): JsonObject {
        val source = when (schema) {
            is JsonObject -> schema
            is JsonPrimitive -> schema.contentOrNull
                ?.let { runCatching { Json.lenient.parseToJsonElement(it) }.getOrNull() }
                ?.let { it as? JsonObject }
            else -> null
        } ?: return emptyObject()
        return sanitize(source)
    }

    private fun sanitize(source: JsonObject): JsonObject {
        val obj = LinkedHashMap(source)

        // Gemini requires string enums. Convert const first so both forms end up
        // in the same enum representation.
        val constValue = (obj["const"] as? JsonPrimitive)?.contentOrNull
        if (constValue != null && obj["enum"] == null) {
            obj["enum"] = JsonArray(listOf(JsonPrimitive(constValue)))
        }
        (obj["enum"] as? JsonArray)?.let { values ->
            val strings = values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (strings.isNotEmpty()) {
                obj["enum"] = JsonArray(strings.map { JsonPrimitive(it) })
                if (obj["type"] == null) obj["type"] = JsonPrimitive("string")
            }
        }

        // allOf is common in MCP schemas. Merge the parts Gemini can express and
        // drop the combinator.
        (obj["allOf"] as? JsonArray)?.let { allOf ->
            obj.remove("allOf")
            val mergedProperties = LinkedHashMap<String, JsonElement>()
            val mergedRequired = LinkedHashSet<String>()
            allOf.forEach { item ->
                val sub = item as? JsonObject ?: return@forEach
                val cleaned = sanitize(sub)
                (cleaned["properties"] as? JsonObject)?.forEach { (name, value) ->
                    mergedProperties[name] = value
                }
                (cleaned["required"] as? JsonArray)?.forEach { name ->
                    (name as? JsonPrimitive)?.contentOrNull?.let { mergedRequired += it }
                }
            }
            if (mergedProperties.isNotEmpty()) {
                val existing = LinkedHashMap<String, JsonElement>()
                (obj["properties"] as? JsonObject)?.forEach { (name, value) ->
                    existing[name] = value
                }
                existing.putAll(mergedProperties)
                obj["properties"] = JsonObject(existing)
            }
            if (mergedRequired.isNotEmpty()) {
                val existing = (obj["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                obj["required"] = JsonArray(
                    (existing + mergedRequired).distinct().map { JsonPrimitive(it) },
                )
            }
        }

        // Tuple items cannot be represented; keep the most useful branch.
        (obj["prefixItems"] as? JsonArray)?.let { prefix ->
            obj.remove("prefixItems")
            if (obj["items"] == null && prefix.isNotEmpty()) {
                val candidates = prefix.filter { it != JsonNull }.ifEmpty { prefix }
                candidates.maxByOrNull { score(it) }?.let { obj["items"] = it }
            }
        }

        // Gemini has no anyOf/oneOf. Pick the branch that carries the most
        // structure (object/array) and merge its fields, like the web translator.
        val alternatives = (obj["anyOf"] as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: (obj["oneOf"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        if (alternatives != null) {
            obj.remove("anyOf")
            obj.remove("oneOf")
            val candidates = alternatives.filter { it != JsonNull }.ifEmpty { alternatives }
            val selected = candidates.maxByOrNull { score(it) }
            if (selected is JsonObject) {
                sanitize(selected).forEach { (key, value) -> obj[key] = value }
            }
        }

        normalizeType(obj["type"])?.let { obj["type"] = JsonPrimitive(it) }

        (obj["properties"] as? JsonObject)?.let { properties ->
            val cleaned = LinkedHashMap<String, JsonElement>()
            properties.forEach { (name, value) -> cleaned[name] = cleanValue(value) }
            obj["properties"] = JsonObject(cleaned)
        }
        obj["items"]?.let { obj["items"] = cleanValue(it) }
        for (key in obj.keys.toList()) {
            if (key == "properties" || key == "items") continue
            val value = obj[key]
            if (value is JsonObject || value is JsonArray) {
                obj[key] = cleanValue(value)
            }
        }

        for (key in obj.keys.toList()) {
            if (key in UNSUPPORTED || key.startsWith("x-")) obj.remove(key)
        }

        val required = (obj["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
            .distinct()
        if (required.isNotEmpty()) {
            val properties = obj["properties"] as? JsonObject
            val filtered = if (properties != null) {
                required.filter { properties.containsKey(it) }
            } else {
                required
            }
            if (filtered.isNotEmpty()) {
                obj["required"] = JsonArray(filtered.map { JsonPrimitive(it) })
            } else {
                obj.remove("required")
            }
        } else {
            obj.remove("required")
        }

        if (obj["type"] == null) {
            obj["type"] = JsonPrimitive(
                when {
                    obj["properties"] != null -> "object"
                    obj["items"] != null -> "array"
                    else -> "object"
                },
            )
        }

        when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "array" -> {
                obj.remove("properties")
                obj.remove("required")
                val items = obj["items"] as? JsonObject
                if (items == null || items.isEmpty()) {
                    obj["items"] = JsonObject(mapOf("type" to JsonPrimitive("string")))
                }
            }

            "object" -> {
                obj.remove("items")
                if (obj["properties"] == null) obj["properties"] = JsonObject(emptyMap())
            }

            else -> {
                obj.remove("items")
                obj.remove("properties")
                obj.remove("required")
            }
        }
        return JsonObject(obj)
    }

    private fun cleanValue(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> sanitize(value)
        is JsonArray -> JsonArray(value.map { cleanValue(it) })
        else -> value
    }

    private fun normalizeType(raw: JsonElement?): String? {
        if (raw is JsonNull) return null
        (raw as? JsonPrimitive)?.contentOrNull?.let { return it }
        val array = raw as? JsonArray ?: return null
        val types = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it != "null" }
        if (types.isEmpty()) return "string"
        if (types.size == 1) return types.first()
        return when {
            "object" in types -> "object"
            "array" in types -> "array"
            "string" in types -> "string"
            "number" in types -> "number"
            "integer" in types -> "integer"
            "boolean" in types -> "boolean"
            else -> types.first()
        }
    }

    private fun score(value: JsonElement): Int {
        val obj = value as? JsonObject ?: return 0
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        return when {
            obj["properties"] != null || type == "object" -> 3
            obj["items"] != null || type == "array" -> 2
            type != null && type != "null" -> 1
            else -> 0
        }
    }

    private fun emptyObject(): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
        ),
    )
}
