package ai.ninerouter.service.server

import ai.ninerouter.core.HostLayout
import ai.ninerouter.core.HostedKinds
import ai.ninerouter.core.HostedService
import ai.ninerouter.core.HostingRuntime
import ai.ninerouter.core.SiteDatabases
import ai.ninerouter.core.SiteTls
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One-shot maintenance jobs that reuse the bundled PHP binary: MySQL
 * administration through PDO and self-signed certificate generation through
 * OpenSSL. Both run as short-lived child processes on loopback, never as part
 * of the web runtime, so a failed job cannot take a site down.
 */
object SiteTools {
    data class Exec(val code: Int, val output: String, val error: String) {
        val ok: Boolean get() = code == 0
        val detail: String get() = error.ifBlank { output }.trim().take(400)
    }

    /** Run PHP with the same library path the web runtime uses. */
    suspend fun php(
        context: Context,
        script: String,
        env: Map<String, String> = emptyMap(),
    ): Exec = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val program = HostingManager.resolve(app, HostedKinds.php)["php"]
            ?: throw IllegalStateException("The bundled PHP runtime is not available.")
        val tmp = File(app.cacheDir, "site-tools").apply { mkdirs() }
        val home = File(app.filesDir, "hosting/tools").apply { mkdirs() }
        val builder = ProcessBuilder(
            program.absolutePath,
            "-n",
            "-d", "display_errors=stderr",
            "-r", script,
        ).directory(home)
        val environment = builder.environment()
        environment.clear()
        environment["PATH"] = "/system/bin:/vendor/bin"
        environment["LD_LIBRARY_PATH"] = program.parentFile?.absolutePath.orEmpty()
        environment["HOME"] = home.absolutePath
        environment["TMPDIR"] = tmp.absolutePath
        environment["LANG"] = "C.UTF-8"
        environment.putAll(env)
        val process = builder.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
            }
        }
        val errors = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            }
        }
        reader.start()
        errors.start()
        val finished = process.waitFor(JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        reader.join(2_000)
        errors.join(2_000)
        Exec(
            code = if (finished) process.exitValue() else -1,
            output = out.toString().trim(),
            error = err.toString().trim().ifBlank {
                if (finished) "" else "The maintenance job timed out."
            },
        )
    }

    private suspend fun database(
        context: Context,
        mysql: HostedService,
        script: String,
        sql: String? = null,
    ): Exec {
        if (!HostingRuntime.state(mysql.id).running) {
            throw IllegalStateException("Start the ${mysql.name} service before using databases.")
        }
        return php(
            context = context,
            script = script,
            env = buildMap {
                put("DB_PORT", mysql.port.toString())
                put("DB_USER", mysql.username.ifBlank { "9router" })
                put("DB_PASS", mysql.password)
                if (sql != null) put("DB_SQL", sql)
            },
        )
    }

    suspend fun databaseVersion(context: Context, mysql: HostedService): String {
        val exec = database(context, mysql, SiteDatabases.VERSION_SCRIPT)
        if (!exec.ok) throw IllegalStateException(exec.detail.ifBlank { "Connection failed." })
        return exec.output.removePrefix("OK").trim()
    }

    suspend fun databases(context: Context, mysql: HostedService): List<SiteDatabases.DbInfo> {
        val exec = database(context, mysql, SiteDatabases.ADMIN_SCRIPT)
        if (!exec.ok) throw IllegalStateException(exec.detail.ifBlank { "Could not list databases." })
        return SiteDatabases.parseList(exec.output)
    }

    suspend fun createDatabase(context: Context, mysql: HostedService, name: String): String {
        val db = SiteDatabases.normalize(name)
        val exec = database(context, mysql, SiteDatabases.ADMIN_SCRIPT, SiteDatabases.createSql(db))
        if (!exec.ok) throw IllegalStateException(exec.detail.ifBlank { "Could not create $db." })
        return db
    }

    suspend fun dropDatabase(context: Context, mysql: HostedService, name: String) {
        val exec = database(context, mysql, SiteDatabases.ADMIN_SCRIPT, SiteDatabases.dropSql(name))
        if (!exec.ok) throw IllegalStateException(exec.detail.ifBlank { "Could not drop $name." })
    }

    /** Create (or refresh) the self-signed pair for a hosted site. */
    suspend fun certificate(
        context: Context,
        service: HostedService,
        layout: HostLayout,
    ): SiteTls.Certificate {
        val names = SiteTls.namesFor(service)
        val directory = File(layout.data, SiteTls.DIR).apply { mkdirs() }
        val cert = SiteTls.certFile(layout)
        val key = SiteTls.keyFile(layout)
        val config = File(layout.temp, "tls.cnf")
        config.parentFile?.mkdirs()
        config.writeText(SiteTls.config(names.first(), names))
        val exec = php(
            context = context,
            script = SiteTls.GENERATE_SCRIPT,
            env = mapOf(
                "TLS_CNF" to config.absolutePath,
                "TLS_CN" to names.first(),
                "TLS_DAYS" to SiteTls.DEFAULT_DAYS.toString(),
                "TLS_CERT_OUT" to cert.absolutePath,
                "TLS_KEY_OUT" to key.absolutePath,
            ),
        )
        if (!exec.ok) {
            throw IllegalStateException(exec.detail.ifBlank { "Certificate generation failed." })
        }
        runCatching { config.delete() }
        return SiteTls.read(cert)
            ?: throw IllegalStateException("PHP wrote a certificate that could not be parsed.")
    }

    fun removeCertificate(layout: HostLayout) {
        runCatching { File(layout.data, SiteTls.DIR).deleteRecursively() }
    }

    private const val JOB_TIMEOUT_SECONDS = 90L
}
