package ai.ninerouter.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Client fingerprint required by OpenCode's free inference lane.
 *
 * The upstream gate mirrors the official CLI: a v2 user agent, a `ses_...`
 * session id, OpenCode's own read/shell tool definitions and the `<env>` system
 * marker. Requests missing any of these are answered with 403 FreeTierError or
 * a false 429 FreeUsageLimitError even when the account still has quota.
 */
object OpenCodeFingerprint {
    const val USER_AGENT = "opencode/latest/2.3.0/cli"
    const val CLIENT = "cli"
    const val PROJECT = "global"

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val AUTO_ONLY = setOf(
        "glm-5.3-flash-free",
        "mimo-v2.6-flash-free",
        "minimax-m3.1-flash-free"
    )

    /** big-pickle is a stealth free model without the "-free" suffix. */
    fun isFreeLaneModel(model: String): Boolean {
        val base = model.substringBefore('(').substringAfterLast('/')
        return base == "big-pickle" || base.endsWith("-free")
    }

    /**
     * Stable `ses_<12 hex><14 base62>` session for a connection. Upstream only
     * checks the shape, but keeping it stable preserves prompt cache affinity.
     */
    fun sessionId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val hex = buildString {
            for (i in 0 until 6) append("%02x".format(digest[i]))
        }
        val suffix = buildString {
            for (i in 0 until 14) append(ALPHABET[(digest[(i + 6) % digest.size].toInt() and 0xFF) % ALPHABET.length])
        }
        return "ses_$hex$suffix"
    }

    /**
     * Prepare a Chat Completions payload for the free lane: force streaming,
     * append the required tools, inject the env marker, echo reasoning_content
     * placeholders and demote unsupported tool choices.
     */
    fun prepare(body: JsonObject, model: String): JsonObject {
        val fields = body.toMutableMap()
        fields["stream"] = JsonPrimitive(true)

        val messages: MutableList<JsonObject> = (body["messages"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.toMutableList()
            ?: mutableListOf()

        // Thinking models reject multi-turn history whose assistant turns have
        // no reasoning_content; the response pipeline strips the field before
        // it reaches the client, so the echo can never come from the caller.
        for (i in messages.indices) {
            val message = messages[i]
            if (message["role"]?.jsonPrimitive?.contentOrNull != "assistant") continue
            if (!message["reasoning_content"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) continue
            messages[i] = JsonObject(message + ("reasoning_content" to JsonPrimitive(" ")))
        }
        val tools: MutableList<JsonObject> = (body["tools"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.toMutableList()
            ?: mutableListOf()

        // Upstream compares the read/shell definitions byte for byte. When a
        // caller already declares one of those names, replace its definition in
        // place; leaving a custom schema would answer 403 FreeTierError.
        val normalized = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        for (tool in tools) {
            val name = toolName(tool)?.trim().orEmpty()
            if (name == "read" || name == "shell") {
                if (!seen.add(name)) continue
                normalized += withFingerprintDefinition(tool, name)
            } else {
                normalized += tool
            }
        }

        val appended = mutableListOf<String>()
        for (definition in FINGERPRINT_TOOLS) {
            if (normalized.any { toolName(it) == definition.name }) continue
            normalized += JsonObject(
                mapOf(
                    "type" to JsonPrimitive("function"),
                    "function" to JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(definition.name),
                            "description" to JsonPrimitive(definition.description),
                            "parameters" to definition.parameters
                        )
                    )
                )
            )
            appended += definition.name
        }
        fields["tools"] = JsonArray(normalized)

        // Without this note the model calls the appended placeholders (live
        // probe: `read` on "what files are in /etc?"). It must travel before
        // the env marker so both end up in the same system message.
        if (appended.isNotEmpty()) addPlaceholderNote(messages, appended)
        fields["messages"] = JsonArray(withEnvMarker(messages))

        val choice = body["tool_choice"]
        val choiceText = (choice as? JsonPrimitive)?.contentOrNull
        val base = model.substringBefore('(').substringAfterLast('/')
        if (choice != null && choiceText != "auto" && base in AUTO_ONLY) {
            fields["tool_choice"] = JsonPrimitive("auto")
        }
        return JsonObject(fields)
    }

    private fun withFingerprintDefinition(tool: JsonObject, name: String): JsonObject {
        val definition = FINGERPRINT_TOOLS.first { it.name == name }
        val function = tool["function"] as? JsonObject
        return if (function != null) {
            JsonObject(
                tool + (
                    "function" to JsonObject(
                        function + mapOf(
                            "description" to JsonPrimitive(definition.description),
                            "parameters" to definition.parameters
                        )
                    )
                )
            )
        } else {
            JsonObject(
                tool + mapOf(
                    "description" to JsonPrimitive(definition.description),
                    "parameters" to definition.parameters
                )
            )
        }
    }

    private fun addPlaceholderNote(messages: MutableList<JsonObject>, names: List<String>) {
        val list = names.joinToString(" or ") { "`$it`" }
        val note = "Do not call the $list tools; they are placeholders that do nothing. " +
            "Use the other tools you have been given instead."
        val index = messages.indexOfFirst { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
        if (index < 0) {
            messages.add(0, JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(note))))
            return
        }
        val message = messages[index]
        val content = message["content"]
        val next = when (content) {
            is JsonPrimitive -> JsonPrimitive(
                content.contentOrNull?.takeIf { it.isNotBlank() }?.let { "$it\n\n$note" } ?: note
            )
            is JsonArray -> JsonArray(
                content + JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(note)
                    )
                )
            )
            else -> JsonPrimitive(note)
        }
        messages[index] = JsonObject(message + ("content" to next))
    }

    private fun withEnvMarker(messages: MutableList<JsonObject>): List<JsonObject> {
        val marker = "<env>"
        val index = messages.indexOfFirst { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
        if (index >= 0) {
            val existing = messages[index]["content"]
            val text = existing?.jsonPrimitive?.contentOrNull
            if (text?.contains(marker) == true) return messages
            val injected = if (text.isNullOrBlank()) ENV_PROMPT else "$text\n\n$ENV_PROMPT"
            messages[index] = JsonObject(messages[index] + ("content" to JsonPrimitive(injected)))
            return messages
        }
        messages.add(0, JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(ENV_PROMPT))))
        return messages
    }

    private fun toolName(tool: JsonObject): String? =
        tool["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?: tool["name"]?.jsonPrimitive?.contentOrNull

    private class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject
    )

    // Verbatim from the OpenCode v2.3 CLI. Upstream compares description and
    // parameters, so do not reword or reformat these.
    private val FINGERPRINT_TOOLS = listOf(
        ToolDefinition(
            name = "read",
            description = "Read the contents of a file or directory. Supports text files, images, and PDFs. Images and PDFs are presented directly to the model. Each text line is prefixed by its 1-based line number as <line>: <content>. The prefix is for reference and is not part of the file content. Directory entries are returned one per line. Use offset and limit to read large files or directories in sections. Prefer one larger read over many small slices, and use grep to find specific content in large files.",
            parameters = jsonObject(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "path" to schema("string", "File or directory to read"),
                        "offset" to schema("integer", "The line or directory entry to start reading from (1-based)", 0),
                        "limit" to schema("integer", "The maximum number of lines or directory entries to read (defaults to and capped at 2000)", 0)
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("path"))),
                "additionalProperties" to JsonPrimitive(false)
            )
        ),
        ToolDefinition(
            name = "shell",
            description = "Execute a shell command and return its output. Commands run on Linux using bash. Quote file paths containing spaces or special characters. Prefer dedicated tools over shell commands when possible. When output is large, the full result is saved to a file and a truncated preview is returned. Rely on automatic truncation unless filtering the output is more useful. Commands accept an optional timeout, background commands have no timeout by default. Background commands return immediately, and you will be notified when they complete.",
            parameters = jsonObject(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "command" to schema("string", "Shell command string to execute"),
                        "workdir" to schema("string", "Working directory to execute the command in. Defaults to the current working directory."),
                        "timeout" to schema("integer", "Timeout in milliseconds. Set to 0 to disable the timeout. Defaults to 120000 for foreground commands.", 0),
                        "background" to schema("boolean", "Run the command in the background and return immediately (useful for dev servers and long-running builds).")
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("command"))),
                "additionalProperties" to JsonPrimitive(false)
            )
        )
    )

    private fun schema(type: String, description: String, minimum: Int? = null): JsonObject =
        JsonObject(
            buildMap {
                put("type", JsonPrimitive(type))
                put("description", JsonPrimitive(description))
                if (minimum != null) put("minimum", JsonPrimitive(minimum))
            }
        )

    private fun jsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(pairs.toMap())

    private val ENV_PROMPT = """
        Here is some useful information about the environment you are running in:
        <env>
          Working directory: /data/user/0/ai.ninerouter/files
          Workspace root folder: /data/user/0/ai.ninerouter/files
          Is directory a git repo: no
          Platform: linux
          Prefer /tmp/opencode over generic system temporary directories such as /tmp; it is pre-created and approved for external access.
        </env>
    """.trimIndent()
}
