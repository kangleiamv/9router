package ai.ninerouter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.FtpHostStore
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.TunnelLink
import ai.ninerouter.core.TunnelLinks
import ai.ninerouter.core.TunnelPorts
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.TextMuted

/**
 * Hosting-only page: runtimes, deployments, releases and environment variables.
 * It is reachable from the navigation drawer and does not repeat the tunnel or
 * FTP panels, so the hosting surface has a single, focused home.
 */
@Composable
fun HostingScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val services by viewModel.hostedServices.collectAsState()
    val states by viewModel.hostingStates.collectAsState()
    val availability by viewModel.hostingAvailability.collectAsState()
    val detectedPorts by viewModel.detectedPorts.collectAsState()
    val scanningPorts by viewModel.scanningPorts.collectAsState()
    val port by viewModel.port.collectAsState()

    val ftpHostSettings = remember(settings[FtpHostStore.SETTINGS_KEY]) {
        FtpHostStore.decode(settings[FtpHostStore.SETTINGS_KEY])
    }

    var editorOpen by remember { mutableStateOf(false) }
    var editingLink by remember { mutableStateOf<TunnelLink?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshSettings()
        viewModel.refreshHostingAvailability()
    }

    val running = services.count { states[it.id]?.status == HostStatus.RUNNING }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
    ) {
        item {
            SectionHeader(
                title = "Web Hosting",
                subtitle = "Sites and runtimes served from this device",
                trailing = {
                    StatusPill(
                        text = if (running == 0) "Stopped" else "$running live",
                        color = if (running == 0) TextMuted else Emerald,
                    )
                },
            )
        }

        item {
            HostingCard(
                services = services,
                states = states,
                availability = availability,
                blockedPorts = buildSet {
                    add(port)
                    add(TunnelPorts.publicPort(port))
                    if (ftpHostSettings.enabled) add(ftpHostSettings.port)
                    addAll(FtpHostStore.passivePorts.toList())
                },
                onRefreshAvailability = { viewModel.refreshHostingAvailability() },
                onSave = { viewModel.saveHostedService(it) },
                onDelete = { viewModel.deleteHostedService(it) },
                onStart = { viewModel.startHostedService(it) },
                onStop = { viewModel.stopHostedService(it) },
                onAutoStart = { id, enabled -> viewModel.setHostedAutoStart(id, enabled) },
                onPublish = { service ->
                    val spec = service.spec
                    if (spec != null) {
                        editingLink = TunnelLink(
                            id = "",
                            name = service.name,
                            target = service.target,
                            kind = spec.linkKind,
                            mode = if (spec.web) TunnelLinks.MODE_QUICK else TunnelLinks.MODE_NAMED,
                        )
                        editorOpen = true
                    }
                },
                onDeploy = { service, uri -> viewModel.deployHostedService(service.id, uri) },
                onDeployFolder = { service, uri ->
                    viewModel.deployHostedFolder(service.id, uri)
                },
                onActivateRelease = { id, release ->
                    viewModel.activateHostedRelease(id, release)
                },
                onDeleteRelease = { id, release ->
                    viewModel.deleteHostedRelease(id, release)
                },
                onSaveEnvironment = { id, env, build ->
                    viewModel.saveHostedEnvironment(id, env, build)
                },
            )
        }
    }

    if (editorOpen) {
        LinkEditorDialog(
            initial = editingLink,
            blockedPorts = setOf(port, TunnelPorts.publicPort(port)),
            ftpHostPort = ftpHostSettings.port,
            ftpHostEnabled = ftpHostSettings.enabled,
            detected = detectedPorts,
            scanning = scanningPorts,
            onScanServices = { viewModel.scanLocalServices() },
            onDismiss = { editorOpen = false },
            onSave = { link ->
                viewModel.saveTunnelLink(link)
                editorOpen = false
            },
        )
    }
}
