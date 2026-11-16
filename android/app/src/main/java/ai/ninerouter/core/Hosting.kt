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
 * PHP, Node.js, MariaDB and PostgreSQL ship as native libraries for arm64 and
 * execute from the APK native library directory. Python and Redis use a real
 * binary found on PATH. Every service starts on loopback with an app-owned
 * document root or data directory. Processes are supervised: logs are
 * captured, a crashed process is restarted with a backoff, and stopping a
 * service kills the whole process tree.
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
    /** Ships in the APK as an arm64 native library. */
    val bundled: Boolean = false,
) {
    val web: Boolean get() = linkKind == TunnelLinks.KIND_HTTP
}

object HostedKinds {
    val static = HostedKind(
        id = "static",
        label = "Static site",
        blurb = "HTML, CSS and JS served by the bundled static server",
        port = 8080,
        scheme = "http",
        linkKind = TunnelLinks.KIND_HTTP,
        defaultUser = "",
        binaries = listOf("node"),
        bundled = true,
    )
    val php = HostedKind(
        id = "php",
        label = "PHP",
        blurb = "PHP built-in server over a document root",
        port = 8000,
        scheme = "http",
        linkKind = TunnelLinks.KIND_HTTP,
        defaultUser = "",
        binaries = listOf("php"),
        bundled = true,
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
        bundled = true,
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
        // The bundled server is libpg.so so initdb can find it as a sibling.
        binaries = listOf("postgres", "pg"),
        setupBinaries = listOf("initdb"),
        bundled = true,
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
        bundled = true,
    )

    val all: List<HostedKind> = listOf(static, php, node, python, redis, postgres, mysql)

    fun byId(id: String): HostedKind? = all.firstOrNull { it.id == id }

    fun require(id: String): HostedKind =
        byId(id) ?: throw IllegalArgumentException("Unknown hosted runtime '$id'.")
}

/** One extracted deployment of a hosted web app. */
@Serializable
data class HostRelease(
    val id: String,
    val createdAt: Long,
    val bytes: Long,
)

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
    /** Extra process environment, applied on top of the runtime defaults. */
    val env: Map<String, String> = emptyMap(),
    /** Optional build command run inside a fresh deployment folder. */
    val build: String = "",
    /** Deployment history, newest first. */
    val releases: List<HostRelease> = emptyList(),
    /** Active release id; blank serves the legacy `public` folder. */
    val active: String = "",
    /** Folder served inside the active release, for example `public`. */
    val publicDir: String = "",
    /** Public hostname assigned in the Domains tab, for example `shop.example.com`. */
    val domain: String = "",
    /** MySQL schema created for this site, empty until the database is added. */
    val dbName: String = "",
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
    const val MAX_RELEASES = 5
    const val MAX_ENV_VARS = 32

    /** Runtimes that need a generated password before they are published. */
    val DATABASE_KINDS = setOf("redis", "postgres", "mysql")

    /** Public hostname shape accepted for a site domain. */
    private val DOMAIN_REGEX = Regex(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
    )

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
            val env = validateEnv(service.env)
            val build = service.build.trim()
            if (build.length > 500 || build.any { it == '\n' || it == '\r' }) {
                throw IllegalArgumentException("Build command must be one line under 500 characters.")
            }
            if (env.size > MAX_ENV_VARS) {
                throw IllegalArgumentException("At most $MAX_ENV_VARS environment variables are supported.")
            }
            val domain = service.domain.trim().lowercase()
            if (domain.isNotEmpty() && !domain.matches(DOMAIN_REGEX)) {
                throw IllegalArgumentException(
                    "Domain must look like shop.example.com (letters, digits and dashes).",
                )
            }
            if (domain.isNotEmpty() && domain.endsWith(".localhost") &&
                domain.removeSuffix(".localhost").contains('.')
            ) {
                throw IllegalArgumentException("Use a single local name, such as shop.localhost.")
            }
            val dbName = service.dbName.trim().lowercase().let { raw ->
                if (raw.isEmpty()) "" else SiteDatabases.normalize(raw)
            }
            val releases = service.releases
                .distinctBy { it.id }
                .filter { it.id.matches(Regex("[0-9]{1,20}")) }
                .take(MAX_RELEASES)
            service.copy(
                name = name,
                entry = entry,
                domain = domain,
                dbName = dbName,
                username = username,
                password = password,
                port = service.port,
                env = env,
                build = build,
                releases = releases,
                active = service.active.takeIf { id -> releases.any { it.id == id } } ?: "",
                publicDir = if (service.publicDir.contains("..")) "" else service.publicDir.trim('/'),
            )
        }
        val duplicateDomain = normalized.map { it.domain }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateDomain.isNotEmpty()) {
            throw IllegalArgumentException(
                "Domain ${duplicateDomain.first()} is assigned to more than one site.",
            )
        }
        return normalized
    }

    /** Next port at or above [preferred] that no runtime or 9Router service owns. */
    fun nextPort(
        preferred: Int,
        used: Collection<Int>,
        blocked: Collection<Int> = emptyList(),
    ): Int {
        val taken = used.toSet() + blocked.toSet()
        val start = preferred.coerceIn(MIN_PORT, 65535)
        for (port in start..65535) if (port !in taken) return port
        for (port in MIN_PORT until start) if (port !in taken) return port
        throw IllegalArgumentException("No free loopback port is available for a new site.")
    }

    /** Environment names the runtime itself owns. */
    val RESERVED_ENV = setOf(
        "PATH", "HOME", "TMPDIR", "LD_LIBRARY_PATH", "PORT", "HOST", "DOCROOT",
        "PWD", "NODE_ENV", "PGDATA", "LANG", "TZ", "ANDROID_DATA", "ANDROID_ROOT",
    )

    private fun validateEnv(env: Map<String, String>): Map<String, String> {
        val clean = linkedMapOf<String, String>()
        env.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
                throw IllegalArgumentException("Invalid environment name '$rawKey'.")
            }
            if (key.uppercase() in RESERVED_ENV) {
                throw IllegalArgumentException("$key is set by the runtime and cannot be overridden.")
            }
            if (rawValue.length > 4096 || rawValue.contains('\u0000')) {
                throw IllegalArgumentException("$key has an invalid value.")
            }
            clean[key] = rawValue
        }
        return clean
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
    /** Shared runtime files (`share`, `lib`, `tmp`) for PostgreSQL/MariaDB. */
    val runtime: File,
) {
    val logs: File get() = File(root, "logs")

    /** Immutable deployment folders, `releases/<id>`. */
    val releases: File get() = File(root, "releases")
}

data class HostSetupStep(
    val program: File,
    val args: List<String>,
    /** Skip the step when this path already exists. */
    val skipIfExists: File? = null,
)

/**
 * One-shot initialization streamed over stdin, used when the upstream
 * installer is a shell script (MariaDB). [scripts] are concatenated after
 * [prelude] and fed to the process.
 */
data class HostBootstrap(
    val program: File,
    val args: List<String>,
    val scripts: List<File>,
    val prelude: String,
    val skipIfExists: File? = null,
    val marker: File? = null,
    val markerText: String = "",
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
    val init: HostBootstrap? = null,
    /** Plaintext secrets removed once one-shot setup succeeds. */
    val privateFiles: List<File> = emptyList(),
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
        if (searchDirs.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = searchDirs.joinToString(":") { it.absolutePath }
        }
        service.env.forEach { (key, value) -> env[key] = value }
        if (spec.web) {
            val cert = SiteTls.certFile(layout)
            val key = SiteTls.keyFile(layout)
            if (cert.isFile && key.isFile) {
                env["TLS_CERT"] = cert.absolutePath
                env["TLS_KEY"] = key.absolutePath
                env["SSL_CERT_FILE"] = cert.absolutePath
            }
        }

        layout.content.mkdirs()
        layout.data.mkdirs()
        layout.home.mkdirs()
        layout.temp.mkdirs()
        layout.logs.mkdirs()
        layout.runtime.mkdirs()

        return when (spec.id) {
            "static" -> {
                val starter = File(layout.home, "9router-static-server.js")
                env["PORT"] = service.port.toString()
                env["HOST"] = "127.0.0.1"
                env["DOCROOT"] = layout.content.absolutePath
                env["NODE_ENV"] = "production"
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(starter.absolutePath),
                    env = env,
                    workDir = layout.content,
                    files = mapOf(starter to NODE_SERVER),
                    // A static site starts useful: one editable landing page.
                    scaffold = if (service.active.isBlank()) {
                        mapOf(File(layout.content, "index.html") to STATIC_INDEX)
                    } else {
                        emptyMap()
                    },
                    webUrl = service.webUrl,
                )
            }

            "php" -> {
                env["DOCUMENT_ROOT"] = layout.content.absolutePath
                // The CLI server forks workers on Linux; a single request must
                // not block the whole site.
                env["PHP_CLI_SERVER_WORKERS"] = "4"
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(
                        "-n",
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
                // A deployed release with no Node entry file is a static
                // site. Serve it with the built-in server from `home` so the
                // immutable release is never modified and switching releases
                // does not carry a starter file between them.
                val staticFallback =
                    service.active.isNotBlank() && !File(layout.content, entry).isFile
                val starter = File(layout.home, "9router-static-server.js")
                // Hook net.Server.listen so the app's real port is known even
                // when it ignores PORT or calls listen(0).
                val portAgent = File(layout.home, "9router-port.js")
                val nodeOptions = env["NODE_OPTIONS"].orEmpty().trim()
                env["NODE_OPTIONS"] = (if (nodeOptions.isEmpty()) "" else "$nodeOptions ") +
                    "--require ${portAgent.absolutePath}"
                env["PORT"] = service.port.toString()
                env["HOST"] = "127.0.0.1"
                env["DOCROOT"] = layout.content.absolutePath
                env["NODE_ENV"] = "production"
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(if (staticFallback) starter.absolutePath else entry),
                    env = env,
                    workDir = layout.content,
                    files = buildMap {
                        if (staticFallback) put(starter, NODE_SERVER)
                        put(portAgent, NODE_PORT_AGENT)
                    },
                    scaffold = if (
                        !staticFallback && service.active.isBlank() && entry == "server.js"
                    ) {
                        mapOf(File(layout.content, "server.js") to NODE_SERVER)
                    } else {
                        emptyMap()
                    },
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
                    privateFiles = listOf(pwfile),
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
                val share = File(layout.runtime, "share/mariadb")
                val sql = buildString {
                    appendLine("CREATE DATABASE IF NOT EXISTS app;")
                    appendLine(
                        "CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY '$password';",
                    )
                    appendLine("ALTER USER '$user'@'%' IDENTIFIED BY '$password';")
                    appendLine(
                        "CREATE USER IF NOT EXISTS '$user'@'127.0.0.1' IDENTIFIED BY '$password';",
                    )
                    appendLine("ALTER USER '$user'@'127.0.0.1' IDENTIFIED BY '$password';")
                    appendLine("GRANT ALL PRIVILEGES ON *.* TO '$user'@'%' WITH GRANT OPTION;")
                    appendLine(
                        "GRANT ALL PRIVILEGES ON *.* TO '$user'@'127.0.0.1' WITH GRANT OPTION;",
                    )
                    appendLine("FLUSH PRIVILEGES;")
                }
                HostingPlan(
                    program = bin(spec.binaries),
                    args = listOf(
                        "--basedir=${layout.runtime.absolutePath}",
                        "--datadir=${layout.data.absolutePath}",
                        "--port=${service.port}",
                        "--bind-address=127.0.0.1",
                        "--socket=${File(socket, "mysql.sock").absolutePath}",
                        "--pid-file=${File(socket, "mysql.pid").absolutePath}",
                        "--init-file=${initFile.absolutePath}",
                        "--skip-name-resolve",
                        "--lc-messages-dir=${share.absolutePath}",
                        "--character-sets-dir=${File(share, "charsets").absolutePath}",
                        "--console",
                    ),
                    env = env,
                    workDir = layout.data,
                    files = mapOf(initFile to sql),
                    init = HostBootstrap(
                        program = bin(spec.binaries),
                        args = listOf(
                            "--bootstrap",
                            "--basedir=${layout.runtime.absolutePath}",
                            "--datadir=${layout.data.absolutePath}",
                            "--log-warnings=0",
                            "--enforce-storage-engine=",
                            "--plugin-dir=${File(layout.runtime, "lib/mysql/plugin").absolutePath}",
                            "--max_allowed_packet=8M",
                            "--net_buffer_length=16K",
                            "--lc-messages-dir=${share.absolutePath}",
                            "--character-sets-dir=${File(share, "charsets").absolutePath}",
                        ),
                        scripts = MARIA_BOOTSTRAP_FILES.map { File(share, it) },
                        prelude = buildString {
                            appendLine("create database if not exists mysql;")
                            appendLine("use mysql;")
                            appendLine("SET @auth_root_socket=NULL;")
                        },
                        // The marker is only written after a complete bootstrap,
                        // so a half-initialized data folder is retried.
                        skipIfExists = File(layout.data, MARIA_UPGRADE_INFO_FILE),
                        marker = File(layout.data, MARIA_UPGRADE_INFO_FILE),
                        markerText = MARIADB_UPGRADE_INFO,
                    ),
                )
            }

            else -> throw IllegalArgumentException("Unsupported runtime '${spec.id}'.")
        }
    }

    /** Written into a MariaDB data folder after a successful bootstrap. */
    const val MARIADB_UPGRADE_INFO = "13.0.2-MariaDB"
    const val MARIA_UPGRADE_INFO_FILE = "mariadb_upgrade_info"

    private val MARIA_BOOTSTRAP_FILES = listOf(
        "mariadb_system_tables.sql",
        "mariadb_performance_tables.sql",
        "mariadb_system_tables_data.sql",
        "fill_help_tables.sql",
        "maria_add_gis_sp_bootstrap.sql",
        "mariadb_sys_schema.sql",
    )

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

    /** Machine-readable line the injected Node agent writes on listen. */
    const val PORT_MARKER = "__9ROUTER_PORT__="

    /** Port reported by [line], or null when it is not a port marker. */
    fun portFromLog(line: String): Int? {
        if (!line.startsWith(PORT_MARKER)) return null
        return line.removePrefix(PORT_MARKER)
            .trim()
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
    }

    /**
     * Preloaded with `node --require` to report every listening server. Express
     * and plain `http` both end up in `net.Server.listen`, so this also catches
     * apps that hardcode a port or call `listen(0)`.
     */
    private const val NODE_PORT_AGENT = """// 9Router: report the port this Node server listens on.
const net = require('net');
const listen = net.Server.prototype.listen;
net.Server.prototype.listen = function (...args) {
  this.once('listening', () => {
    try {
      const address = this.address();
      if (address && typeof address === 'object' && address.port) {
        process.stdout.write('$PORT_MARKER' + address.port + '\n');
      }
    } catch (err) {}
  });
  return listen.apply(this, args);
};
"""

    /** Starter page for a new static site; safe to replace. */
    private const val STATIC_INDEX = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Your site is live</title>
  <style>
    :root { color-scheme: dark; }
    body { margin: 0; min-height: 100vh; display: grid; place-items: center;
      background: #0b0b0d; color: #f4f4f5; font: 16px/1.6 system-ui, sans-serif; }
    main { max-width: 34rem; padding: 2.5rem; }
    h1 { font-size: 2rem; margin: 0 0 .5rem; }
    p { color: #a1a1aa; }
    code { background: #1a1a1f; padding: .15rem .4rem; border-radius: .35rem; }
    .dot { display: inline-block; width: .6rem; height: .6rem; border-radius: 50%;
      background: #34d399; margin-right: .45rem; }
  </style>
</head>
<body>
  <main>
    <p><span class="dot"></span>Hosted on this device by 9Router</p>
    <h1>Your site is live</h1>
    <p>Edit <code>index.html</code> in the file manager, or deploy a ZIP from the
       Hosting dashboard. Nothing here leaves the device unless you publish it
       through a tunnel.</p>
  </main>
</body>
</html>
"""

    private const val NODE_SERVER = """// Created by 9Router. Replace this file with your own app.
const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.env.PORT || 3000);
const host = process.env.HOST || '127.0.0.1';
const root = path.resolve(process.env.DOCROOT || __dirname);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.pdf': 'application/pdf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2'
};

http.createServer((req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { 'content-type': 'text/plain; charset=utf-8', allow: 'GET, HEAD' });
    res.end('Method not allowed');
    return;
  }
  let pathname;
  try {
    pathname = decodeURIComponent((req.url || '/').split('?')[0]);
  } catch (err) {
    res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Bad request');
    return;
  }
  let target = path.join(root, path.posix.normalize('/' + pathname));
  if (target !== root && !target.startsWith(root + path.sep)) {
    res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Forbidden');
    return;
  }
  fs.stat(target, (statErr, stat) => {
    if (!statErr && stat.isDirectory()) target = path.join(target, 'index.html');
    fs.readFile(target, (err, data) => {
      if (err) {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
        res.end('Not found');
        return;
      }
      res.writeHead(200, {
        'content-type': types[path.extname(target).toLowerCase()] || 'application/octet-stream',
        'content-length': data.length
      });
      if (req.method === 'HEAD') res.end(); else res.end(data);
    });
  });
}).listen(port, host, () => {
  console.log('9Router Node host listening on http://' + host + ':' + port);
});
"""
}
