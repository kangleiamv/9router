package ai.ninerouter.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Catalog of services people commonly run next to 9Router (Termux, a local
 * dev server, a database) with the port each one uses by default.
 *
 * The app publishes services that already listen on loopback: HTTP apps through
 * the browser tunnel, FTP through the file browser and native TCP services
 * through a Zero Trust TCP hostname.
 */
data class LocalServicePreset(
    val id: String,
    val name: String,
    val kind: String,
    val scheme: String,
    val port: Int,
    val group: String,
) {
    val target: String get() = "$scheme://127.0.0.1:$port"
}

object LocalServices {
    val presets: List<LocalServicePreset> = listOf(
        LocalServicePreset("php", "PHP built-in server", TunnelLinks.KIND_HTTP, "http", 8000, "Web"),
        LocalServicePreset("node", "Node.js", TunnelLinks.KIND_HTTP, "http", 3000, "Web"),
        LocalServicePreset("python", "Python http.server", TunnelLinks.KIND_HTTP, "http", 8000, "Web"),
        LocalServicePreset("flask", "Flask / Django", TunnelLinks.KIND_HTTP, "http", 5000, "Web"),
        LocalServicePreset("vite", "Vite dev server", TunnelLinks.KIND_HTTP, "http", 5173, "Web"),
        LocalServicePreset("nginx", "nginx", TunnelLinks.KIND_HTTP, "http", 8080, "Web"),
        LocalServicePreset("apache", "Apache httpd", TunnelLinks.KIND_HTTP, "http", 8080, "Web"),
        LocalServicePreset("ftp", "FTP server", TunnelLinks.KIND_FTP, "ftp", 2121, "Files"),
        LocalServicePreset("redis", "Redis", TunnelLinks.KIND_TCP, "tcp", 6379, "Database"),
        LocalServicePreset("postgres", "PostgreSQL", TunnelLinks.KIND_TCP, "tcp", 5432, "Database"),
        LocalServicePreset("mysql", "MySQL / MariaDB", TunnelLinks.KIND_TCP, "tcp", 3306, "Database"),
        LocalServicePreset("mongo", "MongoDB", TunnelLinks.KIND_TCP, "tcp", 27017, "Database"),
        LocalServicePreset("ssh", "SSH", TunnelLinks.KIND_TCP, "tcp", 22, "Remote"),
        LocalServicePreset("tcp", "Custom TCP service", TunnelLinks.KIND_TCP, "tcp", 9000, "Remote"),
    )

    /** Ports worth probing when the user asks which local services are up. */
    val defaultPorts: List<Int> = presets.map { it.port }.distinct()

    fun forKind(kind: String): List<LocalServicePreset> = presets.filter { it.kind == kind }

    fun byId(id: String): LocalServicePreset? = presets.firstOrNull { it.id == id }

    fun byPort(port: Int, kind: String? = null): LocalServicePreset? =
        presets.firstOrNull { it.port == port && (kind == null || it.kind == kind) }

    /**
     * Probe loopback ports in parallel. A successful TCP handshake is enough:
     * the service may still reject the protocol, but the port is open.
     */
    suspend fun scan(
        ports: Collection<Int> = defaultPorts,
        timeoutMs: Int = 200,
    ): Set<Int> {
        val candidates = ports.filter { it in 1..65535 }.distinct()
        if (candidates.isEmpty()) return emptySet()
        val timeout = timeoutMs.coerceIn(60, 2_000)
        return coroutineScope {
            candidates.map { port ->
                async(Dispatchers.IO) { if (isOpen(port, timeout)) port else null }
            }.awaitAll().filterNotNull().toSet()
        }
    }

    /**
     * Android's [InetAddress.getLoopbackAddress] returns `::1`, while hosted
     * services bind IPv4 `127.0.0.1`, so probe that address explicitly.
     */
    private fun isOpen(port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
        }
        true
    }.getOrDefault(false)

    /** CLI a user runs on their own machine once the TCP route is local. */
    fun clientCommand(port: Int): String? = when (port) {
        6379 -> "redis-cli -h 127.0.0.1 -p $port"
        5432 -> "psql -h 127.0.0.1 -p $port -U postgres"
        3306 -> "mysql -h 127.0.0.1 -P $port -u root -p"
        27017 -> "mongosh \"mongodb://127.0.0.1:$port\""
        22 -> "ssh -p $port user@127.0.0.1"
        else -> null
    }

    /** Client-side setup: expose the named TCP host locally, then connect. */
    fun remoteCommand(hostname: String, link: TunnelLink): String {
        val access = TunnelLinks.nativeAccessCommand(hostname, link)
        val port = TunnelLinks.targetPort(link.target) ?: return access
        val client = clientCommand(port) ?: return access
        return "$access\n$client"
    }
}
