package ai.ninerouter.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.FtpHostStore
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.HostStorage
import ai.ninerouter.core.HostedKinds
import ai.ninerouter.core.HostedService
import ai.ninerouter.service.server.HostingManager
import ai.ninerouter.core.HostingStore
import ai.ninerouter.core.NpmRuntime
import ai.ninerouter.core.TunnelLink
import ai.ninerouter.core.TunnelLinks
import ai.ninerouter.core.TunnelPorts
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.CodeBlock
import ai.ninerouter.ui.components.ConfirmDialog
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.MetaRow
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.BrandDeep
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The hosting console: a premium, tabbed surface for everything that runs on
 * this device. It replaces the single hosting card with dedicated pages for
 * websites, databases, FTP accounts, TLS and storage, while every control calls
 * the real runtime, deployment and database code.
 */
@Composable
fun HostingScreen(viewModel: MainViewModel) {
    val services by viewModel.hostedServices.collectAsState()
    val states by viewModel.hostingStates.collectAsState()
    val availability by viewModel.hostingAvailability.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val ftpState by viewModel.ftpHostState.collectAsState()
    val gatewayPort by viewModel.port.collectAsState()

    val context = LocalContext.current
    var tab by remember { mutableStateOf("overview") }
    var createOpen by remember { mutableStateOf(false) }
    var manageId by remember { mutableStateOf<String?>(null) }
    var domainFor by remember { mutableStateOf<HostedService?>(null) }
    var portFor by remember { mutableStateOf<HostedService?>(null) }
    var deleteFor by remember { mutableStateOf<HostedService?>(null) }
    var filesFor by remember { mutableStateOf<HostedService?>(null) }
    var releasesFor by remember { mutableStateOf<HostedService?>(null) }
    var envFor by remember { mutableStateOf<HostedService?>(null) }
    var deployFor by remember { mutableStateOf<HostedService?>(null) }
    var folderFor by remember { mutableStateOf<HostedService?>(null) }
    var publishFor by remember { mutableStateOf<HostedService?>(null) }

    val ftpSettings = remember(settings[FtpHostStore.SETTINGS_KEY]) {
        FtpHostStore.decode(settings[FtpHostStore.SETTINGS_KEY])
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSettings()
        viewModel.refreshHostingAvailability()
    }

    val deployPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = deployFor
        deployFor = null
        if (uri != null && target != null) viewModel.deployHostedService(target.id, uri)
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = folderFor
        folderFor = null
        if (uri != null && target != null) viewModel.deployHostedFolder(target.id, uri)
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    val manage = services.firstOrNull { it.id == manageId }

    Column(Modifier.fillMaxSize()) {
        HostingTabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            },
            label = "hosting-tabs",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (current) {
                "sites" -> SitesTab(
                    services = services,
                    states = states,
                    onNew = { createOpen = true },
                    onManage = { manageId = it.id },
                    onToggle = { service ->
                        if (states[service.id]?.status == HostStatus.RUNNING) {
                            viewModel.stopHostedService(service.id)
                        } else {
                            viewModel.startHostedService(service.id)
                        }
                    },
                    onOpen = { openUrl("http://127.0.0.1:${effectivePort(it, states[it.id] ?: HostServiceState())}/") },
                    onFiles = { filesFor = it },
                    onDeploy = { deployFor = it; deployPicker.launch(arrayOf("application/zip")) },
                    onFolder = { folderFor = it; folderPicker.launch(null) },
                )

                "databases" -> DatabasesTab(viewModel, services, states)

                "ftp" -> FtpTab(viewModel, services, ftpSettings, ftpState)

                "ssl" -> SslTab(viewModel, services)

                "storage" -> StorageTab(services, states, onFiles = { filesFor = it })

                else -> OverviewTab(
                    services = services,
                    states = states,
                    availability = availability,
                    gatewayPort = gatewayPort,
                    ftpRunning = ftpState.running,
                    onNew = { createOpen = true },
                    onTab = { tab = it },
                    onManage = { manageId = it.id },
                )
            }
        }
    }

    if (createOpen) {
        NewSiteDialog(
            availability = availability,
            onDismiss = { createOpen = false },
            onCreate = { kind, name, domain, entry, autoStart ->
                viewModel.createHostedSite(kind, name, domain, entry, autoStart)
                createOpen = false
                tab = "sites"
            },
        )
    }

    if (manage != null) {
        SiteManageDialog(
            service = manage,
            state = states[manage.id] ?: HostServiceState(port = manage.port),
            services = services,
            onDismiss = { manageId = null },
            onToggle = {
                if (states[manage.id]?.status == HostStatus.RUNNING) {
                    viewModel.stopHostedService(manage.id)
                } else {
                    viewModel.startHostedService(manage.id)
                }
            },
            onOpen = {
                openUrl("http://127.0.0.1:${effectivePort(manage, states[manage.id] ?: HostServiceState())}/")
            },
            onFiles = { filesFor = manage; manageId = null },
            onReleases = { releasesFor = manage; manageId = null },
            onEnvironment = { envFor = manage; manageId = null },
            onDeploy = {
                deployFor = manage
                manageId = null
                deployPicker.launch(arrayOf("application/zip"))
            },
            onFolder = {
                folderFor = manage
                manageId = null
                folderPicker.launch(null)
            },
            onDomain = { domainFor = manage; manageId = null },
            onPort = { portFor = manage; manageId = null },
            onPublish = { publishFor = manage; manageId = null },
            onAttachDatabase = { viewModel.attachSiteDatabase(manage.id) },
            onCertificate = { viewModel.generateSiteCertificate(manage.id) },
            onDeleteCertificate = { viewModel.deleteSiteCertificate(manage.id) },
            onDelete = { deleteFor = manage; manageId = null },
        )
    }

    if (domainFor != null) {
        DomainDialog(
            service = domainFor!!,
            publicPort = TunnelPorts.publicPort(gatewayPort),
            links = TunnelLinks.decode(settings[TunnelLinks.SETTINGS_KEY]),
            onDismiss = { domainFor = null },
            onSave = { domain ->
                viewModel.setHostedDomain(domainFor!!.id, domain)
                domainFor = null
            },
        )
    }

    if (portFor != null) {
        PortDialog(
            service = portFor!!,
            onDismiss = { portFor = null },
            onSave = { port ->
                viewModel.saveHostedService(portFor!!.copy(port = port))
                portFor = null
            },
        )
    }

    if (filesFor != null) {
        val target = filesFor!!
        val root = remember(target.id, target.active) {
            HostingManager.layout(context, target).content
        }
        HostFilesDialog(
            title = "${target.name} · files",
            root = root,
            onDismiss = { filesFor = null },
            onChanged = { viewModel.refreshSettings() },
        )
    }

    if (releasesFor != null) {
        val target = releasesFor!!
        HostReleasesDialog(
            service = target,
            onActivate = { viewModel.activateHostedRelease(target.id, it) },
            onDelete = { viewModel.deleteHostedRelease(target.id, it) },
            onDismiss = { releasesFor = null },
        )
    }

    if (envFor != null) {
        val target = envFor!!
        HostEnvDialog(
            service = target,
            onSave = { env, build, npmInstall, npmBuild ->
                viewModel.saveHostedEnvironment(
                    target.id,
                    env,
                    build,
                    npmInstall,
                    npmBuild,
                )
            },
            onDismiss = { envFor = null },
        )
    }

    if (publishFor != null) {
        val target = publishFor!!
        val spec = target.spec
        if (spec != null) {
            LinkEditorDialog(
                initial = TunnelLink(
                    id = "",
                    name = target.name,
                    target = target.target,
                    kind = spec.linkKind,
                    mode = if (spec.web) TunnelLinks.MODE_QUICK else TunnelLinks.MODE_NAMED,
                ),
                blockedPorts = setOf(gatewayPort, TunnelPorts.publicPort(gatewayPort)),
                ftpHostPort = ftpSettings.port,
                ftpHostEnabled = ftpSettings.enabled,
                detected = null,
                scanning = false,
                onScanServices = {},
                onDismiss = { publishFor = null },
                onSave = { link ->
                    viewModel.saveTunnelLink(link)
                    publishFor = null
                },
            )
        }
    }

    if (deleteFor != null) {
        val target = deleteFor!!
        ConfirmDialog(
            title = "Delete ${target.name}?",
            message = "The runtime, its releases, database files and logs are removed from this " +
                "device. This cannot be undone.",
            onConfirm = {
                viewModel.deleteHostedService(target.id)
                deleteFor = null
            },
            onDismiss = { deleteFor = null },
        )
    }
}

/* ------------------------------------------------------------------ */
/* Overview                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun OverviewTab(
    services: List<HostedService>,
    states: Map<String, HostServiceState>,
    availability: Map<String, Boolean>,
    gatewayPort: Int,
    ftpRunning: Boolean,
    onNew: () -> Unit,
    onTab: (String) -> Unit,
    onManage: (HostedService) -> Unit,
) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<Map<String, LongArray>>(emptyMap()) }
    var freeBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(services) {
        val result = withContext(Dispatchers.IO) {
            services.associate { service ->
                val layout = HostingManager.layout(context, service)
                service.id to longArrayOf(
                    HostStorage.usage(layout.content),
                    HostStorage.usage(layout.data),
                    HostStorage.usage(layout.logs),
                )
            } to HostStorage.freeBytes(context.filesDir)
        }
        usage = result.first
        freeBytes = result.second
    }

    val running = services.count { states[it.id]?.status == HostStatus.RUNNING }
    val sites = services.count { it.spec?.web == true }
    val databases = services.count { it.kind in HostingStore.DATABASE_KINDS }
    val usedBytes = usage.values.sumOf { it.sum() }
    val runtimes = HostedKinds.all.count { availability[it.id] == true }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Brand, BrandDeep)))
                    .padding(18.dp),
            ) {
                Column {
                    Text(
                        "ON-DEVICE HOSTING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (services.isEmpty()) "Your server, in your pocket" else "$running of ${services.size} services live",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Immutable releases, domains, databases, FTP and TLS — all served from " +
                            "127.0.0.1 with the runtimes bundled in this app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = onNew,
                            shape = RoundedCornerShape(50),
                            color = Color.White,
                        ) {
                            Text(
                                "New website",
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = BrandDeep,
                            )
                        }
                        Surface(
                            onClick = { onTab("sites") },
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                "Manage sites",
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostMetricTile("Sites", sites.toString(), "web apps", Brand, Modifier.weight(1f))
                HostMetricTile("Running", running.toString(), "processes", Emerald, Modifier.weight(1f))
                HostMetricTile("Databases", databases.toString(), "servers", Amber, Modifier.weight(1f))
            }
        }

        item {
            HostingSection("Quick actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostingChoice(
                        title = "New site",
                        subtitle = "Static, PHP, Node",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = onNew,
                    )
                    HostingChoice(
                        title = "Databases",
                        subtitle = "MariaDB & Postgres",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { onTab("databases") },
                    )
                    HostingChoice(
                        title = "FTP & TLS",
                        subtitle = "Accounts, certs",
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { onTab("ftp") },
                    )
                }
            }
        }

        item {
            HostingSection(
                "Your sites",
                subtitle = if (services.isEmpty()) null else "${services.size} configured",
                trailing = {
                    TextButton(onClick = { onTab("sites") }) {
                        Text("All", color = Brand, style = MaterialTheme.typography.labelMedium)
                    }
                },
            ) {
                if (services.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Storage,
                        title = "No sites yet",
                        body = "Create a static, PHP, Node.js or Python site and deploy it from a " +
                            "ZIP or a folder on this device.",
                        action = { PrimaryButton("Create a site", onClick = onNew, icon = Icons.Filled.Add) },
                    )
                } else {
                    services.take(3).forEachIndexed { index, service ->
                        if (index > 0) {
                            Spacer(Modifier.height(8.dp))
                            HairLine()
                            Spacer(Modifier.height(8.dp))
                        }
                        CompactSiteRow(
                            service = service,
                            state = states[service.id] ?: HostServiceState(port = service.port),
                            onClick = { onManage(service) },
                        )
                    }
                }
            }
        }

        item {
            HostingSection("This device") {
                MetaRow("Gateway", "127.0.0.1:$gatewayPort")
                MetaRow(
                    "Public listener",
                    if (ftpRunning) "127.0.0.1:${TunnelPorts.publicPort(gatewayPort)} · FTP on" else
                        "127.0.0.1:${TunnelPorts.publicPort(gatewayPort)}",
                )
                MetaRow("Used by hosting", HostStorage.formatBytes(usedBytes))
                MetaRow("Free storage", HostStorage.formatBytes(freeBytes))
                MetaRow(
                    "Runtimes",
                    if (runtimes == 0) "None detected" else "${runtimes} of ${HostedKinds.all.size} available",
                    if (runtimes == 0) Rose else Emerald,
                )
                MetaRow("Databases", if (databases == 0) "No database service" else "$databases service(s)")
            }
        }

        item {
            val recent = services.flatMap { service ->
                (states[service.id]?.log ?: emptyList()).takeLast(3).map { "${service.name}: $it" }
            }.takeLast(8)
            HostingSection(
                "Activity",
                subtitle = if (recent.isEmpty()) "Runtime output appears here" else "Latest runtime output",
            ) {
                if (recent.isEmpty()) {
                    Text(
                        "Start a site to see its process output.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else {
                    CodeBlock(recent.joinToString("\n"))
                }
            }
        }
    }
}

@Composable
private fun CompactSiteRow(
    service: HostedService,
    state: HostServiceState,
    onClick: () -> Unit,
) {
    val color = statusColor(state)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteGlyph(service.kind, 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(service.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(
                "${runtimeLabel(service.kind)} · 127.0.0.1:${effectivePort(service, state)}" +
                    (service.domain.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HostPill(statusLabel(state), color)
        TextButton(onClick = onClick) {
            Text("Manage", color = Brand, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Websites                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun SitesTab(
    services: List<HostedService>,
    states: Map<String, HostServiceState>,
    onNew: () -> Unit,
    onManage: (HostedService) -> Unit,
    onToggle: (HostedService) -> Unit,
    onOpen: (HostedService) -> Unit,
    onFiles: (HostedService) -> Unit,
    onDeploy: (HostedService) -> Unit,
    onFolder: (HostedService) -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    val visible = services.filter {
        when (filter) {
            "running" -> states[it.id]?.status == HostStatus.RUNNING
            "stopped" -> states[it.id]?.status != HostStatus.RUNNING
            else -> true
        }
    }.sortedWith(compareByDescending<HostedService> { it.spec?.web == true }.thenBy { it.name })

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Websites", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "Deploy, start and route every site from one place",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                PrimaryButton("New", onClick = onNew, icon = Icons.Filled.Add)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("all" to "All", "running" to "Running", "stopped" to "Stopped").forEach { (id, label) ->
                    HostingChoice(
                        title = label,
                        subtitle = when (id) {
                            "all" -> "${services.size}"
                            "running" -> "${services.count { states[it.id]?.status == HostStatus.RUNNING }}"
                            else -> "${services.count { states[it.id]?.status != HostStatus.RUNNING }}"
                        },
                        selected = filter == id,
                        onClick = { filter = id },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Storage,
                    title = if (services.isEmpty()) "Create your first site" else "Nothing here",
                    body = if (services.isEmpty()) {
                        "Pick a runtime, choose a name and the app allocates a free port, " +
                            "writes a starter page and starts it."
                    } else {
                        "No site matches this filter."
                    },
                    action = if (services.isEmpty()) {
                        { PrimaryButton("New website", onClick = onNew, icon = Icons.Filled.Add) }
                    } else {
                        null
                    },
                )
            }
        }

        items(visible, key = { it.id }) { service ->
            SiteCard(
                service = service,
                state = states[service.id] ?: HostServiceState(port = service.port),
                onManage = { onManage(service) },
                onToggle = { onToggle(service) },
                onOpen = { onOpen(service) },
                onFiles = { onFiles(service) },
                onDeploy = { onDeploy(service) },
                onFolder = { onFolder(service) },
            )
        }
    }
}

@Composable
private fun SiteCard(
    service: HostedService,
    state: HostServiceState,
    onManage: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onFiles: () -> Unit,
    onDeploy: () -> Unit,
    onFolder: () -> Unit,
) {
    val running = state.status == HostStatus.RUNNING
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SiteGlyph(service.kind)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        service.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    HostPill(statusLabel(state), statusColor(state))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${runtimeLabel(service.kind)} · port ${effectivePort(service, state)}" +
                        if (service.entry.isNotBlank()) " · ${service.entry}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(38.dp)) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (running) "Stop ${service.name}" else "Start ${service.name}",
                    tint = if (running) Rose else Emerald,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (service.spec?.web == true) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "http://127.0.0.1:${effectivePort(service, state)}/",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (running) Brand else TextMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onOpen, enabled = running, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = "Open site",
                        tint = if (running) TextSecondary else TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (service.domain.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = Brand, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    service.domain,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Brand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HairLine()
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onManage) {
                Text("Manage", color = Brand, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onFiles) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("Files", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            if (service.spec?.web == true) {
                TextButton(onClick = onDeploy) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("ZIP", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onFolder) {
                    Text("Folder", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Site management dialog                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun SiteManageDialog(
    service: HostedService,
    state: HostServiceState,
    services: List<HostedService>,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onFiles: () -> Unit,
    onReleases: () -> Unit,
    onEnvironment: () -> Unit,
    onDeploy: () -> Unit,
    onFolder: () -> Unit,
    onDomain: () -> Unit,
    onPort: () -> Unit,
    onPublish: () -> Unit,
    onAttachDatabase: () -> Unit,
    onCertificate: () -> Unit,
    onDeleteCertificate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val running = state.status == HostStatus.RUNNING
    val database = services.firstOrNull { it.kind == "mysql" }
    val certificate = remember(service.id, state.status) {
        ai.ninerouter.core.SiteTls.read(
            ai.ninerouter.core.SiteTls.certFile(HostingManager.layout(context, service)),
        )
    }
    val npmManifest = remember(service.id, service.active, service.entry) {
        if (service.kind == "node") {
            NpmRuntime.manifest(HostingManager.layout(context, service).content)
        } else {
            null
        }
    }
    val dsn = if (database != null && service.dbName.isNotBlank()) {
        ai.ninerouter.core.SiteDatabases.url(
            "127.0.0.1",
            database.port,
            service.dbName,
            database.username,
            database.password,
        )
    } else {
        ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteGlyph(service.kind, 32)
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(service.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        "${runtimeLabel(service.kind)} · ${statusLabel(state)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(state),
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = if (running) "Stop" else "Start",
                        onClick = onToggle,
                        icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "Open",
                        onClick = onOpen,
                        enabled = running && service.spec?.web == true,
                        icon = Icons.Filled.OpenInNew,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(10.dp))
                HostValueRow(
                    label = "Local URL",
                    value = if (service.spec?.web == true) {
                        "http://127.0.0.1:${effectivePort(service, state)}/"
                    } else {
                        "${service.spec?.scheme ?: "tcp"}://127.0.0.1:${effectivePort(service, state)}"
                    },
                )
                HostValueRow(label = "Entrypoint", value = service.entry.ifBlank {
                    if (service.kind == "static") "index.html" else "default"
                })
                if (service.kind == "node") {
                    HostValueRow(
                        label = "npm",
                        value = NpmRuntime.VERSION + " · " + when {
                            npmManifest?.usesNpmStart == true && service.entry.isBlank() -> "npm start"
                            npmManifest?.usesNpmBuild == true && service.npmBuild -> "node + build"
                            else -> "node"
                        },
                    )
                }
                HostValueRow(
                    label = "Active release",
                    value = service.releases.firstOrNull { it.id == service.active }?.id ?: "folder deploy",
                )

                Spacer(Modifier.height(6.dp))
                HairLine()
                Spacer(Modifier.height(6.dp))
                Text("Deployments", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton("ZIP", onDeploy, Modifier.weight(1f), icon = Icons.Filled.UploadFile)
                    GhostButton("Folder", onFolder, Modifier.weight(1f), icon = Icons.Filled.Folder)
                    GhostButton("History", onReleases, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton("Files", onFiles, Modifier.weight(1f))
                    GhostButton("Environment", onEnvironment, Modifier.weight(1f))
                }

                Spacer(Modifier.height(10.dp))
                HairLine()
                Spacer(Modifier.height(6.dp))
                Text("Domain & routing", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    service.domain.ifBlank { "No hostname assigned" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (service.domain.isBlank()) TextMuted else Brand,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton(
                        if (service.domain.isBlank()) "Assign hostname" else "Change hostname",
                        onDomain,
                        Modifier.weight(1f),
                        icon = Icons.Filled.Public,
                    )
                    GhostButton("Port · ${service.port}", onPort, Modifier.weight(1f), icon = Icons.Filled.Edit)
                }
                GhostButton(
                    "Publish through tunnel",
                    onPublish,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Cloud,
                )

                Spacer(Modifier.height(10.dp))
                HairLine()
                Spacer(Modifier.height(6.dp))
                Text("Database", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                if (database == null) {
                    Text(
                        "Add a MySQL service in the Databases tab to attach a schema.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else if (service.dbName.isBlank()) {
                    Text(
                        "No schema yet. The app creates one named after this site and injects " +
                            "DB_HOST, DB_NAME, DB_USER, DB_PASSWORD and DATABASE_URL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        "Create & attach database",
                        onAttachDatabase,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Filled.Storage,
                        enabled = database.password.isNotEmpty(),
                    )
                } else {
                    Text(
                        service.dbName,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Emerald,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dsn,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(dsn)) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy database URL",
                                tint = TextMuted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HairLine()
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Brand, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("TLS", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                }
                Spacer(Modifier.height(4.dp))
                if (certificate == null) {
                    Text(
                        "No certificate. Generate a self-signed pair for the site hostname; " +
                            "Node/Static apps can read TLS_CERT and TLS_KEY.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        "Generate certificate",
                        onCertificate,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Filled.Lock,
                    )
                } else {
                    Text(
                        "${certificate.subject.substringAfter("CN=", "certificate")} · " +
                            "${certificate.daysLeft} days left",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (certificate.expired) Rose else Emerald,
                    )
                    Text(
                        certificate.sha256,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GhostButton("Renew", onCertificate, Modifier.weight(1f))
                        GhostButton("Remove", onDeleteCertificate, Modifier.weight(1f), color = Rose)
                    }
                }

                Spacer(Modifier.height(10.dp))
                HairLine()
                Spacer(Modifier.height(6.dp))
                Text("Recent output", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                CodeBlock(
                    state.log.takeLast(8).joinToString("\n").ifBlank { "No output yet." },
                )

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Rose, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete site", color = Rose, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Brand) }
        },
    )
}

/* ------------------------------------------------------------------ */
/* Dialogs                                                             */
/* ------------------------------------------------------------------ */

@Composable
private fun NewSiteDialog(
    availability: Map<String, Boolean>,
    onDismiss: () -> Unit,
    onCreate: (kind: String, name: String, domain: String, entry: String, autoStart: Boolean) -> Unit,
) {
    val options = listOf(
        Triple("static", "Static site", "HTML, CSS, JS"),
        Triple("php", "PHP", "index.php"),
        Triple("node", "Node.js", "server.js"),
        Triple("python", "Python", "http.server"),
    )
    var kind by remember { mutableStateOf("static") }
    var name by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var autoStart by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("New website") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "The app allocates a free loopback port, writes a starter page and can start " +
                        "the site right away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.take(2).forEach { (id, title, subtitle) ->
                        HostingChoice(
                            title = title,
                            subtitle = if (availability[id] == false) "runtime missing" else subtitle,
                            selected = kind == id,
                            accent = runtimeColor(id),
                            modifier = Modifier.weight(1f),
                            onClick = { kind = id; error = null },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.drop(2).forEach { (id, title, subtitle) ->
                        HostingChoice(
                            title = title,
                            subtitle = if (availability[id] == false) "needs Termux" else subtitle,
                            selected = kind == id,
                            accent = runtimeColor(id),
                            modifier = Modifier.weight(1f),
                            onClick = { kind = id; error = null },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Site name",
                    placeholder = "My portfolio",
                    singleLine = true,
                    supportingText = "Shown in the dashboard and used for the folder name.",
                )
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = domain,
                    onValueChange = { domain = it.trim().lowercase() },
                    label = "Hostname (optional)",
                    placeholder = "shop.localhost",
                    singleLine = true,
                    supportingText = "Uses *.localhost for local previews, or a real hostname you " +
                        "route through the tunnel.",
                )
                if (kind == "node" || kind == "python") {
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = entry,
                        onValueChange = { entry = it },
                        label = "Entrypoint (optional)",
                        placeholder = if (kind == "node") "server.js" else "app.py",
                        singleLine = true,
                        supportingText = "Relative to the site folder. Leave blank to detect it.",
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start now", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Start the runtime as soon as it is created.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { autoStart = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Brand,
                            checkedBorderColor = Brand,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = AppSurfaceHigh,
                            uncheckedBorderColor = AppBorder,
                        ),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        error = "Give the site a name."
                    } else {
                        onCreate(kind, name.trim(), domain.trim(), entry.trim(), autoStart)
                    }
                },
            ) {
                Text("Create", color = Brand, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}

@Composable
private fun DomainDialog(
    service: HostedService,
    publicPort: Int,
    links: List<TunnelLink>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(service.id) { mutableStateOf(service.domain) }
    val routed = links.any { it.host.equals(value.trim(), true) && it.enabled }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Hostname for ${service.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppTextField(
                    value = value,
                    onValueChange = { value = it.trim().lowercase() },
                    label = "Hostname",
                    placeholder = "shop.localhost",
                    singleLine = true,
                    supportingText = "A named local link is created automatically, so the hostname " +
                        "routes to this site once the gateway is running.",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Local preview: http://${value.ifBlank { "name.localhost" }}:$publicPort/",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (routed) Emerald else TextMuted,
                )
                Text(
                    "Real domains also need the hostname added to your Cloudflare tunnel.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim().lowercase()) }) {
                Text("Save", color = Brand, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}

@Composable
private fun PortDialog(
    service: HostedService,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var value by remember(service.id) { mutableStateOf(service.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Loopback port") },
        text = {
            Column {
                AppTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(5) },
                    label = "Port",
                    placeholder = "8080",
                    singleLine = true,
                    supportingText = if (service.kind == "node") {
                        "Passed as PORT; the app's real listening port is detected."
                    } else {
                        "Between 1024 and 65535, free on 127.0.0.1."
                    },
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = value.toIntOrNull()
                    if (port == null || port !in 1024..65535) {
                        error = "Enter a port between 1024 and 65535."
                    } else {
                        onSave(port)
                    }
                },
            ) {
                Text("Save", color = Brand, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}
