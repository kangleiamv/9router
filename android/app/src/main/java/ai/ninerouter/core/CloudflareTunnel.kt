package ai.ninerouter.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

data class TunnelOptions(
    val localPort: Int,
    val mode: String = "quick",
    val token: String = "",
    val hostname: String = "",
)

/**
 * Pure command construction so the quick/named flags can be unit tested without
 * an Android context. Kept separate from the process manager on purpose.
 */
object TunnelCommand {
    const val MODE_QUICK = "quick"
    const val MODE_TOKEN = "token"
    const val METRICS_ADDRESS = "127.0.0.1:0"

    /**
     * Build the argv for cloudflared. The Android PIE connector is executed
     * directly; its cgo resolver uses Android's DNS, so no hosts file or
     * launcher wrapper is involved.
     *
     * HTTP2 avoids cloudflared's QUIC preflight on networks that block UDP
     * 7844. The metrics listener stays on a loopback IP literal so it does not
     * depend on hostname resolution.
     */
    fun build(binary: String, options: TunnelOptions, configPath: String? = null): List<String> {
        return buildList {
            add(binary)
            add("tunnel")
            add("--no-autoupdate")
            add("--retries")
            add(if (options.mode == MODE_TOKEN) "99" else "2")
            add("--protocol")
            add("http2")
            add("--metrics")
            add(METRICS_ADDRESS)
            if (options.mode == MODE_TOKEN) {
                add("run")
                add("--token")
                add(options.token)
            } else {
                add("--url")
                add("http://127.0.0.1:${options.localPort}")
                if (!configPath.isNullOrBlank()) {
                    add("--config")
                    add(configPath)
                }
            }
        }
    }
}

/** Parse cloudflared's human log lines into the information the UI needs. */
object TunnelLog {
    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
    private val QUICK_URL = Regex(
        """https://([a-z0-9-]+)\.trycloudflare\.com""",
        RegexOption.IGNORE_CASE,
    )
    private val REGISTERED = Regex("""Registered tunnel connection""", RegexOption.IGNORE_CASE)

    fun stripAnsi(line: String): String = ANSI.replace(line, "").trimEnd()

    /** Returns the last quick-tunnel URL on the line, ignoring api.trycloudflare.com. */
    fun quickTunnelUrl(message: String): String? {
        var found: String? = null
        for (match in QUICK_URL.findAll(message)) {
            val host = match.groupValues[1].lowercase()
            if (host == "api") continue
            found = "https://$host.trycloudflare.com"
        }
        return found
    }

    fun isRegistered(message: String): Boolean = REGISTERED.containsMatchIn(message)

    fun publicUrlForHostname(hostname: String): String {
        val value = hostname.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        return if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            value
        } else {
            "https://$value"
        }
    }
}

/** Local port for the restricted public surface: proxy port + 1, kept in range. */
object TunnelPorts {
    fun publicPort(proxyPort: Int): Int {
        return if (proxyPort in 1024..65534) proxyPort + 1 else 20128
    }
}

/**
 * Runs the bundled cloudflared arm64 binary as a child process.
 *
 * The binary is Termux's Android/arm64 build, packaged as
 * jniLibs/arm64-v8a/libcloudflared.so. Android extracts it into
 * nativeLibraryDir and executes the PIE directly from there; its cgo resolver
 * uses Android's DNS. Quick tunnels need no account; named tunnels use a token
 * created in the Cloudflare Zero Trust dashboard.
 *
 * Cloudflared points at a restricted loopback server (see KtorProxyServer's
 * public module), never at the full management API.
 *
 * More than one connector can run at once: the gateway owns [GATEWAY_ID] and
 * each quick local link owns its own quick tunnel instance. Every instance has
 * independent generation, process and log state.
 */
object CloudflareTunnel {
    const val GATEWAY_ID = "gateway"

    private const val BINARY_NAME = "libcloudflared.so"
    private const val MIN_BINARY_BYTES = 1_000_000L
    private const val CONNECT_TIMEOUT_MS = 120_000L
    private const val MAX_LOG_LINES = 120

    private class Instance(var sink: TunnelStateSink) {
        var process: Process? = null
        var generation: Int = 0
        var monitor: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val instances = mutableMapOf<String, Instance>()

    /** Path of the packaged binary, or null when this ABI has no binary. */
    fun binaryFile(context: Context): File? {
        val candidates = listOf(
            File(context.applicationInfo.nativeLibraryDir, BINARY_NAME),
            File(context.filesDir, "cloudflared/cloudflared"),
            File(context.filesDir, "cloudflared/cloudflared-arm64"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > MIN_BINARY_BYTES }
    }

    /** Start (or restart) the gateway connector. */
    fun start(context: Context, options: TunnelOptions) {
        startInstance(GATEWAY_ID, context, options, TunnelRuntime)
    }

    /** Stop the gateway connector; quick link connectors are stopped separately. */
    fun stop() = stopInstance(GATEWAY_ID)

    fun fail(message: String) = TunnelRuntime.fail(message)

    internal fun startInstance(
        id: String,
        context: Context,
        options: TunnelOptions,
        sink: TunnelStateSink,
    ) {
        val app = context.applicationContext
        var gen = 0
        var previous: Process? = null
        synchronized(lock) {
            val instance = instances.getOrPut(id) { Instance(sink) }
            instance.sink = sink
            instance.generation += 1
            gen = instance.generation
            previous = instance.process
            instance.process = null
            instance.monitor?.cancel()
            instance.monitor = null
        }
        sink.begin(options.mode, options.localPort, options.hostname)
        previous?.let { killAsync(it) }
        val monitor = scope.launch { run(id, app, options, gen, sink) }
        synchronized(lock) {
            val instance = instances[id]
            if (instance != null && instance.generation == gen) {
                instance.monitor = monitor
            }
        }
    }

    internal fun stopInstance(id: String) {
        var previous: Process? = null
        var sink: TunnelStateSink? = null
        synchronized(lock) {
            val instance = instances[id] ?: return
            instance.generation += 1
            previous = instance.process
            sink = instance.sink
            instance.process = null
            instance.monitor?.cancel()
            instance.monitor = null
        }
        sink?.stopped()
        previous?.let { proc ->
            scope.launch {
                runCatching { proc.destroy() }
                if (!proc.waitFor(4, TimeUnit.SECONDS)) {
                    runCatching { proc.destroyForcibly() }
                }
            }
        }
    }

    internal fun stopAll() {
        val ids = synchronized(lock) { instances.keys.toList() }
        ids.forEach(::stopInstance)
    }

    private fun isCurrent(id: String, gen: Int): Boolean = synchronized(lock) {
        instances[id]?.generation == gen
    }

    /** Report a startup failure only if this attempt was not superseded. */
    private fun failIfCurrent(id: String, gen: Int, message: String) {
        synchronized(lock) {
            val instance = instances[id] ?: return
            if (instance.generation != gen) return
            instance.sink.fail(message)
        }
    }

    private fun attachProcess(id: String, gen: Int, proc: Process): Boolean = synchronized(lock) {
        val instance = instances[id] ?: return@synchronized false
        if (instance.generation != gen) return@synchronized false
        instance.process = proc
        true
    }

    private fun detachProcess(id: String, gen: Int) {
        synchronized(lock) {
            val instance = instances[id] ?: return
            if (instance.generation == gen) instance.process = null
        }
    }

    private fun killAsync(proc: Process) {
        scope.launch {
            runCatching { proc.destroy() }
            if (!proc.waitFor(4, TimeUnit.SECONDS)) {
                runCatching { proc.destroyForcibly() }
            }
        }
    }

    private suspend fun run(
        id: String,
        context: Context,
        options: TunnelOptions,
        gen: Int,
        sink: TunnelStateSink,
    ) {
        val binary = binaryFile(context)
        if (binary == null) {
            failIfCurrent(
                id,
                gen,
                "cloudflared is not bundled for ${Build.SUPPORTED_ABIS.firstOrNull() ?: "this ABI"}. " +
                    "Install the arm64 build of 9Router Android.",
            )
            return
        }
        TunnelAssets.verifyAndroidPie(binary)?.let { reason ->
            failIfCurrent(id, gen, "$reason Reinstall 9Router Android.")
            return
        }
        if (!binary.canExecute()) {
            runCatching { binary.setExecutable(true) }
        }
        if (!binary.canExecute()) {
            failIfCurrent(
                id,
                gen,
                "cloudflared is not executable in this install. Reinstall 9Router Android.",
            )
            return
        }

        sink.update { it.copy(message = "Preparing the Android connector…") }
        val caFile = TunnelAssets.prepareCaBundle(context).getOrElse { error ->
            failIfCurrent(
                id,
                gen,
                error.message ?: "Could not prepare the bundled CA store.",
            )
            return
        }
        if (!isCurrent(id, gen)) return

        val workingDir = File(context.filesDir, "cloudflared").apply { mkdirs() }
        val configPath = if (options.mode == TunnelCommand.MODE_TOKEN) {
            null
        } else {
            val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            File(workingDir, "quick-tunnel-$safeId.yml").apply {
                writeText("# 9Router quick tunnel\n", Charsets.UTF_8)
            }.absolutePath
        }

        val command = TunnelCommand.build(
            binary = binary.absolutePath,
            options = options,
            configPath = configPath,
        )
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = context.filesDir.absolutePath
        builder.environment()["TMPDIR"] = workingDir.absolutePath
        builder.environment()["TUNNEL_TRANSPORT_PROTOCOL"] = "http2"
        builder.environment()["GODEBUG"] = "netdns=cgo"
        builder.environment()["SSL_CERT_FILE"] = caFile.absolutePath

        val proc = try {
            builder.start()
        } catch (t: Throwable) {
            failIfCurrent(id, gen, "Could not start cloudflared: ${t.message ?: t.javaClass.simpleName}")
            return
        }

        if (!attachProcess(id, gen, proc)) {
            runCatching { proc.destroy() }
            return
        }
        sink.update { it.copy(processAlive = true, message = "Connecting to Cloudflare…") }

        val logLines = ArrayList<String>()
        val quickUrl = CompletableDeferred<String>()
        val registered = CompletableDeferred<Unit>()

        val reader = scope.launch {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        if (!isCurrent(id, gen)) break
                        val line = TunnelLog.stripAnsi(raw)
                        if (line.isBlank()) continue
                        logLines += line
                        while (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
                        sink.update { it.copy(logTail = logLines.toList()) }

                        if (options.mode == TunnelCommand.MODE_QUICK) {
                            TunnelLog.quickTunnelUrl(line)?.let { url ->
                                if (!quickUrl.isCompleted) quickUrl.complete(url)
                            }
                        }
                        if (TunnelLog.isRegistered(line)) {
                            if (!registered.isCompleted) registered.complete(Unit)
                            if (sink.status == TunnelStatus.RUNNING) {
                                sink.update { it.copy(message = "Cloudflare tunnel is live.") }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Process killed or stream closed; the monitor below decides state.
            }
        }

        val quickPublicUrl = if (options.mode == TunnelCommand.MODE_QUICK) {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { quickUrl.await() }
        } else {
            null
        }
        val tokenConnected = if (options.mode == TunnelCommand.MODE_TOKEN) {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { registered.await() } != null
        } else {
            false
        }

        if (!isCurrent(id, gen)) {
            reader.cancel()
            return
        }
        val connectedInTime = if (options.mode == TunnelCommand.MODE_QUICK) quickPublicUrl != null else tokenConnected
        if (!connectedInTime) {
            synchronized(lock) {
                val instance = instances[id]
                if (instance != null && instance.generation == gen) instance.generation += 1
                if (instance?.process === proc) instance.process = null
            }
            runCatching { proc.destroy() }
            reader.cancel()
            sink.fail(
                "Cloudflare did not connect within ${CONNECT_TIMEOUT_MS / 1000}s. " +
                    "Check the network and cloudflared log below." +
                    logTailSuffix(logLines),
            )
            return
        }

        val publicUrl = if (options.mode == TunnelCommand.MODE_QUICK) {
            quickPublicUrl.orEmpty()
        } else {
            TunnelLog.publicUrlForHostname(options.hostname)
        }
        sink.connected(
            publicUrl = publicUrl,
            message = if (publicUrl.isBlank()) {
                "Named tunnel connected. Add a public hostname in the Cloudflare dashboard."
            } else {
                "Cloudflare tunnel is live."
            },
        )

        val exitCode = withContext(Dispatchers.IO) { proc.waitFor() }
        runCatching { reader.join() }
        if (!isCurrent(id, gen)) return

        detachProcess(id, gen)
        synchronized(lock) {
            val instance = instances[id]
            if (instance != null && instance.generation == gen) instance.generation += 1
        }
        sink.fail(
            "cloudflared exited (code $exitCode)." + logTailSuffix(logLines),
        )
    }

    private fun logTailSuffix(lines: List<String>): String {
        val tail = lines.takeLast(4).joinToString(" | ")
        return if (tail.isBlank()) "" else " Last log: $tail"
    }
}
