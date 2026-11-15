package ai.ninerouter.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Shared-storage access for the optional on-device FTP host.
 *
 * The app-specific external folder works without any permission. Serving the
 * whole shared storage (Downloads, DCIM, documents) needs All files access on
 * Android 11+ or the legacy read/write permissions on older releases.
 */
object StorageAccess {
    fun isSharedStorage(path: String): Boolean {
        if (path.isBlank()) return false
        val shared = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault("/storage/emulated/0")
        val value = File(path).absolutePath
        return value == shared || value.startsWith("$shared/") || value.startsWith("/storage/")
    }

    fun hasBroadAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Settings intent for All files access, or null when unsupported. */
    fun allFilesIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /** Legacy runtime permissions needed below Android 11. */
    fun legacyPermissions(): Array<String> = if (Build.VERSION.SDK_INT < 30) {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    } else {
        emptyArray()
    }
}
