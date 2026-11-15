package ai.ninerouter.service.server

import ai.ninerouter.core.TunnelLink
import ai.ninerouter.core.TunnelLinks
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.http.encodeURLParameter
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Browser view of a local FTP server, published through the HTTP tunnel.
 *
 * Cloudflare Quick Tunnels only carry HTTP and cloudflared rejects `ftp://`
 * origins, so an FTP link cannot be a native FTP endpoint in Quick mode. The
 * public listener therefore speaks HTTP to the browser and FTP to the local
 * server. Named links can additionally expose a native TCP route, configured
 * separately in Zero Trust.
 *
 * Every request needs the link's random access key. Without it the page and its
 * upload form would be open to anyone who learns the tunnel URL.
 */
internal object FtpBrowser {
    private const val MAX_UPLOAD_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRIES = 2_000
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val DATA_TIMEOUT_MS = 60_000

    suspend fun serve(call: ApplicationCall, link: TunnelLink) {
        val key = call.request.queryParameters["k"].orEmpty()
        if (!keyMatches(link, key)) {
            call.respondText(
                "This FTP link needs its access key. Open the URL shown in 9Router.",
                status = HttpStatusCode.Unauthorized,
            )
            return
        }
        val dir = TunnelLinks.cleanRelativePath(call.request.queryParameters["dir"].orEmpty())
        if (dir == null) {
            call.respondText("Invalid folder path.", status = HttpStatusCode.BadRequest)
            return
        }
        try {
            when {
                call.request.httpMethod == HttpMethod.Get &&
                    call.request.queryParameters.contains("download") ->
                    download(call, link, call.request.queryParameters["download"].orEmpty())

                call.request.httpMethod == HttpMethod.Post -> upload(call, link, dir)
                call.request.httpMethod == HttpMethod.Get -> list(call, link, dir)
                else -> call.respondText("Method not allowed.", status = HttpStatusCode.MethodNotAllowed)
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (!call.response.isCommitted) {
                call.respondText(
                    "FTP error: ${t.message ?: t.javaClass.simpleName}",
                    status = HttpStatusCode.BadGateway,
                )
            }
        }
    }

    private fun keyMatches(link: TunnelLink, provided: String): Boolean {
        if (link.key.isBlank()) return false
        return MessageDigest.isEqual(
            link.key.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun list(call: ApplicationCall, link: TunnelLink, dir: String) {
        val base = TunnelLinks.ftpBasePath(link.target)
        val absolute = TunnelLinks.absoluteFtpPath(base, dir) ?: error("Invalid folder")
        val files = withContext(Dispatchers.IO) {
            withFtp(link) { ftp ->
                (ftp.listFiles(absolute) ?: emptyArray())
                    .filter { it.name != "." && it.name != ".." }
                    .sortedWith(compareByDescending<FTPFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                    .take(MAX_ENTRIES)
            }
        }
        headers(call)
        call.respondText(
            page(link, call.request.path(), dir, files),
            ContentType.Text.Html.withCharset(Charsets.UTF_8),
            HttpStatusCode.OK,
        )
    }

    private suspend fun download(
        call: ApplicationCall,
        link: TunnelLink,
        requested: String,
    ) {
        val relative = TunnelLinks.cleanRelativePath(requested)
            ?: return call.respondText("Invalid file path.", status = HttpStatusCode.BadRequest)
        if (relative == "/") {
            call.respondText("Choose a file.", status = HttpStatusCode.BadRequest)
            return
        }
        val base = TunnelLinks.ftpBasePath(link.target)
        val absolute = TunnelLinks.absoluteFtpPath(base, relative)
            ?: return call.respondText("Invalid file path.", status = HttpStatusCode.BadRequest)
        val name = headerName(relative.substringAfterLast('/').ifBlank { "download" })
        withContext(Dispatchers.IO) {
            withFtp(link) { ftp ->
                val size = ftp.listFiles(absolute)?.firstOrNull { !it.isDirectory }?.size ?: -1L
                val stream = ftp.retrieveFileStream(absolute)
                    ?: error("FTP server could not open $relative")
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        name,
                    ).toString(),
                )
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondOutputStream(
                    contentType = ContentType.Application.OctetStream,
                    status = HttpStatusCode.OK,
                    contentLength = size.takeIf { it >= 0 },
                ) {
                    stream.use { input -> input.copyTo(this) }
                    flush()
                }
                if (!ftp.completePendingCommand()) {
                    error("FTP server did not finish the download")
                }
            }
        }
    }

    private suspend fun upload(call: ApplicationCall, link: TunnelLink, dir: String) {
        val base = TunnelLinks.ftpBasePath(link.target)
        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                val name = safeName(part.originalFileName)
                if (name != null) {
                    val temp = withContext(Dispatchers.IO) {
                        File.createTempFile("9r-ftp-", ".tmp")
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            part.streamProvider().use { input ->
                                temp.outputStream().use { output ->
                                    if (!copyLimited(input, output, MAX_UPLOAD_BYTES)) {
                                        error("Upload exceeds the 64 MB limit")
                                    }
                                }
                            }
                            val remote = TunnelLinks.absoluteFtpPath(base, merge(dir, name))
                                ?: error("Invalid upload path")
                            withFtp(link) { ftp ->
                                temp.inputStream().use { input ->
                                    if (!ftp.storeFile(remote, input)) {
                                        error("FTP server rejected $name")
                                    }
                                }
                            }
                        }
                    } finally {
                        withContext(Dispatchers.IO) { temp.delete() }
                    }
                }
            }
            part.dispose()
        }
        val target = call.request.path().trimEnd('/') + "/" +
            "?k=${link.key.encodeURLParameter()}&dir=${dir.encodeURLParameter()}"
        call.respondRedirect(target, permanent = false)
    }

    private fun page(link: TunnelLink, mount: String, dir: String, files: List<FTPFile>): String {
        val q = "?k=${link.key.encodeURLParameter()}&dir="
        val root = "<a href=\"${q}${"/".encodeURLParameter()}\">/</a>"
        val crumbs = StringBuilder(root)
        var walk = ""
        dir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            walk += "/$segment"
            crumbs.append(" / <a href=\"$q${walk.encodeURLParameter()}\">${esc(segment)}</a>")
        }

        val rows = buildString {
            if (dir != "/") {
                val parent = dir.substringBeforeLast('/', "")
                    .ifBlank { "/" }
                append(
                    "<tr><td><a href=\"$q${parent.encodeURLParameter()}\">..</a></td>" +
                        "<td></td><td></td></tr>",
                )
            }
            if (files.isEmpty()) {
                append("<tr><td colspan=\"3\" class=\"muted\">This folder is empty.</td></tr>")
            }
            files.forEach { file ->
                val relative = merge(dir, file.name)
                val encoded = relative.encodeURLParameter()
                if (file.isDirectory) {
                    append(
                        "<tr><td><a href=\"$q$encoded\">${esc(file.name)}/</a></td>" +
                            "<td class=\"muted\">folder</td><td class=\"muted\"></td></tr>",
                    )
                } else {
                    val href = "?k=${link.key.encodeURLParameter()}&download=$encoded"
                    append(
                        "<tr><td><a href=\"$href\">${esc(file.name)}</a></td>" +
                            "<td class=\"muted\">${humanSize(file.size)}</td>" +
                            "<td class=\"muted\">${modified(file)}</td></tr>",
                    )
                }
            }
        }

        val action = esc(mount.ifBlank { "/" }) +
            "?k=" + link.key.encodeURLParameter() +
            "&dir=" + dir.encodeURLParameter()
        return """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(link.name)} · 9Router FTP</title>
</head>
<body style="margin:0;background:#0d0f12;color:#e8eaed;font:15px/1.45 -apple-system,Segoe UI,Roboto,sans-serif">
<main style="max-width:920px;margin:0 auto;padding:22px 18px 48px">
  <div style="display:flex;align-items:baseline;gap:10px;flex-wrap:wrap">
    <h1 style="font-size:20px;margin:0">${esc(link.name)}</h1>
    <span style="font-size:12px;color:#8b949e">FTP via 9Router Cloudflare Tunnel</span>
  </div>
  <p style="font-size:13px;color:#8b949e;margin:6px 0 16px">${crumbs}</p>
  <table style="width:100%;border-collapse:collapse">
    <thead><tr style="text-align:left;color:#8b949e;font-size:12px">
      <th style="padding:6px 4px;border-bottom:1px solid #232830">Name</th>
      <th style="padding:6px 4px;border-bottom:1px solid #232830">Size</th>
      <th style="padding:6px 4px;border-bottom:1px solid #232830">Modified</th>
    </tr></thead>
    <tbody>$rows</tbody>
  </table>
  <h2 style="font-size:15px;margin:26px 0 8px">Upload</h2>
  <form method="post" enctype="multipart/form-data" action="$action"
        style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
    <input type="file" name="file" multiple required
           style="color:#e8eaed;background:#161a20;border:1px solid #232830;border-radius:8px;padding:8px">
    <button type="submit"
            style="background:#4c8dff;color:#fff;border:0;border-radius:8px;padding:9px 14px;font-weight:600">
      Upload
    </button>
    <span style="font-size:12px;color:#8b949e">Max 64 MB per file</span>
  </form>
  <p style="font-size:12px;color:#8b949e;margin-top:22px">
    Anyone with this URL and key can browse and change files. Share it carefully.
  </p>
</main>
</body>
</html>
""".trimIndent()
    }

    private fun headers(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'",
        )
    }

    private inline fun <T> withFtp(link: TunnelLink, block: (FTPClient) -> T): T {
        val uri = URI(link.target)
        val host = uri.host?.removeSurrounding("[", "]") ?: error("Invalid FTP host")
        val port = if (uri.port != -1) uri.port else 21
        val client = FTPClient().apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            defaultTimeout = CONNECT_TIMEOUT_MS
            dataTimeout = Duration.ofMillis(DATA_TIMEOUT_MS.toLong())
            controlEncoding = "UTF-8"
        }
        try {
            client.connect(InetAddress.getByName(host), port)
            val user = link.username.ifBlank { "anonymous" }
            val password = link.password.ifBlank { if (link.username.isBlank()) "anonymous@" else "" }
            if (!client.login(user, password)) {
                error("FTP login failed for $user")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            return block(client)
        } finally {
            runCatching { if (client.isConnected) client.logout() }
            runCatching { if (client.isConnected) client.disconnect() }
        }
    }

    private fun merge(dir: String, name: String): String {
        val cleanName = name.trim('/')
        val cleanDir = dir.trimEnd('/')
        if (cleanName.isEmpty()) return cleanDir.ifBlank { "/" }
        return if (cleanDir.isEmpty() || cleanDir == "/") "/$cleanName" else "$cleanDir/$cleanName"
    }

    /** Keep download names inside ASCII so the header stays valid. */
    private fun headerName(value: String): String = value
        .map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }
        .joinToString("")
        .ifBlank { "download" }

    private fun safeName(raw: String?): String? {
        val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        if (name.isEmpty() || name == "." || name == ".." || name == "/") return null
        if (name.any { it == '\\' || it.code < 0x20 }) return null
        return name
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > limit) return false
            output.write(buffer, 0, read)
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun modified(file: FTPFile): String {
        val date = file.timestamp?.time ?: return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
