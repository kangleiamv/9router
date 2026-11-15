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
import io.ktor.server.request.uri
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

    private const val STYLE = """
:root{
  color-scheme:dark light;
  --bg:#0b0d11;--panel:#12161c;--panel-2:#171c24;--hover:#1a202a;
  --text:#e9edf3;--muted:#98a2b3;--border:#242b35;
  --accent:#6ea8ff;--accent-strong:#8fbaff;--accent-soft:rgba(110,168,255,.14);
  --on-accent:#08111f;
  --shadow:0 10px 30px rgba(0,0,0,.35);--radius:14px
}
@media (prefers-color-scheme:light){
  :root{
    --bg:#f5f7fa;--panel:#fff;--panel-2:#f8fafc;--hover:#eef4ff;
    --text:#111827;--muted:#5b6472;--border:#e2e7ee;
    --accent:#2563eb;--accent-strong:#1d4ed8;--accent-soft:rgba(37,99,235,.08);
    --on-accent:#fff;
    --shadow:0 10px 30px rgba(16,24,40,.08)
  }
}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text)}
body{font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-text-size-adjust:100%}
a{color:var(--accent);text-decoration:none}
a:hover{color:var(--accent-strong);text-decoration:underline}
code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;background:var(--panel-2);border:1px solid var(--border);border-radius:6px;padding:1px 5px;overflow-wrap:anywhere}
.wrap{max-width:920px;margin:0 auto;padding:0 18px}
header.top{border-bottom:1px solid var(--border);background:linear-gradient(180deg,var(--panel),transparent)}
.brand{display:flex;align-items:center;gap:8px;padding-top:18px;font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.dot{width:8px;height:8px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 4px var(--accent-soft)}
.sep{opacity:.5}
h1{font-size:clamp(20px,4vw,26px);line-height:1.2;margin:10px 0 4px;overflow-wrap:anywhere}
.sub{color:var(--muted);font-size:13px;margin:0 0 16px}
main{padding:18px 0 34px}
.crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:0 0 14px;font-size:13px;color:var(--muted)}
.crumbs a{font-weight:600}
.panel{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);box-shadow:var(--shadow);overflow:hidden}
.panel + .panel{margin-top:16px}
.panel-head{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:13px 16px;border-bottom:1px solid var(--border)}
.panel-head h2{margin:0;font-size:14px}
.count{font-size:12px;color:var(--muted);white-space:nowrap}
ul.files{list-style:none;margin:0;padding:6px}
.row{display:grid;grid-template-columns:38px minmax(0,1fr) auto;gap:12px;align-items:center;min-height:52px;padding:8px 10px;border-radius:10px;color:var(--text)}
.row:hover{background:var(--hover);text-decoration:none}
.badge{display:flex;align-items:center;justify-content:center;width:38px;height:38px;border-radius:10px;background:var(--panel-2);border:1px solid var(--border);color:var(--muted);font-size:10px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;overflow:hidden}
.badge.dir{background:var(--accent-soft);border-color:transparent;color:var(--accent)}
.name{min-width:0;overflow-wrap:anywhere}
.dir-name{font-weight:600}
.slash{color:var(--muted);font-weight:400}
.meta{display:flex;flex-direction:column;align-items:flex-end;gap:2px;font-size:12px;color:var(--muted);white-space:nowrap}
.empty{padding:30px 16px;text-align:center;color:var(--muted);font-size:14px}
.empty span{display:block;margin-top:4px;font-size:12px;opacity:.8}
.upload .panel-head{border-bottom:0;padding-bottom:0}
.upload-body{padding:2px 16px 16px}
.where{color:var(--muted);font-size:12px;margin:0 0 12px;overflow-wrap:anywhere}
form{display:flex;flex-direction:column;gap:10px}
input[type=file]{width:100%;padding:12px;border:1px dashed var(--border);border-radius:10px;background:var(--panel-2);color:var(--text);font:inherit}
input[type=file]::file-selector-button{margin-right:12px;padding:8px 12px;border:1px solid var(--border);border-radius:8px;background:var(--panel);color:var(--text);font:inherit;font-weight:600;cursor:pointer}
button{min-height:44px;padding:0 18px;border:0;border-radius:10px;background:var(--accent);color:var(--on-accent);font:inherit;font-weight:700;cursor:pointer;transition:background .15s ease}
button:hover{background:var(--accent-strong)}
@media (hover:hover){.row:hover .name{text-decoration:underline}}
button:focus-visible,input:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.note{margin-top:16px;padding:12px 14px;border:1px solid var(--border);border-radius:12px;background:var(--panel-2);color:var(--muted);font-size:12px}
.note strong{color:var(--text)}
.message{padding:22px}
.message p{margin:0;color:var(--muted);overflow-wrap:anywhere}
footer{padding:0 0 28px;color:var(--muted);font-size:12px}
@media (max-width:560px){
  .wrap{padding:0 14px}
  .row{grid-template-columns:34px minmax(0,1fr);gap:10px;padding:10px}
  .badge{width:34px;height:34px}
  .meta{grid-column:2;flex-direction:row;align-items:center;gap:8px}
}
"""

    suspend fun serve(call: ApplicationCall, link: TunnelLink) {
        headers(call)
        val key = call.request.queryParameters["k"].orEmpty()
        if (!keyMatches(link, key)) {
            call.respondHtml(
                messagePage(
                    "Access key required",
                    "This FTP link needs its access key. Open the URL shown in 9Router.",
                ),
                HttpStatusCode.Unauthorized,
            )
            return
        }
        val dir = TunnelLinks.cleanRelativePath(call.request.queryParameters["dir"].orEmpty())
        if (dir == null) {
            call.respondHtml(
                messagePage("Invalid folder", "That folder path is not allowed."),
                HttpStatusCode.BadRequest,
            )
            return
        }
        try {
            when {
                call.request.httpMethod == HttpMethod.Get &&
                    call.request.queryParameters.contains("download") ->
                    download(call, link, call.request.queryParameters["download"].orEmpty())

                call.request.httpMethod == HttpMethod.Post -> upload(call, link, dir)
                call.request.httpMethod == HttpMethod.Get -> list(call, link, dir)
                else -> call.respondHtml(
                    messagePage("Method not allowed", "This link accepts GET and POST requests only."),
                    HttpStatusCode.MethodNotAllowed,
                )
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (!call.response.isCommitted) {
                call.respondHtml(
                    messagePage(
                        "FTP error",
                        t.message ?: t.javaClass.simpleName,
                    ),
                    HttpStatusCode.BadGateway,
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
        call.respondHtml(
            page(link, call.request.uri.substringBefore('?'), dir, files),
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
        val mount = call.request.uri.substringBefore('?').trimEnd('/')
        val target = mount + "/" +
            "?k=${link.key.encodeURLParameter()}&dir=${dir.encodeURLParameter()}"
        call.respondRedirect(target, permanent = false)
    }

    private fun page(link: TunnelLink, mount: String, dir: String, files: List<FTPFile>): String {
        val key = link.key.encodeURLParameter()
        val q = "?k=$key&amp;dir="
        val crumbs = StringBuilder(
            "<a href=\"${q}${"/".encodeURLParameter()}\">Root</a>",
        )
        var walk = ""
        dir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            walk += "/$segment"
            crumbs.append("<span aria-hidden=\"true\">/</span>")
            crumbs.append("<a href=\"$q${walk.encodeURLParameter()}\">${esc(segment)}</a>")
        }

        val rows = buildString {
            if (dir != "/") {
                val parent = dir.substringBeforeLast('/', "").ifBlank { "/" }
                append(
                    "<li><a class=\"row\" href=\"$q${parent.encodeURLParameter()}\">" +
                        "<span class=\"badge dir\" aria-hidden=\"true\">↑</span>" +
                        "<span class=\"name dir-name\">Parent folder</span>" +
                        "<span class=\"meta\"></span></a></li>",
                )
            }
            files.forEach { file ->
                val relative = merge(dir, file.name)
                val encoded = relative.encodeURLParameter()
                if (file.isDirectory) {
                    append(
                        "<li><a class=\"row\" href=\"$q$encoded\">" +
                            "<span class=\"badge dir\" aria-hidden=\"true\">DIR</span>" +
                            "<span class=\"name dir-name\">${esc(file.name)}<span class=\"slash\">/</span></span>" +
                            "<span class=\"meta\"><span>Folder</span></span></a></li>",
                    )
                } else {
                    val href = "?k=$key&amp;download=$encoded"
                    append(
                        "<li><a class=\"row\" href=\"$href\">" +
                            "<span class=\"badge\" aria-hidden=\"true\">${esc(badgeName(file.name))}</span>" +
                            "<span class=\"name\">${esc(file.name)}</span>" +
                            "<span class=\"meta\"><span>${humanSize(file.size)}</span>" +
                            "<span>${modified(file)}</span></span></a></li>",
                    )
                }
            }
            if (files.isEmpty()) {
                append(
                    "<li class=\"empty\">This folder is empty." +
                        "<span>Upload a file below to get started.</span></li>",
                )
            }
        }

        val count = files.size
        val countLabel = "$count " + if (count == 1) "item" else "items"
        val action = esc(mount.ifBlank { "/" }) +
            "?k=" + key +
            "&amp;dir=" + dir.encodeURLParameter()
        val content = """
<header class="top">
  <div class="wrap">
    <div class="brand"><span class="dot" aria-hidden="true"></span>9Router<span class="sep">/</span>FTP</div>
    <h1>${esc(link.name)}</h1>
    <p class="sub">Cloudflare Tunnel · browser file view</p>
  </div>
</header>
<main class="wrap">
  <nav class="crumbs" aria-label="Breadcrumb">$crumbs</nav>
  <section class="panel" aria-label="Files">
    <div class="panel-head">
      <h2>Files</h2>
      <span class="count">$countLabel</span>
    </div>
    <ul class="files">$rows</ul>
  </section>
  <section class="panel upload">
    <div class="panel-head"><h2>Upload</h2><span class="count">64 MB max</span></div>
    <div class="upload-body">
      <p class="where">Files are written to <code>${esc(dir)}</code></p>
      <form method="post" enctype="multipart/form-data" action="$action">
        <input type="file" name="file" multiple required>
        <button type="submit">Upload files</button>
      </form>
    </div>
  </section>
  <p class="note"><strong>Private link.</strong> Anyone with this URL and access key can browse and change files. Share it carefully.</p>
</main>
<footer class="wrap">Served by 9Router · FTP browser</footer>
"""
        return shell("${link.name} · 9Router FTP", content)
    }

    private fun messagePage(title: String, message: String): String = shell(
        "$title · 9Router FTP",
        """
<header class="top">
  <div class="wrap">
    <div class="brand"><span class="dot" aria-hidden="true"></span>9Router<span class="sep">/</span>FTP</div>
    <h1>${esc(title)}</h1>
  </div>
</header>
<main class="wrap">
  <section class="panel message">
    <p>${esc(message)}</p>
  </section>
</main>
<footer class="wrap">Served by 9Router · FTP browser</footer>
""",
    )

    private fun shell(title: String, content: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="dark light">
<meta name="robots" content="noindex, nofollow">
<title>${esc(title)}</title>
<style>$STYLE</style>
</head>
<body>
$content</body>
</html>
""".trimIndent()

    /** Extension badge shown in file rows; folders use `DIR` instead. */
    private fun badgeName(name: String): String {
        val ext = name.substringAfterLast('.', "").trim()
        if (ext.isEmpty() || ext.length > 3 || ext.any { !it.isLetterOrDigit() }) return "FILE"
        return ext
    }

    private suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode) {
        respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
    }

    private fun headers(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Referrer-Policy", "no-referrer")
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
