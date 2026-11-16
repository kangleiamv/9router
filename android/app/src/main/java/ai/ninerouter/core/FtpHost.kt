package ai.ninerouter.core

import ai.ninerouter.util.Crypto
import ai.ninerouter.util.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Optional FTP server hosted by the app itself.
 *
 * The server binds to loopback only; the Cloudflare Tunnel publishes it either
 * through the HTTP file browser or, for a named tunnel, through a separate
 * Zero Trust TCP hostname. [root] empty means the app's private external
 * folder, which needs no storage permission. Shared storage needs All files
 * access (or the legacy read/write permissions on older Android versions).
 */
/**
 * One FTP login. [root] is an absolute folder; blank means the host root.
 * [serviceId] links the account to a hosted site so the UI can show its owner.
 */
@Serializable
data class FtpAccount(
    val username: String = "",
    val password: String = "",
    val root: String = "",
    val readOnly: Boolean = false,
    val serviceId: String = "",
)

@Serializable
data class FtpHostSettings(
    val enabled: Boolean = false,
    val port: Int = 2121,
    val username: String = "android",
    val password: String = "",
    val root: String = "",
    val readOnly: Boolean = false,
    /** Additional logins served by the same listener. */
    val accounts: List<FtpAccount> = emptyList(),
)

enum class FtpHostStatus { STOPPED, STARTING, RUNNING, ERROR }

data class FtpHostState(
    val status: FtpHostStatus = FtpHostStatus.STOPPED,
    val port: Int = 0,
    val root: String = "",
    val username: String = "",
    val readOnly: Boolean = false,
    val message: String = "",
) {
    val running: Boolean get() = status == FtpHostStatus.RUNNING
}

object FtpHostRuntime {
    private val _state = MutableStateFlow(FtpHostState())
    val state: StateFlow<FtpHostState> = _state.asStateFlow()

    internal fun starting(settings: FtpHostSettings, root: String) {
        _state.value = FtpHostState(
            status = FtpHostStatus.STARTING,
            port = settings.port,
            root = root,
            username = settings.username.ifBlank { "anonymous" },
            readOnly = settings.readOnly,
            message = "Starting the FTP host…",
        )
    }

    internal fun running(settings: FtpHostSettings, root: String) {
        _state.value = FtpHostState(
            status = FtpHostStatus.RUNNING,
            port = settings.port,
            root = root,
            username = settings.username.ifBlank { "anonymous" },
            readOnly = settings.readOnly,
            message = "",
        )
    }

    internal fun failed(message: String) {
        _state.value = _state.value.copy(
            status = FtpHostStatus.ERROR,
            message = message,
        )
    }

    internal fun stopped() {
        _state.value = FtpHostState()
    }

    /** Loopback target the local links use for the hosted server. */
    fun target(port: Int): String = "ftp://127.0.0.1:$port"
}

object FtpHostStore {
    const val SETTINGS_KEY = "ftp_host"
    const val DEFAULT_PORT = 2121
    const val MIN_PORT = 1024
    const val PASSIVE_PORTS = "41000-41009"
    const val MAX_ACCOUNTS = 8

    /** Passive data ports, all bound to loopback by the embedded server. */
    val passivePorts: IntRange = 41000..41009

    fun isPassivePort(port: Int): Boolean = port in passivePorts

    fun newPassword(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(12) + "9r"

    val defaults: FtpHostSettings = FtpHostSettings()

    fun decode(raw: String?): FtpHostSettings {
        if (raw.isNullOrBlank()) return defaults
        return runCatching {
            val stored = Json.lenient.decodeFromString<FtpHostSettings>(raw)
            stored.copy(
                password = Crypto.decrypt(stored.password).orEmpty(),
                accounts = stored.accounts.map {
                    it.copy(password = Crypto.decrypt(it.password).orEmpty())
                },
            )
        }.getOrDefault(defaults)
    }

    fun encode(settings: FtpHostSettings): String {
        val safe = require(settings)
        return Json.compact.encodeToString(
            safe.copy(
                password = if (safe.password.isBlank()) {
                    ""
                } else {
                    Crypto.encrypt(safe.password) ?: safe.password
                },
                accounts = safe.accounts.map {
                    it.copy(
                        password = if (it.password.isBlank()) {
                            ""
                        } else {
                            Crypto.encrypt(it.password) ?: it.password
                        },
                    )
                },
            ),
        )
    }

    /** Validate and normalize editor values. Throws with a user-facing message. */
    fun require(settings: FtpHostSettings): FtpHostSettings {
        if (settings.port !in MIN_PORT..65535) {
            throw IllegalArgumentException("FTP host port must be between $MIN_PORT and 65535.")
        }
        if (isPassivePort(settings.port)) {
            throw IllegalArgumentException("FTP host port cannot fall in the passive range $PASSIVE_PORTS.")
        }
        val root = settings.root.trim()
        if (root.isNotEmpty()) {
            val absolute = runCatching { File(root).isAbsolute }.getOrDefault(false)
            if (!absolute) {
                throw IllegalArgumentException("FTP host folder must be an absolute path.")
            }
            if (root.contains("..")) {
                throw IllegalArgumentException("FTP host folder cannot contain '..'.")
            }
        }
        val username = settings.username.trim()
        if (username.isEmpty() && settings.password.isNotEmpty()) {
            throw IllegalArgumentException("Add the FTP username that owns this password.")
        }
        if (username.any { it.code < 0x20 } || settings.password.any { it.code < 0x20 }) {
            throw IllegalArgumentException("FTP credentials cannot contain control characters.")
        }
        val accounts = settings.accounts.map { account ->
            val name = account.username.trim()
            if (name.isEmpty()) throw IllegalArgumentException("FTP accounts need a username.")
            if (!name.matches(Regex("[A-Za-z0-9_.@-]{1,32}"))) {
                throw IllegalArgumentException("FTP usernames allow letters, digits, _ . @ and -.")
            }
            if (account.password.isEmpty() && !name.equals("anonymous", true)) {
                throw IllegalArgumentException("Set a password for FTP account '$name'.")
            }
            if (account.password.any { it.code < 0x20 }) {
                throw IllegalArgumentException("FTP passwords cannot contain control characters.")
            }
            val home = account.root.trim()
            if (home.isNotEmpty()) {
                val absolute = runCatching { File(home).isAbsolute }.getOrDefault(false)
                if (!absolute || home.contains("..")) {
                    throw IllegalArgumentException(
                        "FTP account '$name' needs an absolute folder without '..'.",
                    )
                }
            }
            account.copy(username = name, root = home)
        }
        val duplicate = accounts.map { it.username.lowercase() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicate.isNotEmpty()) {
            throw IllegalArgumentException("FTP username '${duplicate.first()}' is used twice.")
        }
        if (accounts.size > MAX_ACCOUNTS) {
            throw IllegalArgumentException("At most $MAX_ACCOUNTS FTP accounts are supported.")
        }
        return settings.copy(
            accounts = accounts,
            username = username,
            password = settings.password,
            root = root,
        )
    }

    /** True when [path] needs All files access rather than the app sandbox. */
    fun needsBroadAccess(path: String): Boolean {
        val value = path.trim()
        if (value.isEmpty()) return false
        return value.startsWith("/storage/") || value.startsWith("/mnt/media_rw/")
    }
}
