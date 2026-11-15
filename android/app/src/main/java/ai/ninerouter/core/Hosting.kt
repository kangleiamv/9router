package ai.ninerouter.core

import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Runtimes the app can host on the device: PHP, Node.js, Python, Redis,
 * PostgreSQL and MySQL/MariaDB.
 *
 * The app never bundles these runtimes. It finds a real binary on the device
 * (an app-private `bin` folder, the environment PATH, or Termux), then starts
 * it on loopback with an app-owned document root or data directory. Processes
 * are supervised: logs are captured, a crashed process is restarted with a
 * backoff, and stopping a service kills the whole process tree.
 */
data class HostedKind(
    val id: String,
    val label: String,
    val blurb: String,
    val port: Int,
    val scheme: String,
    val linkKind: String,
    val defaultUser: String,
    val binaries: List<String>,
    val setupBinaries: List<String> = emptyList(),
) {
    val web: Boolean get() = linkKind == TunnelLinks.KIND_HTTP
}

object HostedKinds {
    val php = HostedKind(
        id = "php",
        label = "PHP",
        blurb = "PHP built-in server over a document root",
        port = 8000,
        scheme = "http",
        linkKind = TunnelLinks.KIND_HTTP,
        defaultUser = "",
        binaries = listOf("php"),
    )
    val node = HostedKind(
        id = "node",
        label = "Node.js",
        blurb = "Runs `node server.js` with PORT and DOCROOT",
        port = 3000,
        scheme = "http",
        linkKind = TunnelLinks.KIND_HTTP,
        defaultUser = "",
        binaries = listOf("node"),
    )
    val python = HostedKind(
        id = "python",
        label = "Python",
        blurb = "`http.server` or your own app.py",
        port = 8001,
        scheme = "http",
        linkKind = TunnelLinks.KIND_HTTP,
        defaultUser = "",
        binaries = listOf("python3", "python"),
    )
    val redis = HostedKind(
        id = "redis",
        label = "Redis",
        blurb = "AOF-persistent Redis with a generated password",
        port = 6379,
        scheme = "tcp",
        linkKind = TunnelLinks.KIND_TCP,
        defaultUser = "",
        binaries = listOf("redis-server"),
    )
    val postgres = HostedKind(
        id = "postgres",
        label = "PostgreSQL",
        blurb = "initdb + postgres with scram-sha-256 auth",
        port = 5432,
        scheme = "tcp",
        linkKind = TunnelLinks.KIND_TCP,
        defaultUser = "postgres",
        binaries = listOf("postgres"),
        setupBinaries = listOf("initdb"),
    )
    val mysql = HostedKind(
        id = "mysql",
        label = "MySQL / MariaDB",
        blurb = "mariadbd with an app database and user",
        port = 3306,
        scheme = "tcp",
        linkKind = TunnelLinks.KIND_TCP,
        defaultUser = "9router",
        binaries = listOf("mariadbd", "mysqld"),
        setupBinaries = listOf("mariadb-install-db", "mysql_install_db"),
    )

    val all: List<HostedKind> = listOf(php, node, python, redis, postgres, mysql)

    fun byId(id: String): HostedKind? = all.firstOrNull { it.id == id }

    fun require(id: String): HostedKind =
        byId(id) ?: throw IllegalArgumentException("Unknown hosted runtime '$id'.")
}

/** One configured runtime instance. [password] is encrypted at rest. */
@Serializable
data class HostedService(
    val id: String,
    val kind: String,
    val name: String,
    val port: Int,
    /** Relative entrypoint for Node.js or Python; empty means the default. */
    val entry: String = "",
    val autoStart: Boolean = false,
    val username: String = "",
    val password: String = "",
) {
    val spec: HostedKind? get() = HostedKinds.byId(kind)

    val target: String get() = "${spec?.scheme ?: "tcp"}://127.0.0.1:$port"

    val webUrl: String? get() = if (spec?.web == true) "http://127.0.0.1:$port/" else null
}

enum class HostStatus { STOPPED, STARTING, RUNNING, ERROR }

data class HostServiceState(
    val status: HostStatus = HostStatus.STOPPED,
    val port: Int = 0,
    val pid: Long = 0L,
    val message: String = "",
    val log: List<String> = emptyList(),
    val restarts: Int = 0,
) {
    val running: Boolean get() = status == HostStatus.RUNNING
}

object HostingRuntime {
    private val _states = MutableStateFlow<Map<String, HostServiceState>>(emptyMap())
    val states: StateFlow<Map<String, HostServiceState>> = _states.asStateFlow()

    fun state(id: String): HostServiceState = _states.value[id] ?: HostServiceState()

    internal fun set(id: String, state: HostServiceState) {
        _states.value = _states.value + (id to state)
    }

    internal fun update(id: String, block: (HostServiceState) -> HostServiceState) {
        set(id, block(state(id)))
    }

    internal fun appendLog(id: String, line: String) {
        update(id) { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    internal fun clear(id: String) {
        _states.value = _states.value - id
    }

    private const val MAX_LOG_LINES = 200
}

object HostingStore {
    const val SETTINGS_KEY = "hosted_services"
    const val MAX_SERVICES = 8
    const val MIN_PORT = 1024
    const val MAX_LOG_LINES = 200

    /** Runtimes that need a generated password before they are published. */
    val DATABASE_KINDS = setOf("redis", "postgres", "mysql")

    fun decode(raw: String?): List<HostedService> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.lenient.decodeFromString<List<HostedService>>(raw)
                .map { it.copy(password = Crypto.decrypt(it.password).orEmpty()) }
        }.getOrDefault(emptyList())
    }

    fun encode(services: List<HostedService>): String {
        val safe = require(services)
        return Json.compact.encodeToString(
            safe.map {
                if (it.password.isBlank()) it
                else it.copy(password = Crypto.encrypt(it.password) ?: it.password)
            },
        )
    }

    /**
     * Validate and normalize service definitions. [blockedPorts] holds ports the
     * gateway, public listener, FTP host and passive range already use.
     */
    fun require(
        services: List<HostedService>,
        blockedPorts: Set<Int> = emptySet(),
    ): List<HostedService> {
        if (services.size > MAX_SERVICES) {
            throw IllegalArgumentException("At most $MAX_SERVICES hosted services are supported.")
        }
        val ids = mutableSetOf<String>()
        val ports = mutableMapOf<Int, String>()
        val normalized = services.map { service ->
            val spec = HostedKinds.require(service.kind)
            if (service.id.isBlank() || !service.id.matches(Regex("[a-z0-9-]{1,32}"))) {
                throw IllegalArgumentException("Hosted service id is invalid.")
            }
            if (!ids.add(service.id)) {
                throw IllegalArgumentException("Duplicate hosted service id '${service.id}'.")
            }
            val name = service.name.trim()
            if (name.isEmpty()) {
                throw IllegalArgumentException("Give the ${spec.label} service a name.")
            }
            if (name.length > 40) {
                throw IllegalArgumentException("Hosted service names are limited to 40 characters.")
            }
            if (service.port !in MIN_PORT..65535) {
                throw IllegalArgumentException("${spec.label} port must be between $MIN_PORT and 65535.")
            }
            if (service.port in blockedPorts) {
                throw IllegalArgumentException(
                    "${spec.label} port ${service.port} is already used by 9Router.",
                )
            }
            val owner = ports.put(service.port, name)
            if (owner != null) {
                throw IllegalArgumentException("$name and $owner cannot share port ${service.port}.")
            }
            val entry = service.entry.trim()
            if (entry.contains("..") || entry.startsWith("/") || entry.contains('\\')) {
                throw IllegalArgumentException("Entrypoint must be a file inside the service folder.")
            }
            val username = service.username.trim().ifBlank { spec.defaultUser }
            if (username.any { it.code < 0x20 } || service.password.any { it.code < 0x20 }) {
                throw IllegalArgumentException("Credentials cannot contain control characters.")
            }
            val password = if (spec.id in DATABASE_KINDS && service.password.isBlank()) {
                newPassword()
            } else {
                service.password
            }
            service.copy(
                name = name,
                entry = entry,
                username = username,
                password = password,
                port = service.port,
            )
        }
        return normalized
    }

    /** Fresh id for a new service of [kind]. */
    fun newId(kind: String, existing: Collection<HostedService>): String {
        val base = kind.lowercase()
        if (existing.none { it.id == base }) return base
        var n = 2
        while (existing.any { it.id == "$base-$n" }) n++
        return "$base-$n"
    }

    /** Random, quote-free credential for a database or cache. */
    fun newPassword(): String =
        java.util.UUID.randomUUID().toString().replace("-", "") + "9r"
}

/** App-owned folders for one hosted service. */
data class HostLayout(
    val root: File,
    val content: File,
    val data: File,
    val home: File,
    val temp: File,
) {
    val logs: File get() = File(root, "logs")
}

data class HostSetupStep(
    val program: File,
    val args: List<String>,
    /** Skip the step when this path already exists. */
    val skipIfExists: File? = null,
)

data class HostingPlan(
    val program: File,
    val args: List<String>,
    val env: Map<String, String>,
    val workDir: File,
    /** Files written on every start; secrets are refreshed each time. */
    val files: Map<File, String> = emptyMap(),
    /** Starter files written only when missing, never overwriting user edits. */
    val scaffold: Map<File, String> = emptyMap(),
    val setup: List<HostSetupStep> = emptyList(),
    val webUrl: String? = null,
)

/**
 * Builds the real command line for a runtime. Kept pure so unit tests can
 * assert every argument without starting a process.
 */
object HostingPlanner {
    fun plan(
        service: HostedService,
        layout: HostLayout,
        binaries: Map<String, File>,
        searchDirs: List<File> = emptyList(),
        systemPath: String = "/system/bin:/vendor/bin",
    ): HostingPlan {
        val spec = HostedKinds.require(service.kind)
        fun bin(names: List<String>): File = names.firstNotNullOfOrNull { binaries[it] }
            ?: throw IllegalStateException(
                buildString {
                    append("${spec.label} is not installed: missing ")
                    append(names.joinToString(" or "))
                    append(". Add the binary to ")
                    append(searchDirs.joinToString(", ") { it.absolutePath })
                    append(" or install the runtime.")
                },
            )

        val path = (searchDirs.map { it.absolutePath } + systemPath)
            .filter { it.isNotBlank() }
            .joinToString(":")
        val env = linkedMapOf(
            "HOME" to layout.home.absolutePath,
            "TMPDIR" to layout.temp.absolutePath,
            "PATH" to path,
            "LANG" to "C.UTF-8",
        )
        val dirs = searchDirs.joinToString(":") { "${it.absolutePath}/lib" }
        if (dirs.isNotBlank()) env["LD_LIBRARY_PATH"] = dirs

        layout.content.mkdirs()
        layout.data.mkdirs()
        layout.home.mkdirs()
        layout.temp.mkdirs()
        layout.logs.mkdirs()

        return when (spec.id) {
            "php" -> {
                env["DOCUMENT_ROOT"] = layout.content.absolutePath
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(
                        "-S", "127.0.0.1:${service.port}",
                        "-t", layout.content.absolutePath,
                    ),
                    env = env,
                    workDir = layout.content,
                    scaffold = mapOf(
                        File(layout.content, "index.php") to PHP_INDEX,
                    ),
                    webUrl = service.webUrl,
                )
            }

            "node" -> {
                val entry = service.entry.ifBlank { "server.js" }
                env["PORT"] = service.port.toString()
                env["HOST"] = "127.0.0.1"
                env["DOCROOT"] = layout.content.absolutePath
                env["NODE_ENV"] = "production"
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(entry),
                    env = env,
                    workDir = layout.content,
                    scaffold = mapOf(File(layout.content, "server.js") to NODE_SERVER),
                    webUrl = service.webUrl,
                )
            }

            "python" -> {
                env["PORT"] = service.port.toString()
                env["HOST"] = "127.0.0.1"
                env["PYTHONUNBUFFERED"] = "1"
                env["PYTHONDONTWRITEBYTECODE"] = "1"
                val entry = service.entry.ifBlank { "" }
                val args = if (entry.isEmpty()) {
                    listOf(
                        "-m", "http.server", service.port.toString(),
                        "--bind", "127.0.0.1",
                        "--directory", layout.content.absolutePath,
                    )
                } else {
                    listOf(entry)
                }
                HostingPlan(
                    program = bin(spec.binaries),
                    args = args,
                    env = env,
                    workDir = layout.content,
                    scaffold = if (entry.isEmpty()) {
                        mapOf(File(layout.content, "index.html") to HTML_INDEX)
                    } else {
                        emptyMap()
                    },
                    webUrl = service.webUrl,
                )
            }

            "redis" -> {
                val args = mutableListOf(
                    "--bind", "127.0.0.1",
                    "--port", service.port.toString(),
                    "--dir", layout.data.absolutePath,
                    "--appendonly", "yes",
                    "--appendfsync", "everysec",
                    "--daemonize", "no",
                    "--protected-mode", "yes",
                )
                if (service.password.isNotBlank()) {
                    args += listOf("--requirepass", service.password)
                }
                HostingPlan(
                    program = bin(spec.binaries),
                    args = args,
                    env = env,
                    workDir = layout.data,
                )
            }

            "postgres" -> {
                val user = service.username.ifBlank { spec.defaultUser }
                val password = service.password.ifBlank { HostingStore.newPassword() }
                val pwfile = File(layout.root, "pgpass")
                val socket = File(layout.root, "run").apply { mkdirs() }
                val initdb = bin(spec.setupBinaries)
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(
                        "-D", layout.data.absolutePath,
                        "-p", service.port.toString(),
                        "-h", "127.0.0.1",
                        "-k", socket.absolutePath,
                    ),
                    env = env + mapOf("PGDATA" to layout.data.absolutePath),
                    workDir = layout.data,
                    files = mapOf(pwfile to password + "\n"),
                    setup = listOf(
                        HostSetupStep(
                            program = initdb,
                            args = listOf(
                                "-D", layout.data.absolutePath,
                                "-U", user,
                                "--auth-local=trust",
                                "--auth-host=scram-sha-256",
                                "--pwfile", pwfile.absolutePath,
                                "-E", "UTF8",
                            ),
                            skipIfExists = File(layout.data, "PG_VERSION"),
                        ),
                    ),
                )
            }

            "mysql" -> {
                val user = service.username.ifBlank { spec.defaultUser }
                val password = service.password.ifBlank { HostingStore.newPassword() }
                val initFile = File(layout.root, "init.sql")
                val socket = File(layout.root, "run").apply { mkdirs() }
                val installer = bin(spec.setupBinaries)
                val sql = buildString {
                    appendLine("CREATE DATABASE IF NOT EXISTS app;")
                    appendLine(
                        "CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY '$password';",
                    )
                    appendLine(
                        "CREATE USER IF NOT EXISTS '$user'@'127.0.0.1' IDENTIFIED BY '$password';",
                    )
                    appendLine("GRANT ALL PRIVILEGES ON *.* TO '$user'@'%' WITH GRANT OPTION;")
                    appendLine(
                        "GRANT ALL PRIVILEGES ON *.* TO '$user'@'127.0.0.1' WITH GRANT OPTION;",
                    )
                    appendLine("FLUSH PRIVILEGES;")
                }
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(
                        "--datadir=${layout.data.absolutePath}",
                        "--port=${service.port}",
                        "--bind-address=127.0.0.1",
                        "--socket=${File(socket, "mysql.sock").absolutePath}",
                        "--init-file=${initFile.absolutePath}",
                        "--skip-name-resolve",
                        "--console",
                    ),
                    env = env,
                    workDir = layout.data,
                    files = mapOf(initFile to sql),
                    setup = listOf(
                        HostSetupStep(
                            program = installer,
                            args = listOf(
                                "--datadir=${layout.data.absolutePath}",
                                "--auth-root-authentication-method=normal",
                                "--skip-test-db",
                            ),
                            skipIfExists = File(layout.data, "mysql"),
                        ),
                    ),
                )
            }

            else -> throw IllegalArgumentException("Unsupported runtime '${spec.id}'.")
        }
    }

    private const val PHP_INDEX = """<?php
// Created by 9Router. Replace this file with your own app.
header('Content-Type: text/html; charset=utf-8');
?>
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PHP is running</title>
<style>body{font:16px/1.6 system-ui,sans-serif;max-width:40rem;margin:12vh auto;padding:0 1.25rem;color:#111}
code{background:#f1f5f9;padding:.15rem .4rem;border-radius:.35rem}</style></head>
<body><h1>PHP is running</h1>
<p>Version <code><?php echo htmlspecialchars(PHP_VERSION); ?></code> is serving
<code><?php echo htmlspecialchars(__DIR__); ?></code>.</p>
<p>Edit <code>index.php</code> in the 9Router hosting folder to get started.</p>
</body></html>
"""

    private const val HTML_INDEX = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Python is running</title>
<style>body{font:16px/1.6 system-ui,sans-serif;max-width:40rem;margin:12vh auto;padding:0 1.25rem;color:#111}
code{background:#f1f5f9;padding:.15rem .4rem;border-radius:.35rem}</style></head>
<body><h1>Python is running</h1>
<p><code>python3 -m http.server</code> is serving this folder.</p>
</body></html>
"""

    private const val NODE_SERVER = """// Created by 9Router. Replace this file with your own app.
const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.env.PORT || 3000);
const host = process.env.HOST || '127.0.0.1';
const root = process.env.DOCROOT || __dirname;
const types = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.txt': 'text/plain; charset=utf-8'
};

http.createServer((req, res) => {
  const url = decodeURIComponent((req.url || '/').split('?')[0]);
  const target = path.join(root, path.normalize(url).replace(/^(\.\.[\/\\])+/, ''));
  if (!target.startsWith(root)) {
    res.writeHead(403).end('Forbidden');
    return;
  }
  fs.readFile(target, (err, data) => {
    if (err) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'content-type': types[path.extname(target)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(port, host, () => {
  console.log('9Router Node host listening on http://' + host + ':' + port);
});
"""
}
