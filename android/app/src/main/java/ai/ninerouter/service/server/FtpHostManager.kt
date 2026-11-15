package ai.ninerouter.service.server

import ai.ninerouter.core.FtpHostRuntime
import ai.ninerouter.core.FtpHostSettings
import ai.ninerouter.core.FtpHostStore
import ai.ninerouter.util.StorageAccess
import android.content.Context
import java.io.File

/**
 * Applies the on-device FTP host settings to the embedded server.
 *
 * Called from the proxy foreground service. Blocking work happens on the
 * caller's IO dispatcher; the UI only observes [FtpHostRuntime.state].
 */
object FtpHostManager {
    @Synchronized
    fun apply(context: Context, settings: FtpHostSettings) {
        EmbeddedFtpServer.stop()
        FtpHostRuntime.stopped()
        if (!settings.enabled) return

        val root = resolveRoot(context, settings)
        FtpHostRuntime.starting(settings, root.absolutePath)
        try {
            prepare(context, root, settings)
            EmbeddedFtpServer.start(settings, root)
            FtpHostRuntime.running(settings, root.absolutePath)
        } catch (t: Throwable) {
            EmbeddedFtpServer.stop()
            FtpHostRuntime.failed(t.message ?: "Could not start the FTP host")
        }
    }

    @Synchronized
    fun stop() {
        EmbeddedFtpServer.stop()
        FtpHostRuntime.stopped()
    }

    fun isRunning(): Boolean = EmbeddedFtpServer.isRunning()

    /** Default root: the app's private external folder, no permission needed. */
    fun defaultRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
        return File(base, "ftp")
    }

    private fun resolveRoot(context: Context, settings: FtpHostSettings): File =
        if (settings.root.isBlank()) defaultRoot(context) else File(settings.root.trim())

    private fun isAppOwned(context: Context, root: File): Boolean {
        val base = context.getExternalFilesDir(null)?.absolutePath ?: return false
        val path = root.absolutePath
        return path == base || path.startsWith("$base/")
    }

    private fun prepare(context: Context, root: File, settings: FtpHostSettings) {
        // The app's own external folder lives under /storage too, but it is
        // sandboxed and needs no All files access.
        val shared = !isAppOwned(context, root) && FtpHostStore.needsBroadAccess(root.absolutePath)
        if (shared && !StorageAccess.hasBroadAccess(context)) {
            throw IllegalStateException(
                "Grant All files access before serving shared storage, or use the app folder.",
            )
        }
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            throw IllegalStateException("Cannot create the FTP folder at ${root.absolutePath}")
        }
        if (!root.isDirectory) {
            throw IllegalStateException("FTP folder is not a directory: ${root.absolutePath}")
        }
        if (!root.canRead()) {
            throw IllegalStateException("FTP folder is not readable: ${root.absolutePath}")
        }
        if (!settings.readOnly) {
            if (!root.canWrite()) {
                throw IllegalStateException(
                    "FTP folder is not writable; enable read-only or pick another folder.",
                )
            }
            val probe = File(root, ".9router-write-test")
            val ok = runCatching {
                probe.writeText("ok")
                probe.delete()
            }.getOrDefault(false)
            if (!ok) {
                throw IllegalStateException(
                    "FTP folder is not writable; enable read-only or pick another folder.",
                )
            }
        }
    }
}
