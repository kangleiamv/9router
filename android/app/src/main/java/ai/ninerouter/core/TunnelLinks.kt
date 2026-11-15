package ai.ninerouter.core

import ai.ninerouter.util.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URI
import java.util.UUID

/**
 * One local web service exposed through the tunnel.
 *
 * [mode] decides how the link is published:
 * - `quick`: the link owns an independent cloudflared quick tunnel and gets a
 *   unique `trycloudflare.com` URL at its root. [host] and [path] are ignored.
 * - `named`: the link is matched on the shared Zero Trust tunnel by public
 *   hostname, path prefix, or both.
 *
 * An empty mode is resolved from [host] when stored links are read, so links
 * created before per-link modes keep working: hostname links become named and
 * path-only links become quick.
 */
@Serializable
data class TunnelLink(
    val id: String,
    val name: String,
    val host: String = "",
    val path: String = "",
    val target: String,
    val stripPrefix: Boolean = true,
    val enabled: Boolean = true,
    val mode: String = "",
)

/** Unvalidated editor values. */
data class TunnelLinkDraft(
    val name: String = "",
    val host: String = "",
    val path: String = "",
    val target: String = "",
    val stripPrefix: Boolean = true,
    val enabled: Boolean = true,
    val mode: String = "quick",
)

data class TunnelRoute(
    val link: TunnelLink,
    val targetUrl: String,
)

/**
 * Pure parsing, validation and routing for the tunnel's local links. Kept free
 * of Android and Ktor so the rules can be unit tested. Only loopback targets
 * are accepted: the public listener must never become an open proxy into the
 * device's LAN or the internet.
 */
object TunnelLinks {
    const val SETTINGS_KEY = "tunnel_links"
    const val MAX_LINKS = 12
    const val MODE_QUICK = "quick"
    const val MODE_NAMED = "named"

    private val RESERVED = listOf(
        "/v1",
        "/models",
        "/chat",
        "/api/health",
        "/api/v1/chat/completions",
    )

    private val HOST_REGEX = Regex(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
    )

    fun decode(raw: String?): List<TunnelLink> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.lenient.decodeFromString<List<TunnelLink>>(raw)
                .filter { it.id.isNotBlank() && it.target.isNotBlank() }
                .map { it.copy(mode = normalizeMode(it.mode, it.host)) }
                .take(MAX_LINKS)
        }.getOrDefault(emptyList())
    }

    fun encode(links: List<TunnelLink>): String =
        Json.compact.encodeToString(links.take(MAX_LINKS))

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    /** Validate editor values and return a normalized link. */
    fun fromDraft(
        draft: TunnelLinkDraft,
        existingId: String? = null,
        blockedPorts: Set<Int> = emptySet(),
    ): TunnelLink {
        val mode = normalizeMode(draft.mode, draft.host)
        // Quick links own a generated hostname, so stored host/path values are
        // ignored (and not validated) in that mode.
        val host = if (mode == MODE_NAMED) normalizeHost(draft.host) else ""
        val path = if (mode == MODE_NAMED) normalizePath(draft.path) else ""
        if (mode == MODE_NAMED && host.isEmpty() && path.isEmpty()) {
            throw IllegalArgumentException(
                "Named links need a public path or a public hostname. " +
                    "Choose Quick tunnel to get a generated trycloudflare.com URL instead.",
            )
        }
        val target = normalizeTarget(draft.target)
            ?: throw IllegalArgumentException(
                "Local URL must be loopback http(s), for example http://127.0.0.1:8080.",
            )
        val port = targetPort(target)
            ?: throw IllegalArgumentException("Local URL has no usable port.")
        if (port in blockedPorts) {
            throw IllegalArgumentException(
                "Port $port belongs to the 9Router gateway. Choose the port your local app listens on.",
            )
        }
        return TunnelLink(
            id = existingId?.takeIf { it.isNotBlank() } ?: newId(),
            name = draft.name.trim().ifBlank {
                when {
                    host.isNotBlank() -> host
                    path.isNotBlank() -> path
                    else -> "Local link"
                }
            },
            host = host,
            path = path,
            target = target,
            stripPrefix = draft.stripPrefix,
            enabled = draft.enabled,
            mode = mode,
        )
    }

    /** Resolve an explicit or legacy link mode. */
    fun normalizeMode(raw: String, host: String = ""): String = when (raw.trim().lowercase()) {
        MODE_NAMED, "token" -> MODE_NAMED
        MODE_QUICK -> MODE_QUICK
        else -> if (host.isNotBlank()) MODE_NAMED else MODE_QUICK
    }

    /** Effective mode for a stored link, including links saved before modes existed. */
    fun modeOf(link: TunnelLink): String = normalizeMode(link.mode, link.host)

    fun isQuick(link: TunnelLink): Boolean = modeOf(link) == MODE_QUICK

    /** Normalize a public hostname. Blank means "match by path only". */
    fun normalizeHost(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val withoutScheme = value
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
        val host = if (withoutScheme.startsWith("[")) {
            withoutScheme.substringBefore(']').removePrefix("[")
        } else {
            withoutScheme.substringBefore(':')
        }.lowercase()
        if (!HOST_REGEX.matches(host)) {
            throw IllegalArgumentException("Public hostname must look like app.example.com.")
        }
        return host
    }

    /**
     * Normalize a public path prefix. Blank means "match by hostname only".
     * The gateway's own routes are reserved so a link cannot shadow the API.
     */
    fun normalizePath(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("/")) value = "/$value"
        value = value.trimEnd('/')
        if (value.isEmpty() || value == "/") {
            throw IllegalArgumentException("Use a path such as /site, or a hostname for the root page.")
        }
        if (value.length > 64) {
            throw IllegalArgumentException("Public path must be 64 characters or fewer.")
        }
        if (value.contains("//") || value.contains('\\') || value.contains('?') || value.contains('#')) {
            throw IllegalArgumentException("Public path cannot contain //, \\, ? or #.")
        }
        val segments = value.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw IllegalArgumentException("Public path cannot contain . or .. segments.")
        }
        if (segments.any { segment -> segment.any { it.isWhitespace() || it.code < 0x20 } }) {
            throw IllegalArgumentException("Public path cannot contain spaces or control characters.")
        }
        val lower = value.lowercase()
        if (isReserved(lower)) {
            throw IllegalArgumentException("That path is reserved by the 9Router gateway.")
        }
        return value
    }

    /**
     * Normalize a loopback target. Accepts `8080`-style values through
     * `host:port`, with an optional base path, and returns a canonical URL.
     */
    fun normalizeTarget(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val withScheme = when {
            value.all { it.isDigit() } -> "http://127.0.0.1:$value"
            value.contains("://") -> value
            else -> "http://$value"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        var host = uri.host?.lowercase() ?: return null
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        if (host !in setOf("localhost", "127.0.0.1", "::1")) return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        if (port !in 1..65535) return null
        val authority = if (host.contains(':')) "[$host]" else host
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val portPart = if (defaultPort) "" else ":$port"
        val basePath = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$authority$portPart$basePath"
    }

    fun targetPort(target: String): Int? {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        return when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", true) -> 443
            uri.scheme.equals("http", true) -> 80
            else -> null
        }
    }

    fun isReserved(path: String): Boolean {
        val value = path.lowercase().substringBefore('?')
        return RESERVED.any { value == it || value.startsWith("$it/") }
    }

    /** Strip the port and brackets from a Host header. */
    fun hostFromHeader(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return ""
        if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']')
        return value.substringBefore(':')
    }

    /** True when [path] is [prefix] or sits below it (`/blog` vs `/blogger`). */
    fun pathMatches(prefix: String, path: String): Boolean {
        if (prefix.isEmpty()) return false
        return path == prefix || path.startsWith("$prefix/")
    }

    /**
     * Pick the best link for a request. A quick link matched by its generated
     * hostname owns that whole hostname. Named links are matched by hostname,
     * then by the longest path prefix, and never shadow gateway routes.
     */
    fun match(
        links: List<TunnelLink>,
        hostHeader: String?,
        rawUri: String,
        quickHosts: Map<String, String> = emptyMap(),
    ): TunnelRoute? {
        val host = hostFromHeader(hostHeader)
        val path = rawUri.substringBefore('?').ifBlank { "/" }
        val enabled = links.filter { it.enabled }

        if (host.isNotBlank()) {
            val quickId = quickHosts[host]
            if (quickId != null) {
                val quick = enabled.firstOrNull { it.id == quickId && isQuick(it) }
                if (quick != null) {
                    // The dedicated quick hostname serves the link at its root;
                    // stored path/host values do not participate in routing.
                    val routed = quick.copy(path = "", stripPrefix = false)
                    return TunnelRoute(routed, forwardUrl(routed, rawUri))
                }
            }
        }

        val named = enabled.filter { !isQuick(it) }
        val byHost = named
            .filter { it.host.isNotBlank() && it.host == host }
            .filter { it.path.isBlank() || pathMatches(it.path, path) }
            .maxByOrNull { it.path.length }
        if (byHost != null) {
            return TunnelRoute(byHost, forwardUrl(byHost, rawUri))
        }

        if (isReserved(path)) return null
        val byPath = named
            .filter { it.host.isBlank() && it.path.isNotBlank() && pathMatches(it.path, path) }
            .maxByOrNull { it.path.length }
        return byPath?.let { TunnelRoute(it, forwardUrl(it, rawUri)) }
    }

    /**
     * Build the loopback URL a link request is forwarded to, preserving the
     * query string and the target's base path. When [TunnelLink.stripPrefix]
     * is set the public prefix is removed, which is what most sub-path hosts
     * expect.
     */
    fun forwardUrl(link: TunnelLink, rawUri: String): String {
        val queryIndex = rawUri.indexOf('?')
        val incomingPath = (if (queryIndex >= 0) rawUri.substring(0, queryIndex) else rawUri)
            .ifBlank { "/" }
        val query = if (queryIndex >= 0) rawUri.substring(queryIndex + 1) else ""
        val uri = runCatching { URI(link.target) }.getOrNull()
            ?: return link.target + incomingPath
        val origin = buildString {
            append(uri.scheme)
            append("://")
            append(uri.rawAuthority)
        }
        val basePath = (uri.rawPath ?: "").trimEnd('/')
        val forwarded = if (link.stripPrefix && link.path.isNotBlank() && pathMatches(link.path, incomingPath)) {
            incomingPath.removePrefix(link.path).ifBlank { "/" }
        } else {
            incomingPath
        }
        val fullPath = joinPaths(basePath, forwarded)
        return origin + fullPath + if (query.isNotEmpty()) "?$query" else ""
    }

    /**
     * Public URL shown for a named link on the shared tunnel. Quick links are
     * served from their own generated hostname, which the UI reads from
     * [LinkTunnelRuntime] instead.
     */
    fun publicUrl(root: String, mode: String, link: TunnelLink): String {
        if (isQuick(link)) return ""
        val suffix = if (link.path.isNotBlank()) "${link.path}/" else ""
        if (mode == "token" && link.host.isNotBlank()) {
            return "https://${link.host}$suffix"
        }
        val base = root.trimEnd('/')
        if (base.isBlank()) return ""
        if (link.path.isBlank()) return ""
        return base + suffix
    }

    /**
     * Rewrite a redirect from the local service so it works through the public
     * host: absolute loopback URLs lose the loopback origin, and path-only
     * redirects gain the mount prefix. External redirects are left alone.
     */
    fun publicLocation(location: String, link: TunnelLink, publicHost: String?): String {
        val value = location.trim()
        if (value.isEmpty() || value.startsWith("//")) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        val prefix = if (!isQuick(link) && link.stripPrefix && link.path.isNotBlank()) link.path else ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()

        if (uri.isAbsolute) {
            val host = uri.host?.lowercase() ?: return value
            if (host !in setOf("localhost", "127.0.0.1", "::1")) return value
            val public = publicHost?.trim()?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: return value
            val path = prefixPath(uri.rawPath.orEmpty().ifEmpty { "/" }, prefix)
            return "https://$public$path$query$fragment"
        }

        val path = uri.rawPath ?: return value
        if (path.isEmpty()) return value
        return prefixPath(path, prefix) + query + fragment
    }

    private fun prefixPath(path: String, prefix: String): String {
        if (prefix.isBlank()) return path
        if (pathMatches(prefix, path)) return path
        return prefix + (if (path.startsWith('/')) path else "/$path")
    }

    /** Lines describing the Zero Trust hostname -> local service mapping. */
    fun ingressSummary(links: List<TunnelLink>, publicPort: Int): String {
        val router = "http://127.0.0.1:$publicPort"
        return links.filter { it.enabled && !isQuick(it) && it.host.isNotBlank() }
            .joinToString("\n") { link ->
                "${link.host}  ->  $router${if (link.path.isNotBlank()) link.path else ""}"
            }
    }

    private fun joinPaths(base: String, path: String): String {
        val left = base.trimEnd('/')
        val right = if (path.startsWith('/')) path else "/$path"
        if (left.isEmpty()) return right
        if (right == "/") return "$left/"
        return left + right
    }
}
