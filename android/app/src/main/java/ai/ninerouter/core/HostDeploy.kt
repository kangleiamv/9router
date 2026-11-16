package ai.ninerouter.core

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A deployment archive, inspected before it is activated. */
data class HostAppLayout(
    /** Folder to serve inside the release, or blank for the release root. */
    val publicDir: String,
    /** Entry file for Node.js/Python, relative to that folder. */
    val entry: String,
)

/**
 * Unpacks the ZIP a user uploads for a hosted web app. The archive is the
 * equivalent of a Vercel/Render deployment: it becomes an immutable release
 * folder, and only then is it activated.
 */
object HostDeploy {
    const val MAX_ENTRIES = 5_000
    const val MAX_BYTES = 200L * 1024 * 1024
    const val MAX_DEPTH = 24

    /** Extract [zip] into [target]; deletes [target] on any failure. */
    fun extract(zip: InputStream, target: File) {
        target.mkdirs()
        val base = target.canonicalFile
        var entries = 0
        var bytes = 0L
        try {
            ZipInputStream(zip.buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) {
                        throw IllegalStateException("The archive has more than $MAX_ENTRIES entries.")
                    }
                    // Directory entries end with `/`; trim it so the segment
                    // check below does not see an empty last part.
                    val name = entry.name.replace('\\', '/').trim('/')
                    if (name.isBlank()) {
                        input.closeEntry()
                        continue
                    }
                    val parts = name.split('/')
                    if (parts.size > MAX_DEPTH) {
                        throw IllegalStateException("The archive nests folders too deeply.")
                    }
                    if (parts.any { it.isEmpty() || it == "." || it == ".." || it.contains('\u0000') }) {
                        throw IllegalStateException("The archive contains an unsafe path: $name")
                    }
                    val file = File(target, name)
                    val resolved = file.canonicalFile
                    if (resolved != base && !resolved.path.startsWith(base.path + File.separator)) {
                        throw IllegalStateException("The archive contains an unsafe path: $name")
                    }
                    if (entry.isDirectory) {
                        file.mkdirs()
                        input.closeEntry()
                        continue
                    }
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            bytes += read
                            if (bytes > MAX_BYTES) {
                                throw IllegalStateException("The archive is larger than 200 MB.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    input.closeEntry()
                }
            }
            if (entries == 0) throw IllegalStateException("The archive is empty.")
        } catch (t: Throwable) {
            target.deleteRecursively()
            throw t
        }
    }

    /**
     * Projects are often zipped with their folder around them
     * (`my-app/index.php`). Move the single top-level folder's contents up.
     */
    fun flatten(dir: File, depth: Int = 0) {
        if (depth > 4) return
        val children = dir.listFiles()?.filterNot { it.name == "__MACOSX" } ?: return
        if (children.size != 1 || !children[0].isDirectory) return
        val only = children[0]
        val inner = only.listFiles().orEmpty()
        if (inner.any { File(dir, it.name).exists() }) return
        inner.forEach { child ->
            val target = File(dir, child.name)
            if (!child.renameTo(target)) {
                child.copyRecursively(target, overwrite = true)
                child.deleteRecursively()
            }
        }
        only.deleteRecursively()
        flatten(dir, depth + 1)
    }

    /** Choose the folder and entrypoint for a runtime. */
    fun detect(dir: File, kind: String): HostAppLayout = when (kind) {
        "php" -> HostAppLayout(
            publicDir = if (File(dir, "public/index.php").isFile) "public" else "",
            entry = "",
        )

        "node" -> HostAppLayout(
            publicDir = "",
            entry = nodeEntry(dir),
        )

        "python" -> HostAppLayout(
            publicDir = "",
            entry = if (File(dir, "app.py").isFile) "app.py" else "",
        )

        else -> HostAppLayout("", "")
    }

    private fun nodeEntry(dir: File): String {
        listOf(
            "server.js", "index.js", "app.js", "main.js", "src/server.js", "src/index.js",
        ).firstOrNull { File(dir, it).isFile }?.let { return it }
        val main = runCatching {
            Json.parseToJsonElement(File(dir, "package.json").readText())
                .jsonObject["main"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return main
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("..") && !it.startsWith("/") }
            ?: "server.js"
    }
}
