package ai.ninerouter.core

import ai.ninerouter.util.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * The hosting doctor is a small, provider-agnostic tool loop. It uses the same
 * internal chat route as the Telegram bot, so a site can be fixed with the
 * provider connection or combo the user already configured.
 */
object HostingAi {
    const val MODEL_KEY = "hosting_ai_model"
    const val MAX_STEPS = 14
    const val MAX_READ_CHARS = 48_000
    const val MAX_WRITE_CHARS = 240_000
    const val MAX_TOOL_OUTPUT = 24_000
    const val MAX_CALL_MS = 180_000L
}

enum class HostingAiStatus { RUNNING, DONE, FAILED, CANCELLED }

enum class HostingAiTone { INFO, TOOL, SUCCESS, ERROR }

data class HostingAiStep(
    val tone: HostingAiTone,
    val title: String,
    val detail: String = "",
)

data class HostingAiSession(
    val runTag: String,
    val serviceId: String,
    val serviceName: String,
    val model: String,
    val status: HostingAiStatus = HostingAiStatus.RUNNING,
    val steps: List<HostingAiStep> = emptyList(),
    val summary: String = "",
    val changedFiles: List<String> = emptyList(),
    val backupDir: String = "",
    val siteRunning: Boolean = false,
)

data class HostingAiReply(val ok: Boolean, val text: String)

data class HostingAiRun(
    val ok: Boolean,
    val summary: String,
    val changedFiles: List<String>,
    val restarted: Boolean,
    val siteRunning: Boolean,
)

/** One parsed action from the model's JSON reply. */
sealed interface HostingAiAction {
    data class ListFiles(val path: String) : HostingAiAction
    data class ReadFile(val path: String, val offset: Int, val limit: Int) : HostingAiAction
    data class WriteFile(val path: String, val content: String) : HostingAiAction
    data class ReplaceInFile(val path: String, val old: String, val new: String) : HostingAiAction
    data object Restart : HostingAiAction
    data class ReadLogs(val lines: Int) : HostingAiAction
    data class CheckHttp(val path: String) : HostingAiAction
    data class Done(val summary: String) : HostingAiAction
}

/**
 * Models are not equally good at native tool calls, and every provider in the
 * app speaks a different wire format. A single JSON action per turn is small,
 * readable in the transcript and works through every configured route.
 */
object HostingAiParser {
    private val fenced = Regex(
        "```(?:json|javascript|js)?\\s*([\\s\\S]*?)```",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): HostingAiAction? {
        for (candidate in candidates(text)) {
            val parsed = runCatching { Json.lenient.parseToJsonElement(candidate) }.getOrNull()
                ?: continue
            val root = pickObject(parsed) ?: continue
            val merged = mergeArguments(root)
            val name = actionName(root, merged) ?: continue
            val action = build(name, merged) ?: continue
            return action
        }
        return null
    }

    private fun candidates(text: String): List<String> {
        val out = mutableListOf<String>()
        fenced.findAll(text).forEach { match -> out += match.groupValues[1].trim() }
        var index = 0
        while (index < text.length && out.size < 40) {
            if (text[index] == '{') {
                val end = objectEnd(text, index)
                if (end > index) {
                    out += text.substring(index, end + 1)
                    index = end + 1
                    continue
                }
            }
            index++
        }
        return out.distinct()
    }

    /** Brace matching that ignores braces inside JSON strings. */
    private fun objectEnd(text: String, start: Int): Int {
        var depth = 0
        var string = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (string) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> string = false
                }
            } else {
                when (char) {
                    '"' -> string = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }

    private fun pickObject(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> {
            val calls = element["tool_calls"] as? JsonArray
            (calls?.firstOrNull() as? JsonObject) ?: element
        }
        is JsonArray -> element.firstNotNullOfOrNull { it as? JsonObject }
        else -> null
    }

    private fun mergeArguments(root: JsonObject): JsonObject {
        val function = root["function"] as? JsonObject
        val raw = root["arguments"] ?: function?.get("arguments")
        val arguments = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> raw.contentOrNull?.let { text ->
                runCatching { Json.lenient.parseToJsonElement(text) as? JsonObject }.getOrNull()
            }
            else -> null
        } ?: return root
        return JsonObject(root + arguments)
    }

    private fun actionName(root: JsonObject, merged: JsonObject): String? {
        val function = root["function"] as? JsonObject
        return merged.string("action")
            ?: merged.string("tool")
            ?: merged.string("name")
            ?: function?.string("name")
    }

    private fun build(rawName: String, json: JsonObject): HostingAiAction? {
        val name = rawName.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        val path = json.string("path").orEmpty().trim()
        return when (name) {
            "list_files", "list", "ls", "tree" -> HostingAiAction.ListFiles(path)
            "read_file", "read", "cat", "open" -> {
                if (path.isBlank()) null
                else HostingAiAction.ReadFile(
                    path = path,
                    offset = json.int("offset")?.coerceAtLeast(0) ?: 0,
                    limit = json.int("limit")?.coerceIn(1, 800) ?: 400,
                )
            }
            "write_file", "write", "create_file" -> {
                val content = json.string("content")
                if (path.isBlank() || content == null) null
                else HostingAiAction.WriteFile(path, content)
            }
            "replace_in_file", "replace", "edit_file", "edit", "patch" -> {
                val old = json.string("old")
                val replacement = json.string("new")
                if (path.isBlank() || old.isNullOrEmpty() || replacement == null) null
                else HostingAiAction.ReplaceInFile(path, old, replacement)
            }
            "restart", "rebuild", "restart_service" -> HostingAiAction.Restart
            "read_logs", "logs", "log" -> HostingAiAction.ReadLogs(
                (json.int("lines") ?: 80).coerceIn(10, 120),
            )
            "check_http", "check_site", "http", "fetch" -> HostingAiAction.CheckHttp(
                path.ifBlank { "/" },
            )
            "done", "finish", "finished", "complete" -> HostingAiAction.Done(
                json.string("summary")
                    ?: json.string("message")
                    ?: json.string("text")
                    ?: "Finished.",
            )
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

/**
 * Hosting logs and source files often contain credentials. The agent needs to
 * see structure (variable names, URLs, errors) but not secret values.
 */
object SecretMask {
    private val privateKey = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
    )
    private val authHeader = Regex(
        "(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)([^\\s\"',;]{6,})",
    )
    private val cookieHeader = Regex("(?i)((?:set-)?cookie\\s*:\\s*)([^\\n]+)")
    private val quotedAssignment = Regex(
        "(?i)(?<![A-Za-z0-9_.-])([\"']?)([A-Za-z0-9_.-]{2,80})\\1" +
            "(\\s*[:=]\\s*)([\"'])((?:\\\\.|(?!\\4).)*)\\4",
    )
    private val assignment = Regex(
        "(?i)(?<![A-Za-z0-9_.-])([\"']?)([A-Za-z0-9_.-]{2,80})\\1" +
            "(\\s*[:=]\\s*)(?![=!<>])([\"']?)([^\\s\"'`,;}{)]{3,})\\4",
    )
    private val knownToken = Regex(
        "\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|" +
            "github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|" +
            "AKIA[0-9A-Z]{16})\\b",
    )
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val telegramToken = Regex("\\b\\d{8,10}:[A-Za-z0-9_-]{30,}\\b")
    private val urlCredentials = Regex("(://[^/\\s:@]+:)([^@/\\s]+)(@)")

    fun text(input: String): String {
        if (input.isEmpty()) return input
        var out = privateKey.replace(input, "[redacted private key]")
        out = authHeader.replace(out) { "${it.groupValues[1]}[redacted]" }
        out = cookieHeader.replace(out) { "${it.groupValues[1]}[redacted]" }
        out = quotedAssignment.replace(out) { match ->
            val quote = match.groupValues[1]
            val name = match.groupValues[2]
            val separator = match.groupValues[3]
            val valueQuote = match.groupValues[4]
            if (isSecretName(name, true)) {
                "$quote$name$quote$separator$valueQuote[redacted]$valueQuote"
            } else {
                match.value
            }
        }
        out = assignment.replace(out) { match ->
            val quote = match.groupValues[1]
            val name = match.groupValues[2]
            val separator = match.groupValues[3]
            val valueQuote = match.groupValues[4]
            val value = match.groupValues[5]
            if (isSecretName(name, quote.isNotEmpty() || valueQuote.isNotEmpty()) &&
                !looksLikeReference(value)
            ) {
                "$quote$name$quote$separator$valueQuote[redacted]$valueQuote"
            } else {
                match.value
            }
        }
        out = knownToken.replace(out, "[redacted]")
        out = jwt.replace(out, "[redacted jwt]")
        out = telegramToken.replace(out, "[redacted token]")
        out = urlCredentials.replace(out) {
            "${it.groupValues[1]}[redacted]${it.groupValues[3]}"
        }
        return out
    }

    private fun looksLikeReference(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.startsWith("\$") ||
            lower.startsWith("process.") ||
            lower.startsWith("import.") ||
            lower.startsWith("os.") ||
            lower.startsWith("env.") ||
            lower.startsWith("getenv") ||
            lower.startsWith("require(") ||
            lower.startsWith("system.") ||
            lower == "null" || lower == "undefined" || lower == "true" || lower == "false"
    }

    private fun isSecretName(raw: String, quoted: Boolean): Boolean {
        val name = raw.lowercase(Locale.ROOT).replace('-', '_')
        if (name == "key") return quoted || raw.all { !it.isLowerCase() }
        val words = listOf(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "private_key", "access_key", "credential",
        )
        if (words.any { name.contains(it) }) return true
        val upper = raw.uppercase(Locale.ROOT)
        return raw == upper && name.any { it.isLetter() } &&
            (name.endsWith("_key") || name.endsWith("_secret") ||
                name.endsWith("_token") || name.endsWith("_password") ||
                name.endsWith("_pass"))
    }
}
