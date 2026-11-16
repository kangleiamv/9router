package ai.ninerouter.service.server

import ai.ninerouter.core.ExecPolicy
import ai.ninerouter.core.HostAppLayout
import ai.ninerouter.core.HostBootstrap
import ai.ninerouter.core.HostDeploy
import ai.ninerouter.core.HostLayout
import ai.ninerouter.core.HostRelease
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.HostedKind
import ai.ninerouter.core.HostedKinds
import ai.ninerouter.core.HostedService
import ai.ninerouter.core.HostingPlanner
import ai.ninerouter.core.HostingRuntime
import ai.ninerouter.core.HostingStore
import ai.ninerouter.core.HostStorage
import ai.ninerouter.core.NpmRuntime
import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
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
    /** Hosted services bind IPv4 loopback; probe the same address. */
    private val loopback: InetAddress by lazy {
        runCatching { InetAddress.getByName("127.0.0.1") }
            .getOrElse { InetAddress.getLoopbackAddress() }
    }
    private val lock = Any()
    private val running = mutableMapOf<String, Run>()
    private val stopping = mutableSetOf<String>()
    /** Latest saved definition, so a crash restarts the active release. */
    private val desired = mutableMapOf<String, HostedService>()
    /** Port a Node process reported on its own, used instead of the guess. */
    private val detectedPorts = mutableMapOf<String, Int>()

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
        service.entry,
        service.publicDir,
        service.active,
        service.build,
        service.npmInstall.toString(),
        service.npmBuild.toString(),
        service.env.entries.joinToString(",") { "${it.key}=${it.value}" },
    ).joinToString("|")

    fun searchDirs(context: Context): List<File> = buildList {
        // Bundled runtimes live in the native library directory, the only app
        // location Android 10+ lets an app execute.
        context.applicationInfo?.nativeLibraryDir?.let { add(File(it)) }
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { add(File(it)) }
        add(NpmRuntime.shimDir(context))
        add(NpmRuntime.globalBin(context))
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
     * Android 10+ blocks execve for files in app-writable storage when the
     * app targets 29+. This build targets 28 (like Termux), so its own shims
     * and extracted binaries keep the legacy execution policy.
     */
    private fun execBlocked(context: Context, program: File): Boolean = ExecPolicy.blocked(
        deviceSdk = Build.VERSION.SDK_INT,
        targetSdk = context.applicationInfo.targetSdkVersion,
        path = program.absolutePath,
        writableDirs = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.codeCacheDir,
            context.noBackupFilesDir,
            context.getExternalFilesDir(null),
        ),
    )

    private fun candidates(name: String): List<String> =
        if (name.startsWith("lib") && name.endsWith(".so")) listOf(name)
        else listOf(name, "lib$name.so")

    private fun locate(context: Context, dirs: List<File>, name: String): File? {
        val nativeDir = context.applicationInfo?.nativeLibraryDir?.let(::File)
        for (dir in dirs) {
            for (candidate in candidates(name)) {
                val file = File(dir, candidate)
                if (!file.isFile) continue
                if (file.canExecute()) return file
                // Android extracts native libraries as executable. If a vendor
                // mount makes the probe inconclusive, try the owner bit and
                // keep a native library anyway; exec reports the real error.
                runCatching { file.setExecutable(true, true) }
                if (file.canExecute() || dir == nativeDir) return file
            }
        }
        return null
    }

    /** Absolute binaries for [spec], keyed by their command name. */
    fun resolve(context: Context, spec: HostedKind): Map<String, File> {
        val dirs = searchDirs(context)
        return (spec.binaries + spec.setupBinaries).mapNotNull { name ->
            locate(context, dirs, name)?.let { name to it }
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

    /** Layout for [service]; content points at the active deployment. */
    fun layout(context: Context, service: HostedService): HostLayout {
        val base = layout(context, service.id)
        val release = service.releases.firstOrNull { it.id == service.active } ?: return base
        val dir = File(base.releases, release.id)
        if (!dir.isDirectory) return base
        val sub = service.publicDir.trim('/')
        val content = if (sub.isBlank()) dir else File(dir, sub)
        return base.copy(content = content)
    }

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
     * Repair metadata for a deploy that finished on disk but was interrupted
     * before its release was saved. Recovery prefers the newest folder on
     * disk and re-detects the entry/document root when the saved values were
     * lost with the metadata.
     */
    fun recoverReleases(context: Context, service: HostedService): HostedService {
        if (service.kind !in setOf("static", "php", "node", "python")) return service
        val base = layout(context, service.id)
        val folders = base.releases.listFiles().orEmpty().toList()
        val (releases, active) = HostingStore.recoverReleases(
            service.releases,
            service.active,
            folders,
        )
        if (releases == service.releases && active == service.active) return service
        val dir = File(base.releases, active)
        val detected = if (dir.isDirectory) {
            HostDeploy.detect(dir, service.kind)
        } else {
            HostAppLayout("", "")
        }
        val entry = service.entry.takeIf { it.isNotBlank() }
            ?: detected.entry.takeIf { it.isNotBlank() && File(dir, it).isFile }.orEmpty()
        return service.copy(
            releases = releases,
            active = active,
            entry = entry,
            publicDir = service.publicDir.ifBlank { detected.publicDir },
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
        val marker = File(layout.runtime, ".assets-${spec.id}")
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

    /**
     * Node asks Android for the UID's login shell, which resolves to
     * `hosted/bin/bash` for this app. Nothing creates that path, so every
     * `child_process` call that uses a shell (npm lifecycle scripts, Next's
     * CLI probes) fails with ENOENT. Point it at the platform shell once.
     */
    private fun ensureRuntimeShell(context: Context) {
        val bin = File(runtimeRoot(context), "bin")
        bin.mkdirs()
        listOf("sh", "bash").forEach { name ->
            val link = File(bin, name)
            if (!link.exists()) {
                runCatching { Os.symlink("/system/bin/sh", link.absolutePath) }
            }
        }
    }

    private fun ensureRuntimeDirs(spec: HostedKind, layout: HostLayout) {
        File(layout.runtime, "tmp").mkdirs()
        if (spec.id == "postgres") {
            File(layout.runtime, "lib/postgresql").mkdirs()
            File(layout.runtime, "share/postgresql").mkdirs()
        }
        if (spec.id == "mysql") {
            File(layout.runtime, "share/mariadb").mkdirs()
            // mariadbd looks plugins up here; an absent directory is a warning,
            // an empty one is quieter.
            File(layout.runtime, "lib/mysql/plugin").mkdirs()
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

    /**
     * A failed `initdb` or `--bootstrap` leaves files that the next run refuses
     * to reuse. Only clear a folder that has no completion marker; a valid
     * data directory always carries one.
     */
    private fun prepareDataDir(layout: HostLayout, spec: HostedKind) {
        when (spec.id) {
            "postgres" -> {
                if (File(layout.data, "PG_VERSION").exists()) {
                    restrict(layout.data)
                    return
                }
            }

            "mysql" -> {
                val marker = File(layout.data, HostingPlanner.MARIA_UPGRADE_INFO_FILE)
                if (marker.exists()) {
                    restrict(layout.data)
                    return
                }
                // Data folder from an installer that predates the marker.
                if (File(layout.data, "mysql").isDirectory && File(layout.data, "ibdata1").isFile) {
                    marker.writeText(HostingPlanner.MARIADB_UPGRADE_INFO)
                    restrict(layout.data)
                    return
                }
            }

            else -> return
        }
        layout.data.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        restrict(layout.data)
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
            detectedPorts.remove(service.id)
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
                val layout = layout(app, service)
                ensureRuntimeAssets(app, spec, layout)
                if (spec.id == "node") ensureRuntimeShell(app)
                // A deployment saved before entry detection was fixed can
                // still point at a file that is not in its release. Repair it
                // from the release contents instead of crash-looping Node.
                val content = layout.content
                val effective = if (
                    service.active.isNotBlank() &&
                    spec.id in setOf("node", "python") &&
                    service.entry.isNotBlank() &&
                    !File(content, service.entry).isFile
                ) {
                    val repaired = HostDeploy.entryFor(content, spec.id, "")
                    log(
                        service.id,
                        "Entry '${service.entry}' is missing; using '$repaired'.",
                    )
                    service.copy(entry = repaired)
                } else {
                    service
                }
                val bins = resolve(app, spec)
                if (bins.isEmpty()) {
                    throw IllegalStateException(
                        "No ${spec.label} runtime found. Bundle it as " +
                            "lib${spec.binaries.first()}.so in the APK, add it to PATH, " +
                            "or run it in Termux and publish it as a local link.",
                    )
                }
                val npmCli = if (spec.id == "node") {
                    runCatching { NpmRuntime.ensure(app) }
                        .onFailure {
                            log(
                                service.id,
                                "npm is unavailable: ${it.message ?: it.javaClass.simpleName}",
                            )
                        }
                        .getOrNull()
                } else {
                    null
                }
                NpmRuntime.installShims(app, bins["node"], npmCli)
                if (spec.id == "node") {
                    prepareNodeRelease(app, effective, layout, npmCli, bins["node"])
                }
                val plan = HostingPlanner.plan(
                    service = effective,
                    layout = layout,
                    binaries = bins,
                    searchDirs = searchDirs(app),
                    npmCli = npmCli,
                )
                if (spec.id == "postgres" || spec.id == "mysql") {
                    restrict(File(layout.root, "run"))
                    prepareDataDir(layout, spec)
                }
                if (!plan.program.canExecute()) {
                    runCatching { plan.program.setExecutable(true, true) }
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
                if (service.active.isBlank()) {
                    plan.scaffold.forEach { (file, content) ->
                        val staleStarter = spec.id == "node" &&
                            file.isFile &&
                            HostDeploy.isLegacyNodeStarter(file)
                        if (!file.exists() || staleStarter) {
                            file.parentFile?.mkdirs()
                            file.writeText(content)
                        }
                    }
                }
                synchronized(lock) { desired[service.id] = effective }
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
                    val legacyMysql = spec.id == "mysql" &&
                        File(layout.data, "mysql").isDirectory &&
                        File(layout.data, "ibdata1").isFile
                    if (legacyMysql) {
                        // A data folder created by an older installer is valid;
                        // it only lacks our completion marker.
                        init.marker?.let { marker ->
                            marker.parentFile?.mkdirs()
                            marker.writeText(init.markerText)
                        }
                    } else if (skip == null || !skip.exists()) {
                        try {
                            runBootstrap(init, plan.env, plan.workDir, service.id)
                        } catch (t: Throwable) {
                            // --bootstrap refuses a half-written data folder, so
                            // remove it to let the next start try cleanly.
                            if (init.marker == null || !init.marker.exists()) {
                                layout.data.listFiles()?.forEach {
                                    runCatching { it.deleteRecursively() }
                                }
                                restrict(layout.data)
                            }
                            throw t
                        }
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
                val process = try {
                    builder.start()
                } catch (t: Throwable) {
                    throw IllegalStateException(
                        "Could not run ${plan.program.name} from " +
                            "${plan.program.parent}: ${t.message ?: t.javaClass.simpleName}",
                        t,
                    )
                }
                synchronized(lock) {
                    running[service.id] = Run(process, signature(effective))
                    stopping.remove(service.id)
                }
                HostingRuntime.update(service.id) {
                    it.copy(
                        status = HostStatus.STARTING,
                        port = service.port,
                        message = "Waiting for 127.0.0.1:${service.port}…",
                    )
                }
                scope.launch {
                    pumpLogs(service.id, process, detectPort = spec.id == "node")
                }
                scope.launch { awaitReady(service.id, effective.port, process) }
                scope.launch { watch(app, effective, process) }
            } catch (t: Throwable) {
                fail(service, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun awaitReady(id: String, port: Int, process: Process) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                // watch() normally reports the exit and restarts. If it has
                // not updated the card yet, do not leave "Starting" forever.
                val code = runCatching { process.exitValue() }.getOrDefault(-1)
                HostingRuntime.update(id) { state ->
                    if (state.status == HostStatus.STARTING &&
                        state.message.startsWith("Waiting for")
                    ) {
                        state.copy(
                            status = HostStatus.ERROR,
                            pid = 0,
                            message = "Exited with code $code before opening its port. Check the log.",
                        )
                    } else {
                        state
                    }
                }
                return
            }
            val actual = synchronized(lock) { detectedPorts[id] } ?: port
            if (portOpen(actual)) {
                HostingRuntime.update(id) {
                    it.copy(
                        status = HostStatus.RUNNING,
                        port = actual,
                        message = "",
                        restarts = 0,
                    )
                }
                return
            }
            delay(300)
        }
        if (process.isAlive) {
            val actual = synchronized(lock) { detectedPorts[id] } ?: port
            HostingRuntime.update(id) {
                it.copy(message = "Still starting on 127.0.0.1:$actual…")
            }
        }
    }

    /** Store the port a Node process reported and refresh the UI state. */
    private fun onPortDetected(id: String, port: Int) {
        val fresh = synchronized(lock) {
            if (id in stopping || detectedPorts.containsKey(id)) {
                false
            } else {
                detectedPorts[id] = port
                true
            }
        }
        if (!fresh) return
        HostingRuntime.update(id) {
            it.copy(port = port, message = "Detected Node.js on 127.0.0.1:$port…")
        }
        log(id, "Detected Node.js listening port $port.")
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
        if (synchronized(lock) { service.id in stopping }) {
            HostingRuntime.update(service.id) {
                it.copy(status = HostStatus.STOPPED, pid = 0, message = "Stopped")
            }
            return
        }
        start(context, synchronized(lock) { desired[service.id] } ?: service)
    }

    private suspend fun pumpLogs(id: String, process: Process, detectPort: Boolean = false) {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val port = if (detectPort) HostingPlanner.portFromLog(line) else null
                    if (port != null) onPortDetected(id, port) else log(id, line)
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
            detectedPorts.remove(id)
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
        // Keep the id in [stopping]: watch() may observe the exit after this
        // returns and must not start the service again.
    }

    /**
     * Stop and start a service so new environment values or a freshly
     * generated certificate are picked up by the running process.
     */
    suspend fun restart(context: Context, service: HostedService) {
        val wasRunning = synchronized(lock) { running.containsKey(service.id) }
        stop(service.id)
        if (wasRunning) start(context, service)
    }

    suspend fun stopAll() {
        val ids = synchronized(lock) { running.keys.toList() }
        ids.forEach { id -> runCatching { stop(id) } }
    }

    fun forget(id: String) {
        synchronized(lock) {
            running.remove(id)
            stopping.remove(id)
            detectedPorts.remove(id)
        }
        synchronized(lock) { desired.remove(id) }
        HostingRuntime.clear(id)
    }

    /**
     * Turn a ZIP upload into a new immutable release, run the optional build
     * command, activate it and restart the service when it was running.
     */
    suspend fun deploy(
        context: Context,
        service: HostedService,
        zip: InputStream,
    ): HostedService = deployInto(context, service, flatten = true) { stage ->
        HostDeploy.extract(zip, stage)
    }

    /**
     * Host a folder chosen with the system folder picker. The folder is
     * copied into a release first, so later edits in the original folder do
     * not change what is being served until it is hosted again.
     */
    suspend fun deployFolder(
        context: Context,
        service: HostedService,
        tree: Uri,
    ): HostedService = deployInto(context, service, flatten = false) { stage ->
        var lastReport = 0L
        val copy = HostDeploy.importTree(
            context.contentResolver,
            tree,
            stage,
            // Node projects install their own dependencies. Copying a desktop
            // node_modules through SAF is slow, can exceed the item cap, and
            // may contain binaries built for another CPU.
            skipNodeModules = service.kind == "node" && service.npmInstall,
        ) { progress ->
            val now = System.nanoTime()
            if (now - lastReport >= 500_000_000L) {
                lastReport = now
                HostingRuntime.update(service.id) {
                    it.copy(
                        message = "Copying folder… ${progress.files} files, " +
                            HostStorage.formatBytes(progress.bytes),
                    )
                }
            }
        }
        log(
            service.id,
            "Copied ${copy.files} files (${HostStorage.formatBytes(copy.bytes)})" +
                if (copy.skipped > 0) ", skipped ${copy.skipped} metadata folders" else "",
        )
        if (copy.skippedNodeModules) {
            log(service.id, "Skipped node_modules; installing dependencies for this device.")
        }
    }

    private suspend fun deployInto(
        context: Context,
        service: HostedService,
        flatten: Boolean,
        source: (File) -> Unit,
    ): HostedService = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val spec = HostedKinds.byId(service.kind)
            ?: throw IllegalStateException("Unknown runtime '${service.kind}'.")
        if (spec.id !in setOf("static", "php", "node", "python")) {
            throw IllegalStateException("${spec.label} cannot take a deployment; use a web runtime.")
        }
        val base = layout(app, service.id)
        val releases = base.releases.apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val id = stamp.toString()
        val stage = File(releases, ".stage-$id").apply {
            deleteRecursively()
            mkdirs()
        }
        HostingRuntime.update(service.id) { it.copy(message = "Deploying release $id…") }
        log(service.id, "Deploying release $id…")
        try {
            source(stage)
            if (flatten) HostDeploy.flatten(stage)
            // A ZIP may carry a node_modules built on a desktop. When the app
            // is going to install dependencies anyway, discard it so the tree
            // matches this device and does not waste release storage.
            val bundledModules = File(stage, "node_modules")
            if (
                spec.id == "node" &&
                service.npmInstall &&
                File(stage, "package.json").isFile &&
                bundledModules.exists()
            ) {
                log(service.id, "Ignoring bundled node_modules; npm install rebuilds it.")
                bundledModules.deleteRecursively()
            }
            val npmCli = if (spec.id == "node" && File(stage, "package.json").isFile) {
                runCatching { NpmRuntime.ensure(app) }.getOrElse {
                    throw IllegalStateException(
                        "This project has package.json but the bundled npm is unavailable: " +
                            (it.message ?: it.javaClass.simpleName),
                    )
                }
            } else {
                null
            }
            val node = if (spec.id == "node") resolve(app, HostedKinds.node)["node"] else null
            NpmRuntime.installShims(app, node, npmCli)
            if (
                npmCli != null &&
                service.npmInstall &&
                File(stage, "package.json").isFile &&
                !File(stage, "node_modules").isDirectory &&
                NpmRuntime.manifest(stage)?.needsInstall == true
            ) {
                HostingRuntime.update(service.id) {
                    it.copy(message = "Installing dependencies with bundled npm…")
                }
                runNpmInstall(app, service, base, stage, npmCli, node)
            }
            if (npmCli != null && node != null && service.npmInstall) {
                ensureNextWasm(app, service, base, stage, npmCli, node)
            }
            val buildCommand = when {
                service.build.isNotBlank() -> service.build
                npmCli != null &&
                    service.npmBuild &&
                    NpmRuntime.hasScript(stage, "build") -> {
                    log(
                        service.id,
                        "package.json defines a build script; running " +
                            NpmRuntime.buildCommand(stage),
                    )
                    NpmRuntime.buildCommand(stage)
                }

                else -> null
            }
            if (buildCommand != null) {
                runBuild(app, service, base, stage, npmCli, command = buildCommand)
                markBuilt(buildMarker(base, id), buildCommand)
            }
            // Resolve these before the stage is renamed away.
            val detected = HostDeploy.detect(stage, spec.id)
            // Keep the entry blank when npm owns startup, so the planner
            // resolves scripts.start instead of forcing a guessed file.
            val entry = if (
                spec.id == "node" &&
                service.entry.isBlank() &&
                NpmRuntime.hasScript(stage, "start")
            ) {
                ""
            } else {
                HostDeploy.entryFor(stage, spec.id, service.entry)
            }
            val final = File(releases, id)
            if (!stage.renameTo(final)) {
                stage.copyRecursively(final, overwrite = true)
                stage.deleteRecursively()
            }
            val release = HostRelease(id, stamp, HostStorage.usage(final))
            val kept = (listOf(release) + service.releases.filterNot { it.id == id })
                .take(HostingStore.MAX_RELEASES)
            val updated = service.copy(
                releases = kept,
                active = id,
                publicDir = detected.publicDir,
                entry = entry,
            )
            val keep = kept.map { it.id }.toSet()
            releases.listFiles()
                ?.filter { it.isDirectory && it.name != ".stage-$id" && it.name !in keep }
                ?.forEach { old -> runCatching { old.deleteRecursively() } }
            synchronized(lock) { desired[service.id] = updated }
            restartIfRunning(app, updated, startIfAuto = true)
            HostingRuntime.update(service.id) {
                it.copy(restarts = 0, message = "Release ${updated.active} deployed.")
            }
            log(service.id, "Release $id deployed.")
            updated
        } catch (t: Throwable) {
            runCatching { stage.deleteRecursively() }
            fail(service, "Deploy failed: ${t.message ?: t.javaClass.simpleName}")
            // The previous release kept serving; do not leave it as ERROR.
            if (synchronized(lock) { running.containsKey(service.id) }) {
                HostingRuntime.update(service.id) { it.copy(status = HostStatus.RUNNING) }
            }
            throw t
        }
    }

    /** Point the service at another release and restart it if it was up. */
    suspend fun activate(
        context: Context,
        service: HostedService,
        releaseId: String,
    ): HostedService {
        if (service.releases.none { it.id == releaseId }) {
            throw IllegalStateException("That release is no longer on disk.")
        }
        val updated = service.copy(active = releaseId)
        synchronized(lock) { desired[service.id] = updated }
        restartIfRunning(context.applicationContext, updated)
        return updated
    }

    /** Remove a release that is not serving; the active one is kept. */
    fun deleteRelease(
        context: Context,
        service: HostedService,
        releaseId: String,
    ): HostedService {
        if (service.active == releaseId) {
            throw IllegalStateException("Activate another release before deleting this one.")
        }
        File(layout(context, service.id).releases, releaseId).deleteRecursively()
        return service.copy(releases = service.releases.filterNot { it.id == releaseId })
    }

    private suspend fun restartIfRunning(
        context: Context,
        service: HostedService,
        startIfAuto: Boolean = false,
    ) {
        val running = synchronized(lock) { running.containsKey(service.id) }
        if (running) {
            stop(service.id)
            start(context, service)
        } else if (startIfAuto && service.autoStart) {
            // Auto-start services should serve the new release right away.
            start(context, service)
        }
    }

    /**
     * A recovered release may predate a successful dependency install/build,
     * and an older deploy may have skipped the build entirely. Prepare it once
     * before starting npm; the marker lives outside the immutable release.
     */
    private suspend fun prepareNodeRelease(
        context: Context,
        service: HostedService,
        layout: HostLayout,
        npmCli: File?,
        node: File?,
    ) {
        if (service.active.isBlank() || npmCli == null || node == null) return
        val dir = layout.content
        val manifest = NpmRuntime.manifest(dir) ?: return
        if (
            service.npmInstall &&
            manifest.needsInstall &&
            !File(dir, "node_modules").isDirectory
        ) {
            HostingRuntime.update(service.id) {
                it.copy(message = "Installing dependencies for release ${service.active}…")
            }
            runNpmInstall(context, service, layout, dir, npmCli, node)
        }
        if (service.npmInstall) {
            ensureNextWasm(context, service, layout, dir, npmCli, node)
        }
        val command = when {
            service.build.isNotBlank() -> service.build
            service.npmBuild && manifest.build.isNotBlank() -> NpmRuntime.buildCommand(dir)
            else -> {
                requireNextBuild(manifest, dir)
                return
            }
        }
        val marker = buildMarker(layout, service.active)
        if (marker.isFile && marker.readText() == command) {
            requireNextBuild(manifest, dir)
            return
        }
        HostingRuntime.update(service.id) {
            it.copy(message = "Building release ${service.active}…")
        }
        runBuild(context, service, layout, dir, npmCli, command)
        requireNextBuild(manifest, dir)
        markBuilt(marker, command)
    }

    /** Refuse a `next start` that has no production build instead of looping. */
    private fun requireNextBuild(manifest: NpmRuntime.Manifest, dir: File) {
        val tokens = manifest.start.split(Regex("\\s+"))
        val nextStart = "start" in tokens && tokens.any { it.contains("next") }
        if (nextStart && !File(dir, ".next/BUILD_ID").isFile) {
            throw IllegalStateException(
                "Next.js needs a production build, but .next is missing. " +
                    "Turn on Run npm build or set a build command that creates .next.",
            )
        }
    }

    private fun buildMarker(layout: HostLayout, releaseId: String): File =
        File(layout.root, ".build-$releaseId")

    private fun markBuilt(marker: File, command: String) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(command)
        }
    }

    /**
     * Next 16 needs a wasm SWC binding on android/arm64. Install the matching
     * package when it is not already in the project, so the first build does
     * not depend on Next's on-demand download.
     */
    private suspend fun ensureNextWasm(
        context: Context,
        service: HostedService,
        layout: HostLayout,
        dir: File,
        npmCli: File,
        node: File,
    ) {
        if (NpmRuntime.nextMajor(dir)?.let { it >= 16 } != true) return
        if (File(dir, "node_modules/@next/swc-wasm-nodejs/wasm.js").isFile) return
        val version = NpmRuntime.installedVersion(dir, "next") ?: return
        HostingRuntime.update(service.id) {
            it.copy(message = "Installing Next.js WebAssembly compiler…")
        }
        log(service.id, "Installing @next/swc-wasm-nodejs@$version for android/arm64")
        runTask(
            context = context,
            service = service,
            layout = layout,
            dir = dir,
            program = node.absolutePath,
            args = listOf(
                npmCli.absolutePath,
                "install",
                "--no-save",
                "--package-lock=false",
                "--no-audit",
                "--no-fund",
                "--no-progress",
                "@next/swc-wasm-nodejs@$version",
            ),
            npmCli = npmCli,
            label = "npm install @next/swc-wasm-nodejs",
            timeoutMinutes = 10,
        )
        NpmRuntime.seedNextWasm(dir)
    }

    /** Run the optional build command inside the staged release. */
    private suspend fun runBuild(
        context: Context,
        service: HostedService,
        layout: HostLayout,
        dir: File,
        npmCli: File?,
        command: String = service.build,
    ) {
        val node = resolve(context.applicationContext, HostedKinds.node)["node"]
        // Run the command through a Node preload so command-position
        // node/npm/npx and node_modules/.bin shims are rewritten before the
        // shell tries to exec a file with a /usr/bin/env shebang.
        val runner = if (node != null) {
            File(layout.home, "9router-shell.js").apply {
                parentFile?.mkdirs()
                writeText(HostingPlanner.shellRunnerScript())
            }
        } else {
            null
        }
        NpmRuntime.seedNextWasm(dir)
        log(service.id, "Build: $command")
        runTask(
            context = context,
            service = service,
            layout = layout,
            dir = dir,
            program = runner?.let { node!!.absolutePath } ?: "/system/bin/sh",
            args = if (runner != null) {
                listOf(runner.absolutePath, command)
            } else {
                listOf("-c", NpmRuntime.rewriteShell(command, node, npmCli))
            },
            npmCli = npmCli,
            label = "Build",
            timeoutMinutes = 10,
        )
    }

    /** Install package.json dependencies with the bundled npm CLI. */
    private suspend fun runNpmInstall(
        context: Context,
        service: HostedService,
        layout: HostLayout,
        dir: File,
        npmCli: File,
        node: File?,
    ) {
        val program = node ?: throw IllegalStateException(
            "Node.js is required to install npm dependencies.",
        )
        val args = NpmRuntime.installArgs(dir)
        log(service.id, "npm ${args.joinToString(" ")}")
        runTask(
            context = context,
            service = service,
            layout = layout,
            dir = dir,
            program = program.absolutePath,
            args = listOf(npmCli.absolutePath) + args,
            npmCli = npmCli,
            label = "npm install",
            timeoutMinutes = 20,
        )
    }

    private suspend fun runTask(
        context: Context,
        service: HostedService,
        layout: HostLayout,
        dir: File,
        program: String,
        args: List<String>,
        npmCli: File?,
        label: String,
        timeoutMinutes: Long,
    ) {
        val builder = ProcessBuilder(listOf(program) + args)
            .directory(dir)
            .redirectErrorStream(true)
        val env = buildEnv(context, layout, service, npmCli).toMutableMap()
        val localBins = File(dir, "node_modules/.bin")
        if (localBins.isDirectory) {
            env["PATH"] = localBins.absolutePath + ":" + env["PATH"].orEmpty()
        }
        builder.environment().putAll(env)
        val process = try {
            builder.start()
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Could not run $label: ${t.message ?: t.javaClass.simpleName}",
            )
        }
        val pump = scope.launch { pumpLogs(service.id, process) }
        val code = runCatching {
            if (process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                -1
            }
        }.getOrDefault(-1)
        pump.join()
        if (code != 0) {
            throw IllegalStateException(
                "$label exited with code $code" +
                    if (code == -1) " (timed out after $timeoutMinutes minutes)." else ".",
            )
        }
    }

    private fun buildEnv(
        context: Context,
        layout: HostLayout,
        service: HostedService,
        npmCli: File?,
    ): Map<String, String> {
        val dirs = searchDirs(context)
        // Include the global prefix even before the first install: searchDirs
        // only keeps existing folders, and npm creates files/npm/global during
        // a global install. Later commands and restarts resolve its bins.
        val globalBin = NpmRuntime.globalBin(context).apply { mkdirs() }
        val path = (listOf(globalBin.absolutePath) + dirs.map { it.absolutePath } +
            "/system/bin:/vendor/bin")
            .distinct()
            .joinToString(":")
        val env = linkedMapOf(
            "HOME" to layout.home.absolutePath,
            "TMPDIR" to layout.temp.absolutePath,
            "PATH" to path,
            "LANG" to "C.UTF-8",
        )
        if (dirs.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = dirs.joinToString(":") { it.absolutePath }
        }
        env.putAll(service.env)
        // Every build/install child gets the same preload as a hosted Node
        // process. It fixes shebang scripts and missing node/npm commands.
        val agent = File(layout.home, "9router-port.js")
        layout.home.mkdirs()
        agent.writeText(HostingPlanner.nodeAgentScript())
        val options = env["NODE_OPTIONS"].orEmpty().trim()
        env["NODE_OPTIONS"] = (if (options.isEmpty()) "" else "$options ") +
            "--require ${agent.absolutePath}"
        NpmRuntime.applyEnvironment(env, npmCli, resolve(context, HostedKinds.node)["node"])
        return env
    }

    /**
     * Reconcile running processes with the saved list: stop removed or edited
     * services, start the ones marked auto-start, leave manual ones alone.
     */
    suspend fun apply(context: Context, services: List<HostedService>) {
        // Pick up a release that reached disk before the app died, even when
        // the foreground service starts before the settings screen is open.
        val recovered = withContext(Dispatchers.IO) {
            services.map { recoverReleases(context.applicationContext, it) }
        }
        val current = synchronized(lock) { running.mapValues { it.value.signature } }
        val byId = recovered.associateBy { it.id }
        val toStop = current.keys.filter { id ->
            byId[id]?.let { signature(it) != current[id] } ?: true
        }
        // Edited services restart with their new settings; removed ones stop.
        val toRestart = toStop.filter { byId.containsKey(it) }
        toStop.forEach { runCatching { stop(it) } }
        val saved = recovered.map { it.id }.toSet()
        HostingRuntime.states.value.keys.filterNot { it in saved }.forEach { forget(it) }
        (recovered.filter { it.autoStart } + toRestart.mapNotNull { byId[it] })
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
            detectedPorts.clear()
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
                InetSocketAddress(loopback, port),
                PORT_TIMEOUT_MS,
            )
        }
        true
    }.getOrDefault(false)
}
