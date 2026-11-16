package ai.ninerouter.core

import java.io.File

/**
 * Android's policy for executing files that live in app-writable storage.
 *
 * Android 10 (API 29) introduced W^X for apps targeting 29 or newer: a binary
 * can be read or executed, but not both. Files unpacked under `filesDir` are
 * writable, so `execve` is denied. Apps targeting 28 or lower, like Termux,
 * keep the legacy policy and can execute their own scripts and binaries.
 *
 * This app is sideloaded and targets 28, so the legacy branch applies. The
 * check still models both inputs so raising the target later cannot silently
 * assume execution works.
 */
object ExecPolicy {
    fun blocked(
        deviceSdk: Int,
        targetSdk: Int,
        path: String,
        writableDirs: List<File>,
    ): Boolean {
        if (deviceSdk < 29) return false
        if (targetSdk < 29) return false
        return writableDirs.any { dir ->
            path == dir.absolutePath || path.startsWith(dir.absolutePath + File.separator)
        }
    }
}
