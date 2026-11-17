package ai.ninerouter.core

import java.io.File
import java.util.Locale

data class HostingAiToolResult(
    val ok: Boolean,
    val output: String,
    val summary: String,
)

/**
 * File tools available to the hosting doctor. Everything is resolved below
 * one active release; generated output, dependencies and backups are hidden,
 * and sensitive files cannot be written by the model.
 */
class HostingAiTools(
    private val root: File,
    private val backupDir: File? = null,
) {
    private val changed = linkedSetOf<String>()

    fun changedFiles(): List<String> = changed.toList()

    fun backupPath(): String = backupDir?.absolutePath.orEmpty()

    /** Compact evidence for the first prompt: root listing and the manifest. */
    fun contextSummary(): String = buildString {
        appendLine("Active release root: ${root.absolutePath}")
        appendLine("node_modules: ${if (File(root, "node_modules").isDirectory) "present" else "missing"}")
        appendLine(".next build: ${if (File(root, ".next/BUILD_ID").isFile) "present" else "missing"}")
        appendLine("Top-level entries:")
        val entries = root.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) },
        ).orEmpty()
        entries.take(80).forEach { file ->
            if (isBlocked(file)) return@forEach
            appendLine(
                if (file.isDirectory) "  ${file.name}/"
                else "  ${file.name} (${HostStorage.formatBytes(file.length())})",
            )
        }
        val manifest = listOf(
            "package.json",
            "composer.json",
            "requirements.txt",
            "pyproject.toml",
        ).firstOrNull { File(root, it).isFile }
        if (manifest != null) {
            val result = read(manifest, 0, 220)
            if (result.ok) {
                appendLine()
                appendLine("$manifest (truncated):")
                appendLine(result.output.take(12_000))
            }
        }
    }

    fun list(rawPath: String): HostingAiToolResult {
        val path = relative(rawPath)
        val dir = HostStorage.resolve(root, path)
            ?: return failure("That path is outside the active release.")
        if (!dir.isDirectory) return failure("${path.ifBlank { "." }} is not a folder.")
        if (isBlocked(dir)) return failure("Generated and dependency folders are hidden from the agent.")
        val out = StringBuilder()
        var count = 0
        var clipped = false
        fun walk(current: File, prefix: String, depth: Int) {
            if (count >= 300) {
                clipped = true
                return
            }
            val files = current.listFiles()
                ?.sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )
                .orEmpty()
            for (file in files) {
                if (isBlocked(file)) continue
                if (count >= 300) {
                    clipped = true
                    return
                }
                if (file.isDirectory) {
                    out.appendLine("${prefix}${file.name}/")
                    count++
                    if (depth < 2) walk(file, "$prefix${file.name}/", depth + 1)
                } else {
                    val size = HostStorage.formatBytes(file.length())
                    out.appendLine("${prefix}${file.name} ($size)")
                    count++
                }
            }
        }
        walk(dir, if (path.isBlank()) "" else "$path/", 0)
        if (clipped) out.appendLine("… more entries were hidden; ask for a specific folder.")
        if (out.isEmpty()) out.appendLine("(empty folder)")
        return HostingAiToolResult(
            ok = true,
            output = out.toString().trimEnd(),
            summary = "Listed ${path.ifBlank { "release root" }} · $count entries",
        )
    }

    fun read(rawPath: String, offset: Int, limit: Int): HostingAiToolResult {
        val path = relative(rawPath)
        if (path.isBlank()) return failure("read_file needs a file path.")
        val file = HostStorage.resolve(root, path)
            ?: return failure("That path is outside the active release.")
        if (!file.isFile) return failure("$path does not exist.")
        if (isBlocked(file)) return failure("Generated and dependency files are hidden from the agent.")
        if (!HostStorage.isText(file.name)) return failure("$path is not a text file.")
        if (file.length() > HostStorage.MAX_EDIT_BYTES) {
            return failure("$path is larger than 512 KB. Read a smaller source file.")
        }
        val lines = runCatching { file.readLines() }
            .getOrElse { return failure("Could not read $path: ${it.message}") }
        val from = offset.coerceIn(0, lines.size)
        val take = limit.coerceIn(1, 800)
        val selected = lines.drop(from).take(take)
        val total = lines.size
        val header = buildString {
            append("FILE $path")
            if (from > 0 || from + selected.size < total) {
                append(" (lines ${from + 1}-${from + selected.size} of $total)")
            } else {
                append(" ($total lines)")
            }
            if (isSensitive(file)) append(" · secret values masked")
            appendLine()
        }
        var body = selected.joinToString("\n")
        if (body.length > HostingAi.MAX_READ_CHARS) {
            body = body.take(HostingAi.MAX_READ_CHARS) + "\n… output truncated"
        }
        return HostingAiToolResult(
            ok = true,
            output = header + SecretMask.text(body),
            summary = "Read $path · ${selected.size} lines",
        )
    }

    fun write(rawPath: String, content: String): HostingAiToolResult {
        val path = relative(rawPath)
        if (path.isBlank()) return failure("write_file needs a file path.")
        if (content.length > HostingAi.MAX_WRITE_CHARS) {
            return failure("The replacement is too large (${content.length} characters).")
        }
        if (content.contains("[redacted]")) {
            return failure("Do not write masked placeholders. Read the file again and write real source.")
        }
        val target = HostStorage.resolve(root, path)
            ?: return failure("That path is outside the active release.")
        if (isBlocked(target)) return failure("Generated and dependency folders are read-only.")
        if (isSensitive(target)) {
            return failure("$path holds secrets. Ask the user to change it in Environment.")
        }
        if (!HostStorage.isText(target.name)) return failure("Only text source files can be written.")
        if (target.exists()) {
            if (!target.isFile) return failure("$path is not a file.")
            if (target.length() > HostStorage.MAX_EDIT_BYTES) {
                return failure("$path is too large to rewrite.")
            }
            backup(target)?.let { return failure("Could not back up $path: $it") }
        }
        return runCatching {
            HostStorage.writeText(target, content)
            changed += path
            HostingAiToolResult(
                ok = true,
                output = "WROTE $path (${content.length} characters).",
                summary = "Wrote $path · ${HostStorage.formatBytes(content.toByteArray().size.toLong())}",
            )
        }.getOrElse { failure("Could not write $path: ${it.message}") }
    }

    fun replace(rawPath: String, old: String, new: String): HostingAiToolResult {
        val path = relative(rawPath)
        if (path.isBlank()) return failure("replace_in_file needs a file path.")
        if (old.isEmpty() || old.length > 16_000) {
            return failure("The old text must be between 1 and 16000 characters.")
        }
        if (old.contains("[redacted]") || new.contains("[redacted]")) {
            return failure("Replace real text, not masked placeholders.")
        }
        if (new.length > HostingAi.MAX_WRITE_CHARS) {
            return failure("The replacement is too large.")
        }
        val file = HostStorage.resolve(root, path)
            ?: return failure("That path is outside the active release.")
        if (!file.isFile) return failure("$path does not exist.")
        if (isBlocked(file)) return failure("Generated and dependency files are read-only.")
        if (isSensitive(file)) {
            return failure("$path holds secrets. Ask the user to change it in Environment.")
        }
        if (!HostStorage.isText(file.name)) return failure("Only text source files can be edited.")
        if (file.length() > HostStorage.MAX_EDIT_BYTES) return failure("$path is too large to edit.")
        val text = runCatching { file.readText() }
            .getOrElse { return failure("Could not read $path: ${it.message}") }
        val first = text.indexOf(old)
        if (first < 0) {
            return failure(
                "The old text was not found in $path. Read the file again and use an exact line.",
            )
        }
        if (text.indexOf(old, first + old.length) >= 0) {
            return failure("The old text occurs more than once in $path. Include more context.")
        }
        val updated = text.replaceRange(first, first + old.length, new)
        if (updated.length > HostStorage.MAX_EDIT_BYTES) {
            return failure("The edited file would be too large.")
        }
        backup(file)?.let { return failure("Could not back up $path: $it") }
        return runCatching {
            HostStorage.writeText(file, updated)
            changed += path
            HostingAiToolResult(
                ok = true,
                output = "EDITED $path (replaced ${old.length} characters with ${new.length}).",
                summary = "Edited $path",
            )
        }.getOrElse { failure("Could not edit $path: ${it.message}") }
    }

    private fun relative(raw: String): String {
        var path = raw.trim()
        if (path == "." || path == "./") return ""
        while (path.startsWith("./")) path = path.removePrefix("./")
        return path.trim('/')
    }

    private fun backup(file: File): String? {
        val dir = backupDir ?: return null
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull()
            ?: return "path is outside the release"
        val target = File(dir, relative)
        if (target.exists()) return null
        return runCatching {
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = false)
            null
        }.getOrElse { it.message ?: "backup failed" }
    }

    private fun isBlocked(file: File): Boolean {
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }
            .getOrNull()
            ?: return true
        return relative.split('/').any { segment ->
            segment.lowercase(Locale.ROOT) in BLOCKED
        }
    }

    private fun isSensitive(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        if (name == ".env" || name.startsWith(".env.")) return true
        if (name in setOf(".npmrc", ".netrc", "id_rsa", "id_ed25519", "credentials.json")) {
            return true
        }
        return name.endsWith(".pem") || name.endsWith(".key") ||
            name.endsWith(".p12") || name.endsWith(".keystore")
    }

    private fun failure(message: String) = HostingAiToolResult(false, "ERROR: $message", message)

    private companion object {
        val BLOCKED = setOf(
            ".git", "node_modules", ".next", ".cache", ".npm", ".gradle",
            "ai-backups", ".turbo", "coverage",
        )
    }
}
