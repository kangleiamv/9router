package ai.ninerouter.service.server

import ai.ninerouter.core.FtpHostSettings
import ai.ninerouter.core.FtpHostStore
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

/**
 * Embedded FTP server over a local folder.
 *
 * Apache FtpServer handles the protocol (passive mode, UTF-8, MLSD, resume) so
 * the app only owns configuration and lifecycle. The listener binds to
 * loopback; the tunnel exposes the server, never the LAN.
 */
internal object EmbeddedFtpServer {
    private var server: FtpServer? = null

    @Synchronized
    fun start(settings: FtpHostSettings, root: File) {
        stop()
        fun BaseUser.applyPermissions(readOnly: Boolean) {
            enabled = true
            maxIdleTime = 0
            // BaseUser refuses a login when no authority accepts the
            // concurrent-login request, so this one is always present. Zero
            // means "no extra limit"; the listener's connection config still
            // caps total sessions.
            authorities = buildList<Authority> {
                add(ConcurrentLoginPermission(0, 0))
                if (!readOnly) add(WritePermission())
            }
        }
        val legacy = BaseUser().apply {
            name = settings.username.ifBlank { UserManager.ANONYMOUS }
            password = settings.password
            homeDirectory = root.absolutePath
            applyPermissions(settings.readOnly)
        }
        // Additional accounts share the listener but get their own home
        // folder, so a site owner can be given FTP without the host root.
        val accounts = settings.accounts.map { account ->
            BaseUser().apply {
                name = account.username
                password = account.password
                homeDirectory = account.root.ifBlank { root.absolutePath }
                applyPermissions(account.readOnly)
            }
        }
        val users = if (accounts.isEmpty()) listOf(legacy) else listOf(legacy) + accounts
        val listener = ListenerFactory().apply {
            port = settings.port
            serverAddress = LOOPBACK
            idleTimeout = IDLE_SECONDS
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                setActiveEnabled(false)
                setPassiveAddress(LOOPBACK)
                setPassivePorts(FtpHostStore.PASSIVE_PORTS)
                setIdleTime(IDLE_SECONDS)
            }.createDataConnectionConfiguration()
        }.createListener()

        val factory = FtpServerFactory().apply {
            addListener("default", listener)
            userManager = DeviceUserManager(users)
        }
        val created = factory.createServer()
        try {
            created.start()
        } catch (t: Throwable) {
            runCatching { created.stop() }
            throw t
        }
        server = created
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.stop() }
    }

    @Synchronized
    fun isRunning(): Boolean = server?.isStopped == false

    private const val LOOPBACK = "127.0.0.1"
    private const val IDLE_SECONDS = 300

    /**
     * One configured account. Kept in memory so the plaintext password never
     * touches disk; settings encrypt it separately for the next start.
     */
    private class DeviceUserManager(private val users: List<BaseUser>) : UserManager {
        override fun getUserByName(name: String?): User? =
            users.firstOrNull { it.name.equals(name, ignoreCase = true) }

        override fun getAllUserNames(): Array<String> = users.map { it.name }.toTypedArray()

        override fun delete(name: String?) = Unit

        override fun save(user: User?) = Unit

        override fun doesExist(name: String?): Boolean =
            users.any { it.name.equals(name, ignoreCase = true) }

        override fun authenticate(authentication: Authentication): User {
            val name = when (authentication) {
                is UsernamePasswordAuthentication -> authentication.username
                is AnonymousAuthentication -> UserManager.ANONYMOUS
                else -> throw AuthenticationFailedException("Unsupported authentication")
            }
            val password = when (authentication) {
                is UsernamePasswordAuthentication -> authentication.password
                else -> ""
            }
            val user = getUserByName(name)
                ?: throw AuthenticationFailedException("Invalid FTP credentials")
            // The legacy account keeps its historical behaviour: a blank
            // configured password accepts any password (anonymous style).
            // Extra accounts are validated to always carry a password.
            val legacy = users.firstOrNull()
            val anonymousStyle = legacy === user && user.password.isEmpty()
            if (!anonymousStyle && user.password.isNotEmpty() && user.password != password) {
                throw AuthenticationFailedException("Invalid FTP credentials")
            }
            if (user.name == UserManager.ANONYMOUS && password.isNotEmpty()) {
                throw AuthenticationFailedException("Invalid FTP credentials")
            }
            return user
        }

        override fun getAdminName(): String = users.first().name

        override fun isAdmin(name: String?): Boolean = false
    }
}
