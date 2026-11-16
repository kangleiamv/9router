package ai.ninerouter.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
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

/** One item inside a folder chosen for hosting. */
data class HostFolderEntry(
    val id: String,
    val name: String,
    val directory: Boolean,
)

/** Folder access used by folder deployments; the app backs it with SAF. */
interface HostFolderSource {
    fun children(parent: String): List<HostFolderEntry>
    fun open(document: String): InputStream?
}

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
     * Copy a folder chosen with the system folder picker into [target].
     * Storage Access Framework hands out document URIs, not paths, so the
     * tree is copied into a staged release and then treated like a ZIP.
     * The chosen folder is the project root, so it is never flattened.
     */
    fun importTree(resolver: ContentResolver, tree: Uri, target: File) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }
            .getOrElse { throw IllegalStateException("That folder cannot be read.") }
        importTree(safSource(resolver, tree), rootId, target)
    }

    /** Copy [source] below [target]; deletes [target] on any failure. */
    internal fun importTree(source: HostFolderSource, rootId: String, target: File) {
        target.mkdirs()
        val base = target.canonicalFile
        var entries = 0
        var files = 0
        var bytes = 0L

        fun walk(documentId: String, dir: File, depth: Int) {
            if (depth > MAX_DEPTH) {
                throw IllegalStateException("That folder nests folders too deeply.")
            }
            source.children(documentId).forEach { entry ->
                entries++
                if (entries > MAX_ENTRIES) {
                    throw IllegalStateException("The folder has more than $MAX_ENTRIES items.")
                }
                val name = entry.name.replace('\\', '/').trim('/')
                if (name.isBlank() || name == "." || name == ".." ||
                    name.contains('/') || name.contains('\u0000')
                ) {
                    throw IllegalStateException("The folder contains an unsafe name: '$name'.")
                }
                val file = File(dir, name)
                val resolved = file.canonicalFile
                if (resolved != base && !resolved.path.startsWith(base.path + File.separator)) {
                    throw IllegalStateException("The folder contains an unsafe path: $name")
                }
                if (entry.directory) {
                    file.mkdirs()
                    walk(entry.id, file, depth + 1)
                } else {
                    files++
                    file.parentFile?.mkdirs()
                    val input = source.open(entry.id)
                        ?: throw IllegalStateException("Could not read $name.")
                    input.use { src ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = src.read(buffer)
                                if (read <= 0) break
                                bytes += read
                                if (bytes > MAX_BYTES) {
                                    throw IllegalStateException("The folder is larger than 200 MB.")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        }

        try {
            walk(rootId, target, 0)
            if (files == 0) throw IllegalStateException("The folder has no files.")
        } catch (t: Throwable) {
            target.deleteRecursively()
            throw t
        }
    }

    private fun safSource(resolver: ContentResolver, tree: Uri): HostFolderSource =
        object : HostFolderSource {
            override fun children(parent: String): List<HostFolderEntry> {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                val cursor = resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                ) ?: throw IllegalStateException("That folder cannot be read.")
                cursor.use {
                    val idAt = it.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    )
                    val nameAt = it.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                    val typeAt = it.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )
                    val result = ArrayList<HostFolderEntry>()
                    while (it.moveToNext()) {
                        val id = it.getString(idAt) ?: continue
                        result += HostFolderEntry(
                            id = id,
                            name = it.getString(nameAt).orEmpty(),
                            directory = it.getString(typeAt) ==
                                DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                    }
                    return result
                }
            }

            override fun open(document: String): InputStream? = resolver.openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(tree, document),
            )
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

    /**
     * The Node starter shipped before 1.3.18 returned 404 for `/` because it
     * read directories instead of their `index.html`. Existing copies are
     * safe to replace; only the generated file matches this test.
     */
    fun isLegacyNodeStarter(file: File): Boolean = runCatching {
        val text = file.readText()
        text.startsWith("// Created by 9Router.") && text.contains("path.normalize(url)")
    }.getOrDefault(false)

    /**
     * Keep the configured entry when it exists in the new archive; otherwise
     * use what this deployment looks like, so replacing a project does not
     * fail on a stale entry from the previous one.
     */
    fun entryFor(dir: File, kind: String, preferred: String): String {
        val detected = detect(dir, kind).entry
        return preferred.takeIf { it.isNotBlank() && File(dir, it).isFile } ?: detected
    }

    private fun nodeEntry(dir: File): String {
        listOf(
            "server.js", "index.js", "app.js", "main.js",
            "server.mjs", "index.mjs", "app.mjs", "main.mjs",
            "server.cjs", "index.cjs",
            "src/server.js", "src/index.js", "src/app.js",
            "src/server.mjs", "src/index.mjs",
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
