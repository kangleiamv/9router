package ai.ninerouter.ui

import ai.ninerouter.core.AppContainer
import ai.ninerouter.core.CompatibleProbe
import ai.ninerouter.core.DashboardStats
import ai.ninerouter.core.FtpHostRuntime
import ai.ninerouter.core.FtpHostSettings
import ai.ninerouter.core.FtpHostState
import ai.ninerouter.core.FtpHostStore
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostedKinds
import ai.ninerouter.core.HostedService
import ai.ninerouter.core.HostingRuntime
import ai.ninerouter.core.HostingStore
import ai.ninerouter.core.LinkTunnelRuntime
import ai.ninerouter.core.LocalServices
import ai.ninerouter.core.SiteDatabases
import ai.ninerouter.core.ProxyRuntime
import ai.ninerouter.core.ProviderUsage
import ai.ninerouter.core.TunnelLink
import ai.ninerouter.core.TunnelLinkDraft
import ai.ninerouter.core.TunnelLinks
import ai.ninerouter.core.TunnelPorts
import ai.ninerouter.core.TunnelRuntime
import ai.ninerouter.core.TunnelState
import ai.ninerouter.data.model.ApiKey
import ai.ninerouter.data.model.Combo
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderQuota
import ai.ninerouter.engine.ProviderQuotaClient
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.oauth.OAuthClient
import ai.ninerouter.engine.oauth.OAuthException
import ai.ninerouter.engine.oauth.OAuthProviders
import ai.ninerouter.engine.specificData
import ai.ninerouter.service.ProxyForegroundService
import ai.ninerouter.service.server.HostingManager
import ai.ninerouter.service.server.SiteTools
import ai.ninerouter.util.Crypto
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val QUOTA_TTL_MS = 90_000L

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppContainer.repository

    val connections: StateFlow<List<ProviderConnection>> =
        repo.connections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val combos: StateFlow<List<Combo>> =
        repo.combos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customProviders: StateFlow<List<CustomProvider>> =
        repo.customProviders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customSpecs: StateFlow<List<ProviderSpec>> =
        repo.customProviders
            .map { nodes -> nodes.map(ProviderCatalog::customSpec) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val keys: StateFlow<List<ApiKey>> =
        repo.keys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requests: StateFlow<List<RequestDetail>> =
        repo.requests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _settings = MutableStateFlow<Map<String, String>>(emptyMap())
    val settings: StateFlow<Map<String, String>> = _settings.asStateFlow()

    /** Saved hosted runtimes, decrypted and in display order. */
    val hostedServices: StateFlow<List<HostedService>> = _settings
        .map { HostingStore.decode(it[HostingStore.SETTINGS_KEY]) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hostingStates: StateFlow<Map<String, HostServiceState>> = HostingRuntime.states

    private val _hostingAvailability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hostingAvailability: StateFlow<Map<String, Boolean>> = _hostingAvailability.asStateFlow()

    /** MySQL schemas, loaded on demand by the Databases tab. */
    private val _databases = MutableStateFlow<List<SiteDatabases.DbInfo>>(emptyList())
    val databases: StateFlow<List<SiteDatabases.DbInfo>> = _databases.asStateFlow()

    private val _databaseBusy = MutableStateFlow(false)
    val databaseBusy: StateFlow<Boolean> = _databaseBusy.asStateFlow()

    private val _databaseVersion = MutableStateFlow<String?>(null)
    val databaseVersion: StateFlow<String?> = _databaseVersion.asStateFlow()

    val running: StateFlow<Boolean> = ProxyRuntime.running
    val port: StateFlow<Int> = ProxyRuntime.port
    val lastError: StateFlow<String?> = ProxyRuntime.lastError
    val tunnelState: StateFlow<TunnelState> = TunnelRuntime.state
    val linkTunnels: StateFlow<Map<String, TunnelState>> = LinkTunnelRuntime.states
    val ftpHostState: StateFlow<FtpHostState> = FtpHostRuntime.state

    private val _detectedPorts = MutableStateFlow<Set<Int>?>(null)
    val detectedPorts: StateFlow<Set<Int>?> = _detectedPorts.asStateFlow()

    private val _scanningPorts = MutableStateFlow(false)
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** Bumped whenever a live model catalog changes, so Compose re-reads specs. */
    private val _catalogVersion = MutableStateFlow(0)
    val catalogVersion: StateFlow<Int> = _catalogVersion.asStateFlow()

    /** Provider ids with an OAuth browser sign-in currently in flight. */
    private val _oauthBusy = MutableStateFlow<Set<String>>(emptySet())
    val oauthBusy: StateFlow<Set<String>> = _oauthBusy.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    /** Live provider quota snapshots keyed by connection id. */
    private val _quotas = MutableStateFlow<Map<String, ProviderQuota>>(emptyMap())
    val quotas: StateFlow<Map<String, ProviderQuota>> = _quotas.asStateFlow()

    /** Connection ids with an in-flight quota request. */
    private val _quotaBusy = MutableStateFlow<Set<String>>(emptySet())
    val quotaBusy: StateFlow<Set<String>> = _quotaBusy.asStateFlow()

    private val _quotaRefreshAllAt = MutableStateFlow(0L)
    val quotaRefreshAllAt: StateFlow<Long> = _quotaRefreshAllAt.asStateFlow()

    val stats: StateFlow<DashboardStats> = combine(repo.requests, repo.connections) { list, conns ->
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = list.filter { it.timestamp >= start }
        val success = today.count { (it.status ?: 0) in 200..299 }
        DashboardStats(
            todayRequests = today.size,
            todayTokens = today.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() },
            successRate = if (today.isEmpty()) 0.0 else success * 100.0 / today.size,
            avgLatencyMs = if (today.isEmpty()) 0 else today.sumOf { it.latencyMs ?: 0 } / today.size,
            activeConnections = conns.count { it.isActive && it.testStatus != "unavailable" },
            totalConnections = conns.size,
            lockedConnections = conns.count {
                ai.ninerouter.engine.FallbackPolicy.isUnavailable(it) ||
                    ai.ninerouter.engine.FallbackPolicy.earliestModelLock(it) != null
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardStats())

    val providerUsage: StateFlow<List<ProviderUsage>> =
        combine(repo.requests, repo.connections, repo.customProviders) { list, conns, customNodes ->
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val today = list.filter { it.timestamp >= start }
            val customNames = customNodes.associate { it.id to it.name }
            fun nameOf(provider: String): String =
                ProviderCatalog.byId(provider)?.name ?: customNames[provider] ?: provider
            val grouped = today.groupBy { it.provider ?: "unknown" }.map { (provider, rows) ->
                ProviderUsage(
                    providerId = provider,
                    name = nameOf(provider),
                    requests = rows.size,
                    tokens = rows.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() },
                    errors = rows.count { (it.status ?: 0) >= 400 }
                )
            }.toMutableList()
            conns.map { it.provider }.distinct().forEach { provider ->
                if (grouped.none { it.providerId == provider }) {
                    grouped += ProviderUsage(
                        providerId = provider,
                        name = nameOf(provider),
                        requests = 0, tokens = 0, errors = 0
                    )
                }
            }
            grouped.sortedByDescending { it.requests }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { repo.seed() }
            refreshSettings()
        }
    }

    fun refreshSettings() {
        viewModelScope.launch {
            _settings.value = runCatching { repo.settingsMap() }.getOrDefault(emptyMap())
        }
    }

    fun toggleProxy(run: Boolean) {
        val context = getApplication<Application>()
        if (run) ProxyForegroundService.start(context) else ProxyForegroundService.stop(context)
    }

    fun restartProxy() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            ProxyForegroundService.stop(context)
            delay(700)
            ProxyForegroundService.start(context)
            emit("Restarting local gateway…")
        }
    }

    /** Persist the tunnel configuration and let the foreground service own the process. */
    fun startTunnel(mode: String, token: String, hostname: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val normalizedMode = if (mode == "token") "token" else "quick"
                val typedToken = token.trim()
                val storedToken = repo.getSetting("tunnel_token").orEmpty()
                if (normalizedMode == "token" && typedToken.isBlank() && storedToken.isBlank()) {
                    throw IllegalArgumentException("Cloudflare tunnel token is required for a named tunnel")
                }
                repo.putSetting("tunnel_enabled", "true")
                repo.putSetting("tunnel_mode", normalizedMode)
                repo.putSetting("tunnel_hostname", hostname.trim())
                if (typedToken.isNotBlank()) {
                    repo.putSetting("tunnel_token", Crypto.encrypt(typedToken).orEmpty())
                }
                refreshSettings()
            }.onFailure {
                emit(it.message ?: "Could not start the Cloudflare tunnel", true)
                return@launch
            }
            ProxyForegroundService.startTunnel(context)
            emit("Cloudflare tunnel starting…")
        }
    }

    fun stopTunnel() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                repo.putSetting("tunnel_enabled", "false")
                refreshSettings()
            }
            ProxyForegroundService.stopTunnel(context)
            emit("Cloudflare tunnel stopped")
        }
    }

    fun retryTunnel() {
        val current = _settings.value
        startTunnel(
            mode = current["tunnel_mode"] ?: "quick",
            token = "",
            hostname = current["tunnel_hostname"].orEmpty(),
        )
    }

    /** Persist and apply the optional on-device FTP host. */
    fun saveFtpHost(settings: FtpHostSettings) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val safe = runCatching { FtpHostStore.require(settings) }
                .getOrElse {
                    emit(it.message ?: "Invalid FTP host settings", true)
                    return@launch
                }
            val gatewayPort = ProxyRuntime.port.value
            val publicPort = TunnelPorts.publicPort(gatewayPort)
            if (safe.port == gatewayPort || safe.port == publicPort) {
                emit("FTP host port ${safe.port} belongs to the 9Router gateway.", true)
                return@launch
            }
            if (FtpHostStore.isPassivePort(safe.port)) {
                emit("FTP host port ${safe.port} is reserved for passive data connections.", true)
                return@launch
            }
            runCatching {
                repo.putSetting(FtpHostStore.SETTINGS_KEY, FtpHostStore.encode(safe))
                refreshSettings()
            }.onFailure {
                emit(it.message ?: "Could not save the FTP host", true)
                return@launch
            }
            if (ProxyRuntime.running.value) {
                ProxyForegroundService.syncFtpHost(context)
            }
            emit(if (safe.enabled) "FTP host saved · starting on 127.0.0.1:${safe.port}" else "FTP host stopped")
        }
    }

    /** Which runtimes are installed, so the hosting card can badge them. */
    fun refreshHostingAvailability() {
        viewModelScope.launch {
            _hostingAvailability.value = withContext(Dispatchers.IO) {
                HostingManager.availability(getApplication())
            }
        }
    }

    /** Ports 9Router itself owns and a hosted runtime must not bind. */
    private suspend fun blockedHostingPorts(): Set<Int> {
        val gateway = ProxyRuntime.port.value
        val ftp = FtpHostStore.decode(repo.getSetting(FtpHostStore.SETTINGS_KEY))
        val ports = mutableSetOf(gateway, TunnelPorts.publicPort(gateway))
        if (ftp.enabled) ports += ftp.port
        ports += FtpHostStore.passivePorts.toList()
        return ports
    }

    /** Create or update a hosted runtime and apply its process state. */
    fun saveHostedService(service: HostedService) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                val id = service.id.ifBlank { HostingStore.newId(service.kind, current) }
                val normalized = normalizeHosted(service.copy(id = id))
                val index = current.indexOfFirst { it.id == id }
                val next = if (index >= 0) {
                    current.toMutableList().also { it[index] = normalized }
                } else {
                    current + normalized
                }
                val safe = HostingStore.require(next, blockedHostingPorts())
                repo.putSetting(HostingStore.SETTINGS_KEY, HostingStore.encode(safe))
                refreshSettings()
                ProxyForegroundService.syncHosting(context)
                emit("${normalized.name} saved")
            }.onFailure { emit(it.message ?: "Could not save the hosted service", true) }
        }
    }

    /** Fill defaults that make a fresh runtime usable in one tap. */
    private fun normalizeHosted(service: HostedService): HostedService {
        val spec = HostedKinds.byId(service.kind) ?: return service
        val database = spec.id in HostingStore.DATABASE_KINDS
        return service.copy(
            name = service.name.trim().ifBlank { spec.label },
            entry = service.entry.trim(),
            username = service.username.trim().ifBlank { spec.defaultUser },
            password = if (database && service.password.isBlank()) {
                HostingStore.newPassword()
            } else {
                service.password
            },
        )
    }

    fun deleteHostedService(id: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                val removed = current.firstOrNull { it.id == id }
                repo.putSetting(
                    HostingStore.SETTINGS_KEY,
                    HostingStore.encode(current.filterNot { it.id == id }),
                )
                HostingManager.stop(id)
                HostingManager.forget(id)
                refreshSettings()
                ProxyForegroundService.syncHosting(context)
                emit("${removed?.name ?: "Service"} deleted")
            }.onFailure { emit(it.message ?: "Could not delete the hosted service", true) }
        }
    }

    fun startHostedService(id: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val service = HostingStore
                .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                .firstOrNull { it.id == id }
            if (service == null) {
                emit("That hosted service no longer exists", true)
                return@launch
            }
            // A hosted process must outlive the screen, so keep the foreground
            // service (which owns the runtime lifecycle) alive.
            ProxyForegroundService.start(context)
            runCatching { HostingManager.start(context, service) }
                .onFailure { emit(it.message ?: "Could not start the service", true) }
            refreshHostingAvailability()
        }
    }

    fun stopHostedService(id: String) {
        viewModelScope.launch {
            runCatching { HostingManager.stop(id) }
                .onFailure { emit(it.message ?: "Could not stop the service", true) }
        }
    }

    fun setHostedAutoStart(id: String, autoStart: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                val next = current.map { if (it.id == id) it.copy(autoStart = autoStart) else it }
                repo.putSetting(HostingStore.SETTINGS_KEY, HostingStore.encode(next))
                refreshSettings()
                ProxyForegroundService.syncHosting(context)
            }.onFailure { emit(it.message ?: "Could not update the service", true) }
        }
    }

    /** Replace one saved service and refresh the settings flow. */
    private suspend fun persistHosted(updated: HostedService) {
        val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
        val next = current.map { if (it.id == updated.id) updated else it }
        repo.putSetting(HostingStore.SETTINGS_KEY, HostingStore.encode(next))
        refreshSettings()
    }

    /** Upload a ZIP and turn it into a new release, like a platform deploy. */
    fun deployHostedService(id: String, uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val service = HostingStore
                .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                .firstOrNull { it.id == id }
            if (service == null) {
                emit("That hosted service no longer exists", true)
                return@launch
            }
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not read the archive.")
                input.use { HostingManager.deploy(context, service, it) }
            }.onSuccess { updated ->
                persistHosted(updated)
                emit("${updated.name}: release ${updated.active} deployed")
            }.onFailure {
                emit(it.message ?: "Deployment failed", true)
            }
        }
    }

    /** Copy a folder chosen with the system picker into a new release. */
    fun deployHostedFolder(id: String, uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val service = HostingStore
                .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                .firstOrNull { it.id == id }
            if (service == null) {
                emit("That hosted service no longer exists", true)
                return@launch
            }
            runCatching {
                HostingManager.deployFolder(context, service, uri)
            }.onSuccess { updated ->
                persistHosted(updated)
                emit("${updated.name}: folder hosted as release ${updated.active}")
            }.onFailure {
                emit(it.message ?: "Could not host that folder", true)
            }
        }
    }

    /** Serve another release and restart the process if it is running. */
    fun activateHostedRelease(id: String, releaseId: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val service = HostingStore
                .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                .firstOrNull { it.id == id } ?: return@launch
            runCatching { HostingManager.activate(context, service, releaseId) }
                .onSuccess {
                    persistHosted(it)
                    emit("${it.name}: release $releaseId active")
                }
                .onFailure { emit(it.message ?: "Could not activate the release", true) }
        }
    }

    fun deleteHostedRelease(id: String, releaseId: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val service = HostingStore
                .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                .firstOrNull { it.id == id } ?: return@launch
            runCatching { HostingManager.deleteRelease(context, service, releaseId) }
                .onSuccess {
                    persistHosted(it)
                    emit("Release $releaseId deleted")
                }
                .onFailure { emit(it.message ?: "Could not delete the release", true) }
        }
    }

    /** Save environment variables and the build command, restarting if needed. */
    fun saveHostedEnvironment(id: String, env: Map<String, String>, build: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                val service = current.firstOrNull { it.id == id }
                    ?: error("That hosted service no longer exists")
                val updated = service.copy(env = env, build = build)
                val next = current.map { if (it.id == id) updated else it }
                repo.putSetting(
                    HostingStore.SETTINGS_KEY,
                    HostingStore.encode(HostingStore.require(next, blockedHostingPorts())),
                )
                refreshSettings()
                ProxyForegroundService.syncHosting(context)
                emit("${service.name}: environment saved")
            }.onFailure { emit(it.message ?: "Could not save the environment", true) }
        }
    }

    /**
     * Create a site with sensible defaults: the next free loopback port, a
     * starter page and, unless the caller opts out, an immediate start.
     */
    fun createHostedSite(
        kind: String,
        name: String,
        domain: String = "",
        entry: String = "",
        autoStart: Boolean = true,
    ) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val spec = HostedKinds.require(kind)
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                if (current.size >= HostingStore.MAX_SERVICES) {
                    error("At most ${HostingStore.MAX_SERVICES} hosted services are supported.")
                }
                val blocked = blockedHostingPorts()
                val id = HostingStore.newId(kind, current)
                val port = HostingStore.nextPort(spec.port, current.map { it.port }, blocked)
                val created = normalizeHosted(
                    HostedService(
                        id = id,
                        kind = kind,
                        name = name.ifBlank { spec.label },
                        port = port,
                        entry = entry,
                        domain = domain,
                        autoStart = autoStart,
                    ),
                )
                val safe = HostingStore.require(current + created, blocked)
                repo.putSetting(HostingStore.SETTINGS_KEY, HostingStore.encode(safe))
                refreshSettings()
                ProxyForegroundService.syncHosting(context)
                if (autoStart) {
                    ProxyForegroundService.start(context)
                    HostingManager.start(context, safe.first { it.id == id })
                }
                emit("${created.name} created on 127.0.0.1:$port")
            }.onFailure { emit(it.message ?: "Could not create the site", true) }
        }
    }

    /**
     * Assign a hostname to a site. A named local link is created at the same
     * time, so `name.localhost:<publicPort>` and the tunnel hostname route to
     * the site with no extra step.
     */
    fun setHostedDomain(id: String, rawDomain: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                val service = current.firstOrNull { it.id == id }
                    ?: error("That hosted service no longer exists")
                val domain = rawDomain.trim().lowercase()
                val updated = service.copy(domain = domain)
                val next = current.map { if (it.id == id) updated else it }
                repo.putSetting(
                    HostingStore.SETTINGS_KEY,
                    HostingStore.encode(HostingStore.require(next, blockedHostingPorts())),
                )
                syncDomainLink(context, service, domain)
                refreshSettings()
                emit(
                    if (domain.isEmpty()) {
                        "Hostname removed from ${service.name}"
                    } else {
                        "$domain now routes to ${service.name}"
                    },
                )
            }.onFailure { emit(it.message ?: "Could not assign that hostname", true) }
        }
    }

    private suspend fun syncDomainLink(
        context: Application,
        service: HostedService,
        domain: String,
    ) {
        val gateway = ProxyRuntime.port.value
        val blocked = setOf(gateway, TunnelPorts.publicPort(gateway))
        val current = TunnelLinks.decode(repo.getSetting(TunnelLinks.SETTINGS_KEY))
        val target = service.target
        val previous = service.domain.trim().lowercase()
        val withoutSite = current.filterNot { link ->
            link.target == target &&
                (link.host.equals(previous, true) || link.host.equals(domain, true))
        }
        val next = if (domain.isBlank()) {
            withoutSite
        } else {
            val link = TunnelLinks.fromDraft(
                TunnelLinkDraft(
                    name = service.name,
                    host = domain,
                    target = target,
                    mode = TunnelLinks.MODE_NAMED,
                    kind = TunnelLinks.KIND_HTTP,
                ),
                blockedPorts = blocked,
            )
            withoutSite + link
        }
        repo.putSetting(TunnelLinks.SETTINGS_KEY, TunnelLinks.encode(next))
        ProxyForegroundService.syncLinks(context)
    }

    /** Generate a self-signed certificate for a site and reload its runtime. */
    fun generateSiteCertificate(id: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val service = HostingStore
                    .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                    .firstOrNull { it.id == id } ?: error("That hosted service no longer exists")
                val layout = HostingManager.layout(context, service)
                val certificate = SiteTools.certificate(context, service, layout)
                if (HostingRuntime.state(id).running) {
                    HostingManager.restart(context, service)
                }
                emit(
                    "Certificate for ${certificate.subject.substringAfter("CN=")} " +
                        "is valid for ${certificate.daysLeft} days",
                )
            }.onFailure { emit(it.message ?: "Could not generate the certificate", true) }
        }
    }

    fun deleteSiteCertificate(id: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val service = HostingStore
                    .decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                    .firstOrNull { it.id == id } ?: error("That hosted service no longer exists")
                val layout = HostingManager.layout(context, service)
                SiteTools.removeCertificate(layout)
                if (HostingRuntime.state(id).running) {
                    HostingManager.restart(context, service)
                }
                emit("Certificate removed from ${service.name}")
            }.onFailure { emit(it.message ?: "Could not remove the certificate", true) }
        }
    }

    private suspend fun mysqlService(): HostedService? =
        HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
            .firstOrNull { it.kind == "mysql" }

    /** Load MySQL schemas and the server banner for the Databases tab. */
    fun refreshDatabases() {
        if (_databaseBusy.value) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            _databaseBusy.value = true
            runCatching {
                val mysql = mysqlService() ?: return@runCatching
                _databaseVersion.value = SiteTools.databaseVersion(context, mysql)
                _databases.value = SiteTools.databases(context, mysql)
            }.onFailure {
                _databaseVersion.value = null
                emit(it.message ?: "Could not read the databases", true)
            }
            _databaseBusy.value = false
        }
    }

    fun createDatabase(name: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val mysql = mysqlService() ?: error("Add a MySQL service first.")
                val db = SiteTools.createDatabase(context, mysql, name)
                _databases.value = SiteTools.databases(context, mysql)
                emit("Database $db created")
            }.onFailure { emit(it.message ?: "Could not create the database", true) }
        }
    }

    fun dropDatabase(name: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val mysql = mysqlService() ?: error("Add a MySQL service first.")
                SiteTools.dropDatabase(context, mysql, name)
                _databases.value = SiteTools.databases(context, mysql)
                emit("Database $name dropped")
            }.onFailure { emit(it.message ?: "Could not drop the database", true) }
        }
    }

    /**
     * Create the schema for one site and inject ready-to-use connection
     * variables into its environment.
     */
    fun attachSiteDatabase(id: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val mysql = mysqlService() ?: error("Add a MySQL service first.")
                val current = HostingStore.decode(repo.getSetting(HostingStore.SETTINGS_KEY))
                val service = current.firstOrNull { it.id == id }
                    ?: error("That hosted service no longer exists")
                val name = SiteDatabases.siteDatabase(service.id)
                val db = SiteTools.createDatabase(context, mysql, name)
                val env = service.env + mapOf(
                    "DB_HOST" to "127.0.0.1",
                    "DB_PORT" to mysql.port.toString(),
                    "DB_NAME" to db,
                    "DB_USER" to mysql.username,
                    "DB_PASSWORD" to mysql.password,
                    "DATABASE_URL" to SiteDatabases.url(
                        "127.0.0.1",
                        mysql.port,
                        db,
                        mysql.username,
                        mysql.password,
                    ),
                )
                val updated = service.copy(dbName = db, env = env)
                val next = current.map { if (it.id == id) updated else it }
                repo.putSetting(
                    HostingStore.SETTINGS_KEY,
                    HostingStore.encode(HostingStore.require(next, blockedHostingPorts())),
                )
                refreshSettings()
                ProxyForegroundService.syncHosting(context)
                if (HostingRuntime.state(id).running) {
                    HostingManager.restart(context, updated)
                }
                _databases.value = mysqlService()?.let { SiteTools.databases(context, it) }
                    ?: _databases.value
                emit("Database $db attached to ${service.name}")
            }.onFailure { emit(it.message ?: "Could not attach the database", true) }
        }
    }

    /** Probe loopback ports so the UI can mark services that are actually up. */
    fun scanLocalServices() {
        if (_scanningPorts.value) return
        val links = TunnelLinks.decode(_settings.value[TunnelLinks.SETTINGS_KEY])
        val ftpHost = FtpHostStore.decode(_settings.value[FtpHostStore.SETTINGS_KEY])
        val hosted = HostingStore.decode(_settings.value[HostingStore.SETTINGS_KEY])
        val ports = (
            LocalServices.defaultPorts +
                links.mapNotNull { TunnelLinks.targetPort(it.target) } +
                hosted.map { it.port } +
                listOfNotNull(ftpHost.port.takeIf { ftpHost.enabled })
            ).distinct()
        viewModelScope.launch {
            _scanningPorts.value = true
            _detectedPorts.value = runCatching {
                LocalServices.scan(ports)
            }.getOrDefault(emptySet())
            _scanningPorts.value = false
        }
    }

    /** Persist a validated local link. The public listener reads it per request. */
    fun saveTunnelLink(link: TunnelLink) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val port = ProxyRuntime.port.value
                val safe = TunnelLinks.fromDraft(
                    TunnelLinkDraft(
                        name = link.name,
                        host = link.host,
                        path = link.path,
                        target = link.target,
                        stripPrefix = link.stripPrefix,
                        enabled = link.enabled,
                        mode = link.mode,
                        kind = link.kind,
                        username = link.username,
                        password = link.password,
                    ),
                    existingId = link.id,
                    existingKey = link.key,
                    blockedPorts = setOf(port, TunnelPorts.publicPort(port)),
                )
                val current = TunnelLinks.decode(repo.getSetting(TunnelLinks.SETTINGS_KEY))
                val index = current.indexOfFirst { it.id == safe.id }
                val next = if (index >= 0) {
                    current.toMutableList().also { it[index] = safe }
                } else {
                    current + safe
                }
                if (next.size > TunnelLinks.MAX_LINKS) {
                    throw IllegalArgumentException("At most ${TunnelLinks.MAX_LINKS} local links are supported.")
                }
                repo.putSetting(TunnelLinks.SETTINGS_KEY, TunnelLinks.encode(next))
                refreshSettings()
                ProxyForegroundService.syncLinks(context)
            }.onFailure { emit(it.message ?: "Could not save the local link", true) }
        }
    }

    fun deleteTunnelLink(id: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = TunnelLinks.decode(repo.getSetting(TunnelLinks.SETTINGS_KEY))
                repo.putSetting(
                    TunnelLinks.SETTINGS_KEY,
                    TunnelLinks.encode(current.filterNot { it.id == id }),
                )
                refreshSettings()
                ProxyForegroundService.syncLinks(context)
            }.onFailure { emit(it.message ?: "Could not delete the local link", true) }
        }
    }

    fun setTunnelLinkEnabled(id: String, enabled: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val current = TunnelLinks.decode(repo.getSetting(TunnelLinks.SETTINGS_KEY))
                repo.putSetting(
                    TunnelLinks.SETTINGS_KEY,
                    TunnelLinks.encode(current.map { if (it.id == id) it.copy(enabled = enabled) else it }),
                )
                refreshSettings()
                ProxyForegroundService.syncLinks(context)
            }.onFailure { emit(it.message ?: "Could not update the local link", true) }
        }
    }

    /** Restart one quick link connector without touching the gateway tunnel. */
    fun restartTunnelLink(id: String) {
        val context = getApplication<Application>()
        ProxyForegroundService.syncLinks(context, id)
        emit("Restarting the quick link…")
    }

    fun saveSettingsAndRestart(values: Map<String, String>) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                values.forEach { (key, value) -> repo.putSetting(key, value) }
                refreshSettings()
            }.onFailure { emit(it.message ?: "Could not save settings", true) }
            ProxyForegroundService.stop(context)
            delay(700)
            ProxyForegroundService.start(context)
            emit("Settings saved · gateway restarting")
        }
    }

    fun saveSettings(values: Map<String, String>, message: String? = null) {
        viewModelScope.launch {
            runCatching {
                values.forEach { (key, value) -> repo.putSetting(key, value) }
                refreshSettings()
            }.onSuccess { if (message != null) emit(message) }
                .onFailure { emit(it.message ?: "Could not save settings", true) }
        }
    }

    fun addConnection(
        providerId: String,
        name: String,
        apiKey: String,
        email: String,
        baseUrl: String,
        advanced: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            val providerLabel = withContext(Dispatchers.IO) {
                repo.specById(providerId)?.name ?: providerId
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val data = buildString {
                        val parts = mutableListOf<String>()
                        if (advanced.isNotBlank()) {
                            parts += advanced.trim().removePrefix("{").removeSuffix("}")
                        }
                        if (providerId == "antigravity" && apiKey.isNotBlank() && !advanced.contains("accessToken")) {
                            parts += "\"accessToken\":\"${apiKey.replace("\"", "\\\"")}\""
                        }
                        if (baseUrl.isNotBlank()) {
                            parts += "\"baseUrl\":\"${baseUrl.trim().replace("\"", "\\\"")}\""
                        }
                        if (parts.isNotEmpty()) {
                            append("{")
                            append(parts.joinToString(","))
                            append("}")
                        }
                    }
                    repo.addConnection(
                        providerId = providerId,
                        name = name,
                        apiKey = apiKey.ifBlank { null },
                        email = email.ifBlank { null },
                        authType = if (ai.ninerouter.engine.oauth.OAuthProviders.supports(providerId)) "oauth" else "apikey",
                        providerSpecificData = data.ifBlank { null },
                        baseUrl = baseUrl.ifBlank { null }
                    )
                }
            }.onSuccess {
                emit("$providerLabel connection added")
                onDone()
            }.onFailure { emit(it.message ?: "Could not add connection", true) }
        }
    }

    /**
     * Starts a real OAuth sign-in: opens the provider consent page in the
     * browser and waits for the one-shot loopback callback on localhost.
     */
    fun connectOAuth(providerId: String, onDone: () -> Unit) {
        val provider = OAuthProviders.byId(providerId)
        if (provider == null) {
            emit("${ProviderCatalog.byId(providerId)?.name ?: providerId} does not support OAuth sign-in", true)
            return
        }
        if (_oauthBusy.value.contains(providerId)) return
        viewModelScope.launch {
            setOAuthBusy(providerId, true)
            var session: ai.ninerouter.engine.oauth.OAuthSession? = null
            try {
                val started = withContext(Dispatchers.IO) { OAuthClient.start(provider) }
                session = started
                val opened = runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(started.authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    getApplication<Application>().startActivity(intent)
                }
                if (opened.isFailure) {
                    started.close()
                    session = null
                    emit("No browser found. Open this link in a browser to sign in:\n${started.authUrl}", true)
                    return@launch
                }
                emit("Finish the ${ProviderCatalog.byId(providerId)?.name ?: provider.providerId} sign-in in your browser…")
                val tokens = withContext(Dispatchers.IO) { started.awaitTokens() }
                val connection = repo.addOAuthConnection(providerId, tokens)
                emit("${ProviderCatalog.byId(providerId)?.name ?: providerId} connected${connection.email?.let { " as $it" }.orEmpty()}")
                onDone()
            } catch (e: OAuthException) {
                emit(e.message ?: "OAuth sign-in failed", true)
            } catch (e: Exception) {
                emit(e.message ?: "OAuth sign-in failed", true)
            } finally {
                runCatching { session?.close() }
                setOAuthBusy(providerId, false)
            }
        }
    }

    fun testConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            setBusy(connection.id, true)
            runCatching { withContext(Dispatchers.IO) { repo.testConnection(connection) } }
                .onSuccess { (ok, message) -> emit(message, !ok) }
                .onFailure { emit(it.message ?: "Test failed", true) }
            setBusy(connection.id, false)
        }
    }

    fun fetchModels(connection: ProviderConnection) {
        viewModelScope.launch {
            setBusy(connection.id, true)
            runCatching { withContext(Dispatchers.IO) { repo.fetchProviderModels(connection.id) } }
                .onSuccess { result ->
                    if (result.ok) _catalogVersion.value += 1
                    emit(result.message, !result.ok)
                }
                .onFailure { emit(it.message ?: "Model fetch failed", true) }
            setBusy(connection.id, false)
        }
    }

    fun toggleConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            runCatching {
                repo.updateConnection(connection.copy(isActive = !connection.isActive))
            }.onSuccess {
                emit(if (connection.isActive) "Connection paused" else "Connection enabled")
            }.onFailure { emit(it.message ?: "Update failed", true) }
        }
    }

    fun deleteConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            runCatching { repo.deleteConnection(connection.id) }
                .onSuccess { emit("Connection deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun saveConnection(
        connection: ProviderConnection,
        name: String,
        defaultModel: String,
        apiKey: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                repo.updateConnection(
                    connection.copy(
                        name = name.ifBlank { connection.name },
                        displayName = name.ifBlank { connection.displayName },
                        defaultModel = defaultModel.ifBlank { connection.defaultModel },
                        apiKey = if (apiKey.isBlank()) connection.apiKey
                        else ai.ninerouter.util.Crypto.encrypt(apiKey)
                    )
                )
            }.onSuccess {
                emit("Connection updated")
                onDone()
            }.onFailure { emit(it.message ?: "Update failed", true) }
        }
    }

    fun saveCustomProvider(
        id: String?,
        type: String,
        name: String,
        prefix: String,
        apiType: String,
        baseUrl: String,
        models: List<String>,
        apiKey: String,
        createConnection: Boolean,
        onDone: (CustomProvider) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.saveCustomProvider(
                        id = id,
                        type = type,
                        name = name,
                        prefix = prefix,
                        apiType = apiType,
                        baseUrl = baseUrl,
                        models = models,
                        apiKey = apiKey.ifBlank { null },
                        createConnection = createConnection && apiKey.isNotBlank()
                    )
                }
            }.onSuccess { node ->
                emit(if (id == null) "Compatible provider created" else "Compatible provider updated")
                onDone(node)
            }.onFailure { emit(it.message ?: "Could not save compatible provider", true) }
        }
    }

    fun deleteCustomProvider(node: CustomProvider) {
        viewModelScope.launch {
            runCatching { repo.deleteCustomProvider(node.id) }
                .onSuccess { emit("${node.name} deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun checkCustomProvider(
        type: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        onResult: (CompatibleProbe) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.probeCompatible(type, baseUrl, apiKey, modelId)
                }
            }.onSuccess { result ->
                emit(result.message, !result.ok)
                onResult(result)
            }.onFailure {
                val message = it.message ?: "Check failed"
                emit(message, true)
                onResult(CompatibleProbe(false, message))
            }
        }
    }

    fun saveCustomModels(nodeId: String, models: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.saveCustomModels(nodeId, models) }
                .onSuccess {
                    emit("Models updated")
                    onDone()
                }
                .onFailure { emit(it.message ?: "Could not save models", true) }
        }
    }

    fun discoverCustomModels(nodeId: String, apiKey: String, onDone: (List<String>?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.discoverCustomModels(
                        nodeId,
                        apiKey.ifBlank { null },
                        merge = false
                    )
                }
            }.onSuccess { models ->
                emit("Found ${models.size} models")
                onDone(models)
            }.onFailure {
                emit(it.message ?: "Could not fetch models", true)
                onDone(null)
            }
        }
    }

    fun saveCombo(id: String?, name: String, models: List<String>, kind: String?, onDone: () -> Unit) {
        if (name.isBlank() || models.isEmpty()) {
            emit("Add a name and at least one model", true)
            return
        }
        viewModelScope.launch {
            runCatching { repo.saveCombo(id, name, models, kind) }
                .onSuccess {
                    emit("Combo saved")
                    onDone()
                }
                .onFailure { emit(it.message ?: "Could not save combo", true) }
        }
    }

    fun deleteCombo(combo: Combo) {
        viewModelScope.launch {
            runCatching { repo.deleteCombo(combo.id) }
                .onSuccess { emit("Combo deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun createKey(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.createKey(name) }
                .onSuccess {
                    emit("API key created")
                    onDone()
                }
                .onFailure { emit(it.message ?: "Could not create key", true) }
        }
    }

    fun deleteKey(key: ApiKey) {
        viewModelScope.launch {
            runCatching { repo.deleteKey(key.id) }
                .onSuccess { emit("Key deleted") }
                .onFailure { emit(it.message ?: "Delete failed", true) }
        }
    }

    fun toggleKey(key: ApiKey) {
        viewModelScope.launch {
            runCatching { repo.setKeyActive(key, !key.isActive) }
                .onSuccess { emit(if (key.isActive) "Key disabled" else "Key enabled") }
                .onFailure { emit(it.message ?: "Update failed", true) }
        }
    }

    /**
     * Fetches live quota windows for one connection. Cached snapshots younger
     * than [QUOTA_TTL_MS] are reused unless [force] is set.
     */
    fun refreshQuota(connection: ProviderConnection, force: Boolean = true) {
        refreshQuotas(listOf(connection), force)
    }

    /**
     * Fetches live quota windows for every supplied connection with bounded
     * parallelism. Every card is marked busy immediately so the UI can show a
     * per-provider spinner even when several requests are queued.
     */
    fun refreshQuotas(targets: List<ProviderConnection>, force: Boolean = false) {
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        val busy = _quotaBusy.value
        val pending = targets.distinctBy { it.id }.filter { connection ->
            if (connection.id in busy) return@filter false
            val snapshot = _quotas.value[connection.id]
            force || snapshot == null || now - snapshot.fetchedAt > QUOTA_TTL_MS
        }
        if (pending.isEmpty()) return
        _quotaBusy.update { busy -> busy + pending.map { it.id } }
        viewModelScope.launch {
            val gate = Semaphore(3)
            coroutineScope {
                pending.map { connection ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            try {
                                val result = runCatching { ProviderQuotaClient.fetch(connection, force) }
                                    .getOrElse { error ->
                                        ProviderQuota(
                                            connectionId = connection.id,
                                            provider = connection.provider,
                                            message = error.message ?: "Quota fetch failed"
                                        )
                                    }
                                _quotas.update { it + (connection.id to result) }
                            } finally {
                                _quotaBusy.update { it - connection.id }
                            }
                        }
                    }
                }.awaitAll()
            }
            _quotaRefreshAllAt.value = System.currentTimeMillis()
            if (pending.size > 1) emit("Refreshed quota for ${pending.size} connections")
        }
    }

    fun clearCooldowns() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repo.providerDao.getAll().forEach { connection ->
                        val data = connection.specificData()
                            .filterKeys {
                                !it.startsWith(ai.ninerouter.engine.FallbackPolicy.MODEL_LOCK_PREFIX) &&
                                    it != "rateLimitedUntil"
                            }
                        repo.updateConnection(
                            connection.copy(
                                testStatus = "active",
                                lastError = null,
                                errorCode = null,
                                backoffLevel = 0,
                                providerSpecificData = if (data.isEmpty()) null else
                                    kotlinx.serialization.json.JsonObject(
                                        data.mapValues { JsonPrimitive(it.value) }
                                    ).toString()
                            )
                        )
                    }
                }
            }.onSuccess { emit("Cooldowns cleared") }
                .onFailure { emit(it.message ?: "Could not clear cooldowns", true) }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            runCatching { repo.clearRequests() }
                .onSuccess { emit("Request log cleared") }
                .onFailure { emit(it.message ?: "Could not clear log", true) }
        }
    }

    fun pruneLogs(days: Int) {
        viewModelScope.launch {
            runCatching { repo.pruneOldRequests(days) }
                .onSuccess { emit("Removed requests older than $days days") }
                .onFailure { emit(it.message ?: "Cleanup failed", true) }
        }
    }

    fun sendTelegramTest(token: String, chatId: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.sendTelegram(token, chatId, "✅ 9Router Android is connected.") } }
                .onSuccess { (ok, message) -> emit(message, !ok) }
                .onFailure { emit(it.message ?: "Telegram request failed", true) }
        }
    }

    suspend fun defaultKey(): ApiKey? = withContext(Dispatchers.IO) { repo.defaultKey() }

    suspend fun models(): List<Triple<String, String, String>> =
        withContext(Dispatchers.IO) { ProviderCatalog.modelsForSelection(repo.customSpecs()) }

    suspend fun providers(): List<ProviderSpec> =
        withContext(Dispatchers.IO) { ProviderCatalog.allWith(repo.customSpecs()) }

    /** Resolves a static provider or a user-defined compatible node by id. */
    fun specFor(providerId: String): ProviderSpec? =
        ProviderCatalog.byId(providerId) ?: customSpecs.value.firstOrNull { it.id == providerId }

    private fun setBusy(id: String, value: Boolean) {
        _busy.value = if (value) _busy.value + id else _busy.value - id
    }

    private fun setOAuthBusy(id: String, value: Boolean) {
        _oauthBusy.value = if (value) _oauthBusy.value + id else _oauthBusy.value - id
    }

    private fun emit(message: String, isError: Boolean = false) {
        _events.tryEmit(UiEvent(message, isError))
    }

    data class UiEvent(val message: String, val isError: Boolean = false)
}
