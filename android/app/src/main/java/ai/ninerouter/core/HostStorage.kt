package ai.ninerouter.core

import java.io.File
import java.util.Locale

/** One entry in a hosted service's code folder. */
data class HostFileEntry(
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * File operations for a hosted service's `public` folder (and the read-only
 * usage figures for its data folder). Paths are always resolved below the
 * service root, so a crafted name cannot leave it.
 */
object HostStorage {
    /** Largest file the in-app editor opens; bigger files are download-only. */
    const val MAX_EDIT_BYTES = 512L * 1024L

    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "html", "htm", "css", "scss", "js", "mjs", "cjs",
        "json", "jsonc", "php", "phtml", "py", "rb", "go", "rs", "c", "h", "cpp",
        "hpp", "java", "kt", "kts", "ts", "tsx", "jsx", "xml", "yml", "yaml",
        "toml", "ini", "conf", "cfg", "properties", "sql", "sh", "bash", "zsh",
        "env", "svg", "map", "csv", "tsv", "log", "htaccess", "gitignore",
    )

    /**
     * Resolve [relative] inside [root], refusing `..`, absolute paths and
     * symlinks that point elsewhere. Returns [root] for a blank path.
     */
    fun resolve(root: File, relative: String): File? {
        val clean = relative.trim().trim('/')
        if (clean.isEmpty()) return root.canonicalFile
        if (clean.startsWith("/") || clean.contains('\\') || clean.contains('\u0000')) return null
        if (clean.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(base, clean).canonicalFile }.getOrNull() ?: return null
        val inside = target == base || target.path.startsWith(base.path + File.separator)
        return if (inside) target else null
    }

    /** Sorted entries: folders first, then names, ignoring nothing. */
    fun entries(dir: File): List<HostFileEntry> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .map { file ->
                HostFileEntry(
                    name = file.name,
                    directory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    modified = file.lastModified(),
                )
            }
            .sortedWith(
                compareByDescending<HostFileEntry> { it.directory }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
    }

    /** Recursive byte size, ignoring symlinked directories. */
    fun usage(dir: File): Long {
        val files = dir.listFiles() ?: return 0L
        var total = 0L
        for (file in files) {
            total += if (file.isDirectory) usage(file) else if (file.isFile) file.length() else 0L
        }
        return total
    }

    fun isText(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.isEmpty() || ext in TEXT_EXTENSIONS
    }

    fun isEditable(file: File): Boolean =
        file.isFile && file.length() <= MAX_EDIT_BYTES && isText(file.name)

    /** Write through a sibling temp file so a crash cannot truncate code. */
    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    fun freeBytes(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        val rounded = if (value >= 100) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$rounded ${units[unit]}"
    }
}
