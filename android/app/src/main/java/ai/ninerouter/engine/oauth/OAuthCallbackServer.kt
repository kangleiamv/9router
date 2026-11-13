package ai.ninerouter.engine.oauth

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

class OAuthException(message: String) : Exception(message)

/**
 * Tiny one-shot HTTP server used as an OAuth loopback redirect target.
 *
 * The socket is bound to a random port and only loopback clients are served;
 * every other connection is dropped. `awaitCode` returns the query parameters
 * of the `/callback` request after validating `state`.
 */
class OAuthCallbackServer(
    private val timeoutMs: Long = 5 * 60 * 1000L
) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val closed = AtomicBoolean(false)

    val port: Int = socket.localPort
    val redirectUri: String = "http://localhost:$port/callback"

    fun awaitCode(expectedState: String): Map<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        socket.soTimeout = 5_000
        try {
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                val client = try {
                    socket.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                try {
                    if (!client.inetAddress.isLoopbackAddress) continue
                    val params = handle(client, expectedState)
                    if (params != null) return params
                } finally {
                    runCatching { client.close() }
                }
            }
        } finally {
            close()
        }
        throw OAuthException("Sign-in timed out. Close the browser tab and try again.")
    }

    /** Handles one request; returns params for a valid callback, otherwise null. */
    private fun handle(client: Socket, expectedState: String): Map<String, String>? {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") {
            respond(client.getOutputStream(), 405, page("Method not allowed", false))
            return null
        }
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        if (path != "/callback" && path != "/auth/callback") {
            respond(client.getOutputStream(), 404, page("Not found", false))
            return null
        }
        val params = parseQuery(target.substringAfter('?', ""))
        if (expectedState.isNotEmpty() && params["state"] != expectedState) {
            respond(client.getOutputStream(), 400, page("Sign-in state did not match. Please try again.", false))
            return null
        }
        val error = params["error"]
        if (!error.isNullOrBlank()) {
            val description = params["error_description"]?.replace('+', ' ') ?: error
            respond(client.getOutputStream(), 400, page("Sign-in failed: $description", false))
            return params
        }
        if (params["code"].isNullOrBlank()) {
            respond(client.getOutputStream(), 400, page("No authorization code received.", false))
            return null
        }
        respond(client.getOutputStream(), 200, page("9Router is connected. You can close this tab and return to the app.", true))
        return params
    }

    private fun respond(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun page(message: String, success: Boolean): String {
        val title = if (success) "Connected" else "Sign-in problem"
        val color = if (success) "#22c55e" else "#f43f5e"
        val symbol = if (success) "&#10003;" else "!"
        return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
body{font-family:-apple-system,system-ui,sans-serif;background:#0b0b0f;color:#e5e7eb;display:flex;
align-items:center;justify-content:center;min-height:100vh;margin:0;padding:24px}
.card{max-width:420px;text-align:center;background:#16161d;border:1px solid #2a2a35;
border-radius:18px;padding:32px 26px}
.mark{width:64px;height:64px;border-radius:50%;background:${color}22;color:$color;
display:flex;align-items:center;justify-content:center;font-size:34px;margin:0 auto 16px}
h1{font-size:20px;margin:0 0 10px}p{color:#9ca3af;line-height:1.5;margin:0}
.brand{margin-top:18px;font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:#6b7280}
</style></head>
<body><div class="card"><div class="mark">$symbol</div><h1>$title</h1><p>$message</p>
<div class="brand">9Router for Android</div></div></body></html>"""
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length < 8192) {
            val value = input.read()
            if (value == -1) return if (buffer.isEmpty()) null else buffer.toString()
            val char = value.toChar()
            if (char == '\n') return buffer.toString().removeSuffix("\r")
            buffer.append(char)
        }
        return buffer.toString()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index < 0) return@mapNotNull null
            val key = decode(pair.substring(0, index))
            val value = decode(pair.substring(index + 1))
            key to value
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}
