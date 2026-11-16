package ai.ninerouter.core

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

/**
 * npm for on-device Node hosting.
 *
 * The npm CLI is shipped inside the APK as a ZIP asset (no `node_modules`
 * binary is required to run it) and unpacked once into the app's private
 * folder. Node starts it directly, so Android's ban on executing files from
 * app-writable storage does not apply: the executable is always the bundled
 * `libnode.so`, and npm is just a JavaScript argument.
 *
 * A Mozilla CA bundle is unpacked beside it so npm can reach the registry on
 * Android, where Node's compiled-in OpenSSL paths do not exist.
 */
object NpmRuntime {
    const val VERSION = "11.19.1"
    const val ASSET = "npm/npm-$VERSION.zip"
    const val CA_ASSET = "npm/cacert.pem"
    const val NPM_CLI = "bin/npm-cli.js"
    const val NPX_CLI = "bin/npx-cli.js"
    const val READY = ".ready"

    /** Fields of package.json the hosting planner needs. */
    data class Manifest(
        val name: String = "",
        val start: String = "",
        val build: String = "",
        val dependencies: Int = 0,
    ) {
        val usesNpmStart: Boolean get() = start.isNotBlank()
        val usesNpmBuild: Boolean get() = build.isNotBlank()
        val needsInstall: Boolean get() = dependencies > 0
    }

    fun root(context: Context): File = File(context.filesDir, "npm/$VERSION")

    fun cli(context: Context): File = File(root(context), NPM_CLI)

    fun npx(context: Context): File = File(root(context), NPX_CLI)

    /** Public roots used by Node and npm for outbound HTTPS. */
    fun caFile(context: Context): File = File(root(context), "cacert.pem")

    /** Shared download cache, so redeploys do not fetch the same tarballs twice. */
    fun cacheDir(context: Context): File = File(context.filesDir, "npm/cache")

    fun caFile(npmCli: File): File = File(npmCli.parentFile?.parentFile, "cacert.pem")

    fun cacheDir(npmCli: File): File =
        File(File(npmCli.parentFile?.parentFile?.parentFile, "cache").absolutePath)

    fun installed(context: Context): Boolean {
        val root = root(context)
        return File(root, READY).isFile &&
            File(root, NPM_CLI).isFile &&
            File(root, "cacert.pem").isFile
    }

    /**
     * Unpack npm and its CA bundle on first use. Safe to call repeatedly and
     * from any IO thread; a half-written folder is never used because the
     * marker is written last.
     */
    @Synchronized
    fun ensure(context: Context): File {
        val app = context.applicationContext
        val target = root(app)
        val cli = File(target, NPM_CLI)
        val ca = File(target, "cacert.pem")
        val marker = File(target, READY)
        if (marker.isFile && cli.isFile && ca.isFile) return cli

        val staging = File(target.parentFile, ".stage-$VERSION").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            var entries = 0
            app.assets.open(ASSET).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        if (entries > MAX_ENTRIES) {
                            throw IllegalStateException("The npm bundle has too many files.")
                        }
                        val name = entry.name.replace('\\', '/').trim('/')
                        if (name.isBlank()) {
                            zip.closeEntry()
                            continue
                        }
                        val parts = name.split('/')
                        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
                            throw IllegalStateException("The npm bundle contains an unsafe path.")
                        }
                        val file = File(staging, name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (entries == 0) throw IllegalStateException("The npm bundle is empty.")
            app.assets.open(CA_ASSET).use { input ->
                ca.parentFile?.mkdirs()
                File(staging, "cacert.pem").outputStream().use { output -> input.copyTo(output) }
            }
            File(staging, READY).writeText(VERSION)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (t: Throwable) {
            runCatching { staging.deleteRecursively() }
            throw IllegalStateException(
                "Could not unpack the bundled npm ${VERSION}: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
        return File(target, NPM_CLI)
    }

    /** Parse the package.json in [dir], or null when it is absent or invalid. */
    fun manifest(dir: File): Manifest? {
        val file = File(dir, "package.json")
        if (!file.isFile) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val scripts = root["scripts"] as? JsonObject
            val dependencies = listOf("dependencies", "devDependencies", "optionalDependencies")
                .sumOf { key ->
                    (root[key] as? JsonObject)?.size ?: 0
                }
            Manifest(
                name = name,
                start = scripts?.get("start")?.jsonPrimitive?.contentOrNull.orEmpty(),
                build = scripts?.get("build")?.jsonPrimitive?.contentOrNull.orEmpty(),
                dependencies = dependencies,
            )
        }.getOrNull()
    }

    fun hasScript(dir: File, name: String): Boolean {
        val manifest = manifest(dir) ?: return false
        return when (name) {
            "start" -> manifest.start.isNotBlank()
            "build" -> manifest.build.isNotBlank()
            else -> false
        }
    }

    /** `npm ci` when a lockfile pins the tree, otherwise `npm install`. */
    fun installArgs(dir: File): List<String> {
        val locked = File(dir, "package-lock.json").isFile ||
            File(dir, "npm-shrinkwrap.json").isFile
        // npm ci wipes node_modules and installs exactly the lockfile; plain
        // install resolves package.json when no lock exists.
        return listOf(
            if (locked) "ci" else "install",
            "--no-audit",
            "--no-fund",
            "--no-progress",
            "--foreground-scripts",
        )
    }

    /**
     * Rewrite command-position `npm`/`npx` tokens to the bundled Node and CLI,
     * because a shell script on app storage cannot be executed on Android 10+.
     * Only the first token of a command is touched, not arguments that happen
     * to contain the word npm.
     */
    fun rewriteShell(command: String, node: File?, npmCli: File?): String {
        if (node == null || command.isBlank()) return command
        val nodeQuoted = shellQuote(node.absolutePath)
        // `node` itself is not on PATH: the runtime is libnode.so in the
        // native library directory. Rewrite it together with npm/npx so a
        // build command such as `node build.js && npm run build` works.
        var result = rewriteToken(command, "node", nodeQuoted)
        if (npmCli != null) {
            result = rewriteToken(
                result,
                "npm",
                nodeQuoted + " " + shellQuote(npmCli.absolutePath),
            )
            val npxCli = File(npmCli.parentFile?.parentFile, NPX_CLI)
            if (npxCli.isFile) {
                result = rewriteToken(
                    result,
                    "npx",
                    nodeQuoted + " " + shellQuote(npxCli.absolutePath),
                )
            }
        }
        return result
    }

    /**
     * Replace a command-position token, including after `&&`, `;`, a pipe or
     * a subshell, and after leading `NAME=value` assignments. Arguments that
     * merely contain the word are left alone.
     */
    private fun rewriteToken(command: String, name: String, replacement: String): String {
        val pattern = Regex(
            "(^|[&;|(`]\\s*)" +
                "((?:[A-Za-z_][A-Za-z0-9_]*=[^\\s;&|]+\\s+)*)" +
                Regex.escape(name) +
                "(?=\\s|${'$'})",
        )
        return pattern.replace(command) { match ->
            match.groupValues[1] + match.groupValues[2] + replacement
        }
    }

    /**
     * Environment shared by npm installs, builds and Node runtime processes.
     * Values are applied after the user's own environment, so they cannot be
     * used to break outbound TLS or the shared cache.
     */
    fun applyEnvironment(
        env: MutableMap<String, String>,
        npmCli: File?,
        node: File?,
    ) {
        val ca = npmCli?.let { caFile(it) }
        if (ca != null && ca.isFile) {
            env["NODE_EXTRA_CA_CERTS"] = ca.absolutePath
            env["npm_config_cafile"] = ca.absolutePath
            // Node's compiled-in OpenSSL path is absent on Android; point the
            // default verify paths at the same public bundle.
            env["SSL_CERT_FILE"] = ca.absolutePath
        }
        val cache = npmCli?.let { cacheDir(it) }
        if (cache != null) {
            cache.mkdirs()
            env["npm_config_cache"] = cache.absolutePath
        }
        env["NO_UPDATE_NOTIFIER"] = "1"
        env["npm_config_update_notifier"] = "false"
        env["npm_config_fund"] = "false"
        env["npm_config_audit"] = "false"
        env["npm_config_progress"] = "false"
        env["npm_config_loglevel"] = "warn"
        npmCli?.let { env["NPM_CLI"] = it.absolutePath }
        node?.let { env["NODE_BIN"] = it.absolutePath }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private const val MAX_ENTRIES = 5_000
}
