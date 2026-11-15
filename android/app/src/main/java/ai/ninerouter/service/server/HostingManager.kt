package ai.ninerouter.service.server

import ai.ninerouter.core.HostBootstrap
import ai.ninerouter.core.HostLayout
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.HostedKind
import ai.ninerouter.core.HostedKinds
import ai.ninerouter.core.HostedService
import ai.ninerouter.core.HostingPlanner
import ai.ninerouter.core.HostingRuntime
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Starts and supervises the hosted runtimes on loopback.
 *
 * Each service gets an app-owned folder (`files/hosting/<id>`) with `public`
 * for web content and `data` for database files. Binaries come from the app
 * `bin` folder, the environment PATH or Termux; the app never ships them.
 * Output is captured in [HostingRuntime], crashes are restarted with a backoff
 * and stopping a service kills its process.
 */
object HostingManager {
    private const val MAX_RESTARTS = 3
    /** Bump when the bundled runtime files change. */
    private const val RUNTIME_ASSETS = "pg18.6-md13.0.2-1"
    private const val READY_TIMEOUT_MS = 60_000L
    private const val PORT_TIMEOUT_MS = 250

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val running = mutableMapOf<String, Run>()
    private val stopping = mutableSetOf<String>()

    private data class Run(
        val process: Process,
        val signature: String,
    )

    private fun signature(service: HostedService): String = listOf(
        service.kind,
        service.port.toString(),
        service.entry,
        service.username,
        service.password,
        service.autoStart.toString(),
    ).joinToString("|")

    fun searchDirs(context: Context): List<File> = buildList {
        // Bundled runtimes live in the native library directory, the only app
        // location Android 10+ lets an app execute.
        context.applicationInfo?.nativeLibraryDir?.let { add(File(it)) }
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { add(File(it)) }
        add(File(context.filesDir, "bin"))
        add(File(context.filesDir, "hosting/bin"))
        add(File("/system/bin"))
        add(File("/vendor/bin"))
        add(File("/product/bin"))
    }.distinctBy { it.absolutePath }.filter { it.isDirectory }

    /** True when Termux is installed, for the "run it there" hint. */
    fun termuxInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    /**
     * Android 10+ blocks execve for files in app-writable storage, so a binary
     * copied into `files/bin` cannot run there even though it is +x.
     */
    private fun execBlocked(context: Context, program: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val path = program.absolutePath
        val writable = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.codeCacheDir,
            context.noBackupFilesDir,
            context.getExternalFilesDir(null),
        )
        return writable.any { dir -> path == dir.absolutePath || path.startsWith(dir.absolutePath + "/") }
    }

    private fun candidates(name: String): List<String> =
        if (name.startsWith("lib") && name.endsWith(".so")) listOf(name)
        else listOf(name, "lib$name.so")

    /** Absolute binaries for [spec], keyed by their command name. */
    fun resolve(context: Context, spec: HostedKind): Map<String, File> {
        val dirs = searchDirs(context)
        return (spec.binaries + spec.setupBinaries).mapNotNull { name ->
            val found = dirs.firstNotNullOfOrNull { dir ->
                candidates(name).firstNotNullOfOrNull { candidate ->
                    File(dir, candidate).let { if (it.isFile && it.canExecute()) it else null }
                }
            }
            found?.let { name to it }
        }.toMap()
    }

    /** Which runtimes are present, for the hosting card's status badges. */
    fun availability(context: Context): Map<String, Boolean> =
        HostedKinds.all.associate { spec ->
            val bins = resolve(context, spec)
            spec.id to spec.binaries.any { name ->
                bins[name]?.let { !execBlocked(context, it) } ?: false
            }
        }

    fun isRunning(id: String): Boolean = synchronized(lock) { running.containsKey(id) }

    fun layout(context: Context, id: String): HostLayout {
        val root = File(File(context.filesDir, "hosting"), id)
        return HostLayout(
            root = root,
            content = File(root, "public"),
            data = File(root, "data"),
            home = File(root, "home"),
            temp = File(root, "tmp"),
            runtime = runtimeRoot(context),
        )
    }

    /**
     * PostgreSQL is compiled with `/data/data/<package>/hosted` as its prefix,
     * so its share, extension and temporary files must live exactly there.
     * The app owns its data directory root, so creating the sibling folder is
     * allowed; `/data/data` resolves to the per-user mount.
     */
    private fun runtimeRoot(context: Context): File =
        File("/data/data/${context.packageName}/hosted")

    /**
     * Copy the data files PostgreSQL and MariaDB load at runtime out of the
     * APK. They are extracted once per runtime revision instead of on every
     * start, because the pair is a few megabytes and both servers mmap them.
     */
    private fun ensureRuntimeAssets(context: Context, spec: HostedKind, layout: HostLayout) {
        if (spec.id !in setOf("postgres", "mysql")) return
        val marker = File(layout.runtime, ".assets")
        if (marker.isFile && marker.readText().trim() == RUNTIME_ASSETS + "-" + spec.id) {
            ensureRuntimeDirs(spec, layout)
            return
        }
        log(spec.id, "Extracting bundled ${spec.label} files…")
        when (spec.id) {
            "postgres" -> {
                // Share and extension files are replaced together.
                File(layout.runtime, "share/postgresql").deleteRecursively()
                File(layout.runtime, "lib/postgresql").deleteRecursively()
                copyAssetTree(context, "runtimes/postgres/share", File(layout.runtime, "share"))
                copyAssetTree(context, "runtimes/postgres/lib", File(layout.runtime, "lib"))
            }

            "mysql" -> {
                File(layout.runtime, "share/mariadb").deleteRecursively()
                copyAssetTree(context, "runtimes/mariadb/share", File(layout.runtime, "share"))
            }
        }
        marker.parentFile?.mkdirs()
        marker.writeText(RUNTIME_ASSETS + "-" + spec.id)
        ensureRuntimeDirs(spec, layout)
    }

    private fun ensureRuntimeDirs(spec: HostedKind, layout: HostLayout) {
        File(layout.runtime, "tmp").mkdirs()
        if (spec.id == "postgres") {
            File(layout.runtime, "lib/postgresql").mkdirs()
            File(layout.runtime, "share/postgresql").mkdirs()
        }
        if (spec.id == "mysql") {
            File(layout.runtime, "share/mariadb").mkdirs()
        }
    }

    /** Recursive asset copy; assets cannot contain empty directories. */
    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree(context, "$assetPath/$child", File(target, child)) }
    }

    /** Owner-only directory, required by PostgreSQL for its data directory. */
    private fun restrict(dir: File) {
        dir.mkdirs()
        dir.setReadable(false, false)
        dir.setWritable(false, false)
        dir.setExecutable(false, false)
        dir.setReadable(true, true)
        dir.setWritable(true, true)
        dir.setExecutable(true, true)
    }

    /** Start one service. Safe to call twice; does nothing while it runs. */
    suspend fun start(context: Context, service: HostedService) {
        val spec = HostedKinds.byId(service.kind) ?: return fail(
            service,
            "Unknown runtime '${service.kind}'.",
        )
        synchronized(lock) {
            if (running.containsKey(service.id)) return
            stopping.remove(service.id)
        }
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            HostingRuntime.update(service.id) {
                HostServiceState(
                    status = HostStatus.STARTING,
                    port = service.port,
                    message = "Preparing ${spec.label}…",
                    log = it.log,
                )
            }
            try {
                val layout = layout(app, service.id)
                ensureRuntimeAssets(app, spec, layout)
                val bins = resolve(app, spec)
                if (bins.isEmpty()) {
                    throw IllegalStateException(
                        "No ${spec.label} runtime found. Bundle it as " +
                            "lib${spec.binaries.first()}.so in the APK, add it to PATH, " +
                            "or run it in Termux and publish it as a local link.",
                    )
                }
                val plan = HostingPlanner.plan(
                    service = service,
                    layout = layout,
                    binaries = bins,
                    searchDirs = searchDirs(app),
                )
                if (spec.id == "postgres" || spec.id == "mysql") {
                    restrict(layout.data)
                    restrict(File(layout.root, "run"))
                }
                if (execBlocked(app, plan.program)) {
                    throw IllegalStateException(
                        "Android 10+ blocks executing ${plan.program.name} from app storage. " +
                            "Bundle it as a native library (lib${plan.program.name}.so), " +
                            "or run it in Termux and publish it as a local link.",
                    )
                }
                plan.files.forEach { (file, content) ->
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
                plan.scaffold.forEach { (file, content) ->
                    if (!file.exists()) {
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                    }
                }
                for (step in plan.setup) {
                    val skip = step.skipIfExists
                    if (skip != null && skip.exists()) continue
                    log(service.id, "Running ${step.program.name} ${step.args.joinToString(" ")}")
                    val code = runSetup(step.program, step.args, plan.env, plan.workDir, service.id)
                    if (code != 0) {
                        throw IllegalStateException(
                            "${step.program.name} exited with code $code during setup.",
                        )
                    }
                }
                plan.init?.let { init ->
                    val skip = init.skipIfExists
                    if (skip == null || !skip.exists()) {
                        runBootstrap(init, plan.env, plan.workDir, service.id)
                    }
                }
                // The password file is only needed while initdb runs.
                plan.privateFiles.forEach { runCatching { it.delete() } }
                if (synchronized(lock) { stopping.remove(service.id) }) {
                    HostingRuntime.update(service.id) {
                        it.copy(status = HostStatus.STOPPED, message = "Stopped")
                    }
                    return@withContext
                }
                if (portOpen(service.port)) {
                    throw IllegalStateException(
                        "${spec.label} port ${service.port} is already in use on loopback.",
                    )
                }
                val command = listOf(plan.program.absolutePath) + plan.args
                log(service.id, "Starting ${command.joinToString(" ")}")
                val builder = ProcessBuilder(command)
                    .directory(plan.workDir)
                    .redirectErrorStream(true)
                builder.environment().putAll(plan.env)
                val process = builder.start()
                synchronized(lock) {
                    running[service.id] = Run(process, signature(service))
                    stopping.remove(service.id)
                }
                HostingRuntime.update(service.id) {
                    it.copy(
                        status = HostStatus.STARTING,
                        port = service.port,
                        message = "Waiting for 127.0.0.1:${service.port}…",
                    )
                }
                scope.launch { pumpLogs(service.id, process) }
                scope.launch { awaitReady(service.id, service.port, process) }
                scope.launch { watch(app, service, process) }
            } catch (t: Throwable) {
                fail(service, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun awaitReady(id: String, port: Int, process: Process) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return
            if (portOpen(port)) {
                HostingRuntime.update(id) {
                    it.copy(
                        status = HostStatus.RUNNING,
                        message = "",
                        restarts = 0,
                    )
                }
                return
            }
            delay(300)
        }
        if (process.isAlive) {
            HostingRuntime.update(id) {
                it.copy(message = "Still starting on 127.0.0.1:$port…")
            }
        }
    }

    private suspend fun watch(context: Context, service: HostedService, process: Process) {
        val code = process.waitFor()
        val wasStopping = synchronized(lock) {
            running.remove(service.id)
            stopping.remove(service.id)
        }
        if (wasStopping) {
            HostingRuntime.update(service.id) {
                it.copy(status = HostStatus.STOPPED, pid = 0, message = "Stopped")
            }
            return
        }
        val current = HostingRuntime.state(service.id)
        val restarts = current.restarts + 1
        if (restarts > MAX_RESTARTS) {
            HostingRuntime.update(service.id) {
                it.copy(
                    status = HostStatus.ERROR,
                    pid = 0,
                    restarts = restarts,
                    message = "Stopped with code $code. Restart the service to try again.",
                )
            }
            return
        }
        HostingRuntime.update(service.id) {
            it.copy(
                status = HostStatus.STARTING,
                pid = 0,
                restarts = restarts,
                message = "Exited with code $code. Restarting ($restarts/$MAX_RESTARTS)…",
            )
        }
        log(service.id, "Exited with code $code. Restarting in ${restarts}s.")
        delay(1_000L * restarts)
        start(context, service)
    }

    private suspend fun pumpLogs(id: String, process: Process) {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    log(id, line)
                }
            }
        }
        runCatching { process.inputStream.close() }
    }

    private fun log(id: String, line: String) {
        HostingRuntime.appendLog(id, line.take(500))
    }

    private fun fail(service: HostedService, message: String) {
        HostingRuntime.update(service.id) {
            HostServiceState(
                status = HostStatus.ERROR,
                port = service.port,
                message = message,
                log = it.log + message,
                restarts = it.restarts,
            )
        }
    }

    /**
     * Feed the concatenated SQL scripts to a one-shot initializer (MariaDB
     * --bootstrap). Output is captured like a normal service log.
     */
    private suspend fun runBootstrap(
        init: HostBootstrap,
        env: Map<String, String>,
        workDir: File,
        id: String,
    ) {
        log(id, "Running ${init.program.name} ${init.args.joinToString(" ")}")
        val builder = ProcessBuilder(listOf(init.program.absolutePath) + init.args)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val pump = scope.launch { pumpLogs(id, process) }
        runCatching {
            process.outputStream.use { out -> writeBootstrap(init, out) }
        }.onFailure {
            runCatching { process.destroyForcibly() }
            throw IllegalStateException("Could not write the ${init.program.name} bootstrap: ${it.message}")
        }
        val code = runCatching {
            if (process.waitFor(5, TimeUnit.MINUTES)) process.exitValue() else {
                process.destroyForcibly()
                -1
            }
        }.getOrDefault(-1)
        pump.join()
        if (code != 0) {
            throw IllegalStateException(
                "${init.program.name} exited with code $code while creating the database.",
            )
        }
        init.marker?.let { marker ->
            marker.parentFile?.mkdirs()
            marker.writeText(init.markerText)
        }
        log(id, "Database files created.")
    }

    private fun writeBootstrap(init: HostBootstrap, out: OutputStream) {
        out.write(init.prelude.toByteArray(Charsets.UTF_8))
        init.scripts.forEach { script ->
            if (!script.isFile) {
                throw IllegalStateException("Missing bootstrap script ${script.absolutePath}.")
            }
            script.inputStream().use { it.copyTo(out) }
        }
        out.flush()
    }

    private fun runSetup(
        program: File,
        args: List<String>,
        env: Map<String, String>,
        workDir: File,
        id: String,
    ): Int {
        val builder = ProcessBuilder(listOf(program.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    log(id, line)
                }
            }
        }
        return runCatching {
            if (process.waitFor(5, TimeUnit.MINUTES)) process.exitValue() else {
                process.destroyForcibly()
                -1
            }
        }.getOrDefault(-1)
    }

    /** Stop a service and its process tree. */
    suspend fun stop(id: String) = withContext(Dispatchers.IO) {
        val run = synchronized(lock) {
            stopping.add(id)
            running.remove(id)
        }
        HostingRuntime.update(id) {
            it.copy(status = HostStatus.STOPPED, pid = 0, message = "Stopped")
        }
        val process = run?.process ?: return@withContext
        runCatching { process.destroy() }
        if (!runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
        synchronized(lock) { stopping.remove(id) }
    }

    suspend fun stopAll() {
        val ids = synchronized(lock) { running.keys.toList() }
        ids.forEach { id -> runCatching { stop(id) } }
    }

    fun forget(id: String) {
        synchronized(lock) {
            running.remove(id)
            stopping.remove(id)
        }
        HostingRuntime.clear(id)
    }

    /**
     * Reconcile running processes with the saved list: stop removed or edited
     * services, start the ones marked auto-start, leave manual ones alone.
     */
    suspend fun apply(context: Context, services: List<HostedService>) {
        val current = synchronized(lock) { running.mapValues { it.value.signature } }
        val byId = services.associateBy { it.id }
        val toStop = current.keys.filter { id ->
            byId[id]?.let { signature(it) != current[id] } ?: true
        }
        // Edited services restart with their new settings; removed ones stop.
        val toRestart = toStop.filter { byId.containsKey(it) }
        toStop.forEach { runCatching { stop(it) } }
        val saved = services.map { it.id }.toSet()
        HostingRuntime.states.value.keys.filterNot { it in saved }.forEach { forget(it) }
        (services.filter { it.autoStart } + toRestart.mapNotNull { byId[it] })
            .distinctBy { it.id }
            .forEach { runCatching { start(context, it) } }
    }

    /**
     * Kill every process without suspending, for service teardown. The next
     * start recreates state from settings.
     */
    fun stopAllNow() {
        val current = synchronized(lock) {
            stopping.addAll(running.keys)
            running.values.toList().also { running.clear() }
        }
        current.forEach { run ->
            runCatching { run.process.destroy() }
            runCatching { run.process.destroyForcibly() }
        }
    }

    /** Start auto-start services without touching the rest. */
    suspend fun startAuto(context: Context, services: List<HostedService>) {
        services.filter { it.autoStart }.forEach { runCatching { start(context, it) } }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                PORT_TIMEOUT_MS,
            )
        }
        true
    }.getOrDefault(false)
}
