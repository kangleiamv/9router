package ai.ninerouter.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.TunnelLink
import ai.ninerouter.core.TunnelLinkDraft
import ai.ninerouter.core.TunnelLinks
import ai.ninerouter.core.TunnelPorts
import ai.ninerouter.core.TunnelState
import ai.ninerouter.core.TunnelStatus
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.CodeBlock
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.MetaRow
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Obsidian
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Cloudflare Tunnel control surface. Quick tunnels need no account and return a
 * fresh trycloudflare.com URL; named tunnels use a Zero Trust token plus the
 * public hostname configured in the dashboard. Local links map extra public
 * paths or hostnames to loopback services running on the same device.
 */
@Composable
fun TunnelScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val tunnel by viewModel.tunnelState.collectAsState()
    val linkTunnels by viewModel.linkTunnels.collectAsState()
    val port by viewModel.port.collectAsState()
    val keys by viewModel.keys.collectAsState()

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var mode by remember { mutableStateOf("quick") }
    var hostname by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingLink by remember { mutableStateOf<TunnelLink?>(null) }
    val links = remember(settings[TunnelLinks.SETTINGS_KEY]) {
        TunnelLinks.decode(settings[TunnelLinks.SETTINGS_KEY])
    }

    LaunchedEffect(Unit) { viewModel.refreshSettings() }
    LaunchedEffect(settings) {
        if (!loaded && settings.isNotEmpty()) {
            mode = settings["tunnel_mode"] ?: "quick"
            hostname = settings["tunnel_hostname"].orEmpty()
            loaded = true
        }
    }
    LaunchedEffect(tunnel.status) {
        if (tunnel.status == TunnelStatus.ERROR) showLog = true
    }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1600)
            copied = null
        }
    }

    val activeKey = keys.firstOrNull { it.isActive }?.key.orEmpty()
    val publicRoot = tunnel.publicUrl.trimEnd('/')
    val publicBase = if (publicRoot.isBlank()) "" else "$publicRoot/v1"
    val statusColor = when (tunnel.status) {
        TunnelStatus.RUNNING -> Emerald
        TunnelStatus.STARTING -> Amber
        TunnelStatus.ERROR -> Rose
        TunnelStatus.STOPPED -> TextMuted
    }
    val statusText = when (tunnel.status) {
        TunnelStatus.RUNNING -> "Live"
        TunnelStatus.STARTING -> "Connecting"
        TunnelStatus.ERROR -> "Error"
        TunnelStatus.STOPPED -> "Off"
    }
    val savedToken = settings["tunnel_token"].orEmpty().isNotBlank()

    fun copyValue(value: String) {
        clipboard.setText(AnnotatedString(value))
        copied = value
    }

    fun openUrl(value: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
        ) {
            item {
                SectionHeader(
                    title = "Cloudflare Tunnel",
                    subtitle = "Publish the API and local apps through one tunnel",
                    trailing = { StatusPill(statusText, statusColor) },
                )
            }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brand.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Cloud, contentDescription = null, tint = Brand, modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Public tunnel", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                when {
                                    tunnel.status == TunnelStatus.RUNNING && tunnel.publicUrl.isNotBlank() -> "Cloudflare edge is forwarding to this device"
                                    tunnel.status == TunnelStatus.RUNNING -> "Connected · public hostname not shown"
                                    tunnel.status == TunnelStatus.STARTING -> "Starting cloudflared…"
                                    tunnel.status == TunnelStatus.ERROR -> "Connector stopped with an error"
                                    else -> "Gateway is reachable on loopback only"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                    }

                    if (tunnel.status == TunnelStatus.ERROR) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tunnel.message.ifBlank { "cloudflared stopped." },
                            style = MaterialTheme.typography.bodySmall,
                            color = Rose,
                        )
                    }

                    if (tunnel.status == TunnelStatus.RUNNING && publicBase.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Obsidian.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Column {
                                Text("PUBLIC BASE URL", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    publicBase,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Brand,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { copyValue(publicBase) }) {
                                Icon(
                                    if (copied == publicBase) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    tint = if (copied == publicBase) Emerald else Brand,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (copied == publicBase) "Copied" else "Copy base URL",
                                    color = if (copied == publicBase) Emerald else Brand,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            TextButton(onClick = { openUrl(tunnel.publicUrl) }) {
                                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (activeKey.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Key · ${activeKey.take(10)}…${activeKey.takeLast(4)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = TextSecondary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = { copyValue(activeKey) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        if (copied == activeKey) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                        contentDescription = "Copy API key",
                                        tint = if (copied == activeKey) Emerald else TextSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Clients must send  Authorization: Bearer <API key>  on chat requests.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }

                    if (tunnel.status == TunnelStatus.RUNNING && tunnel.publicUrl.isBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "cloudflared is connected. Add a public hostname to this tunnel in " +
                                "Cloudflare Zero Trust → Networks → Tunnels, then paste it below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(10.dp))
                    MetaRow("Local gateway", "http://127.0.0.1:$port/v1")
                    MetaRow("Tunnel listener", "127.0.0.1:${TunnelPorts.publicPort(port)}")
                    MetaRow("Exposed surface", "API plus ${links.count { it.enabled }} local link(s)")
                    MetaRow(
                        "Starts with gateway",
                        if (settings["tunnel_enabled"] == "true") "Enabled" else "Disabled",
                    )
                    Spacer(Modifier.height(12.dp))
                    when (tunnel.status) {
                        TunnelStatus.STARTING -> {
                            PrimaryButton(
                                text = "Stop",
                                onClick = { viewModel.stopTunnel() },
                                icon = Icons.Filled.Stop,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TunnelStatus.RUNNING -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton(
                                    text = "Stop tunnel",
                                    onClick = { viewModel.stopTunnel() },
                                    icon = Icons.Filled.Stop,
                                    modifier = Modifier.weight(1f),
                                )
                                GhostButton(
                                    text = "Restart",
                                    onClick = { viewModel.startTunnel(mode, token, hostname) },
                                    icon = Icons.Filled.Refresh,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        else -> {
                            PrimaryButton(
                                text = if (tunnel.status == TunnelStatus.ERROR) "Retry tunnel" else "Start tunnel",
                                onClick = { viewModel.startTunnel(mode, token, hostname) },
                                icon = Icons.Filled.PlayArrow,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                AppCard {
                    Text("Mode", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This mode publishes the API gateway. Quick links always get their own connector and URL; " +
                            "named links use the shared named tunnel configured here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChoice(
                            title = "Quick tunnel",
                            subtitle = "No account",
                            selected = mode == "quick",
                            modifier = Modifier.weight(1f),
                            onClick = { mode = "quick" },
                        )
                        ModeChoice(
                            title = "Named tunnel",
                            subtitle = "Zero Trust token",
                            selected = mode == "token",
                            modifier = Modifier.weight(1f),
                            onClick = { mode = "token" },
                        )
                    }
                    if (mode == "token") {
                        Spacer(Modifier.height(12.dp))
                        AppTextField(
                            value = token,
                            onValueChange = { token = it.trim() },
                            label = "Tunnel token",
                            placeholder = if (savedToken) "Saved token — leave blank to keep" else "eyJhIjoi…",
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            supportingText = if (savedToken) {
                                "A token is stored encrypted on this device. Leave blank to reuse it."
                            } else {
                                "Cloudflare Zero Trust → Networks → Tunnels → Install connector."
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        AppTextField(
                            value = hostname,
                            onValueChange = { hostname = it.trim() },
                            label = "Public hostname",
                            placeholder = "router.example.com",
                            singleLine = true,
                            supportingText = "The hostname routed to this tunnel in the Zero Trust dashboard. Optional, used for the displayed URL.",
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    GhostButton(
                        text = "Save & restart tunnel",
                        onClick = { viewModel.startTunnel(mode, token, hostname) },
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Local links", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                "Publish other apps on this device. Each link chooses Quick or Named.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        TextButton(onClick = { editingLink = null; editorOpen = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Brand, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Add", color = Brand, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (links.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "No local links yet. Add a service such as http://127.0.0.1:8080. " +
                                "Quick links get their own trycloudflare.com URL; named links use a path or hostname on the shared tunnel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    } else {
                        links.forEachIndexed { index, link ->
                            if (index > 0) {
                                Spacer(Modifier.height(10.dp))
                                HairLine()
                                Spacer(Modifier.height(10.dp))
                            } else {
                                Spacer(Modifier.height(12.dp))
                            }
                            TunnelLinkRow(
                                link = link,
                                publicUrl = if (TunnelLinks.isQuick(link)) {
                                    linkTunnels[link.id]?.publicUrl.orEmpty()
                                } else {
                                    TunnelLinks.publicUrl(
                                        tunnel.publicUrl,
                                        settings["tunnel_mode"] ?: "quick",
                                        link,
                                    )
                                },
                                quickState = linkTunnels[link.id],
                                gatewayMode = settings["tunnel_mode"] ?: "quick",
                                gatewayRunning = tunnel.status == TunnelStatus.RUNNING,
                                copied = copied,
                                onCopy = { copyValue(it) },
                                onOpen = { openUrl(it) },
                                onToggle = { enabled -> viewModel.setTunnelLinkEnabled(link.id, enabled) },
                                onEdit = { editingLink = link; editorOpen = true },
                                onDelete = { viewModel.deleteTunnelLink(link.id) },
                                onRetry = { viewModel.restartTunnelLink(link.id) },
                            )
                        }
                    }

                    val ingress = TunnelLinks.ingressSummary(links, TunnelPorts.publicPort(port))
                    if ((settings["tunnel_mode"] ?: "quick") == "token" && ingress.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        HairLine()
                        Spacer(Modifier.height(10.dp))
                        Text("Named tunnel hostnames", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Add each hostname to this tunnel in Zero Trust → Networks → Tunnels and point its " +
                                "service at the local router:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        CodeBlock(ingress)
                    }
                }
            }

            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Diagnostics", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                if (tunnel.logTail.isEmpty()) "No cloudflared output yet." else "${tunnel.logTail.size} log lines buffered",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        TextButton(onClick = { showLog = !showLog }) {
                            Text(if (showLog) "Hide" else "Show", color = Brand, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (showLog) {
                        Spacer(Modifier.height(8.dp))
                        CodeBlock(
                            text = tunnel.logTail.takeLast(14).joinToString("\n").ifBlank { "Waiting for cloudflared…" },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Showing the last 14 lines. The notification mirrors the connector state.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }

            item {
                AppCard {
                    Text("Security", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Only the OpenAI-compatible /v1 routes and the local links you add are published. Connection management, settings, keys and logs stay on 127.0.0.1. " +
                            "Anyone with the URL and an active 9Router API key can spend your provider quota, so rotate the key if the URL leaks. " +
                            "Local links can expose a service without its own authentication; publish only what you intend to share.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connector: cloudflared 2026.11.0 · Apache-2.0 · TLS terminated by Cloudflare. Quick tunnels are rate-limited by Cloudflare and the hostname changes on every start.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }

        if (editorOpen) {
            LinkEditorDialog(
                initial = editingLink,
                blockedPorts = setOf(port, TunnelPorts.publicPort(port)),
                onDismiss = { editorOpen = false },
                onSave = { link ->
                    viewModel.saveTunnelLink(link)
                    editorOpen = false
                },
            )
        }
    }
}

@Composable
private fun ModeChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Brand.copy(alpha = 0.14f) else AppSurfaceHigh,
        border = BorderStroke(1.dp, if (selected) Brand.copy(alpha = 0.45f) else AppBorder),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) Brand else TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

@Composable
private fun TunnelLinkRow(
    link: TunnelLink,
    publicUrl: String,
    quickState: TunnelState?,
    gatewayMode: String,
    gatewayRunning: Boolean,
    copied: String?,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val quick = TunnelLinks.isQuick(link)
    val pillColor = if (!quick) {
        Brand
    } else {
        when (quickState?.status) {
            TunnelStatus.RUNNING -> Emerald
            TunnelStatus.STARTING -> Amber
            TunnelStatus.ERROR -> Rose
            else -> TextMuted
        }
    }
    val scope = buildString {
        if (!quick) {
            if (link.path.isNotBlank()) append(link.path)
            if (link.host.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(link.host)
            }
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(link.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(if (quick) "Quick" else "Named", pillColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (quick) "dedicated trycloudflare.com URL" else scope.ifBlank { "root page" },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (quick) TextMuted else Brand,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = link.enabled,
                onCheckedChange = onToggle,
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
        Spacer(Modifier.height(5.dp))
        Text(
            link.target,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (link.enabled) {
            when {
                quick -> when (quickState?.status) {
                    TunnelStatus.RUNNING -> LinkUrlBlock(publicUrl, copied, onCopy, onOpen)
                    TunnelStatus.STARTING -> LinkHint("Starting the dedicated quick tunnel…", Amber)
                    TunnelStatus.ERROR -> {
                        LinkHint(quickState.message.ifBlank { "Quick tunnel failed." }, Rose)
                    }
                    else -> LinkHint("Start the tunnel to connect this quick link.", TextMuted)
                }

                gatewayMode != "token" -> LinkHint(
                    "Named links use the shared Named tunnel. Switch Mode to Named and add this hostname there.",
                    Amber,
                )

                !gatewayRunning -> LinkHint("Waiting for the named tunnel…", TextMuted)
                publicUrl.isNotBlank() -> LinkUrlBlock(publicUrl, copied, onCopy, onOpen)
                else -> LinkHint("Add this hostname to the named tunnel in Zero Trust.", Amber)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Rose, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = Rose, style = MaterialTheme.typography.labelMedium)
            }
            if (quick && quickState?.status == TunnelStatus.ERROR) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Amber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry", color = Amber, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun LinkHint(text: String, color: Color) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun LinkUrlBlock(
    url: String,
    copied: String?,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (url.isBlank()) return
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Brand,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = { onCopy(url) }, modifier = Modifier.size(30.dp)) {
            Icon(
                if (copied == url) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "Copy link",
                tint = if (copied == url) Emerald else TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = { onOpen(url) }, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = "Open link",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun LinkEditorDialog(
    initial: TunnelLink?,
    blockedPorts: Set<Int>,
    onDismiss: () -> Unit,
    onSave: (TunnelLink) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var target by remember(initial?.id) { mutableStateOf(initial?.target.orEmpty()) }
    var path by remember(initial?.id) { mutableStateOf(initial?.path.orEmpty()) }
    var host by remember(initial?.id) { mutableStateOf(initial?.host.orEmpty()) }
    var linkMode by remember(initial?.id) {
        mutableStateOf(initial?.let { TunnelLinks.modeOf(it) } ?: TunnelLinks.MODE_QUICK)
    }
    var stripPrefix by remember(initial?.id) { mutableStateOf(initial?.stripPrefix ?: true) }
    var error by remember(initial?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(if (initial == null) "Add local link" else "Edit local link") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Forward a loopback service through the tunnel. Quick links run their own connector and get a " +
                        "unique trycloudflare.com URL; named links use the shared Zero Trust tunnel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChoice(
                        title = "Quick link",
                        subtitle = "Own trycloudflare URL",
                        selected = linkMode == TunnelLinks.MODE_QUICK,
                        modifier = Modifier.weight(1f),
                        onClick = { linkMode = TunnelLinks.MODE_QUICK },
                    )
                    ModeChoice(
                        title = "Named link",
                        subtitle = "Shared tunnel",
                        selected = linkMode == TunnelLinks.MODE_NAMED,
                        modifier = Modifier.weight(1f),
                        onClick = { linkMode = TunnelLinks.MODE_NAMED },
                    )
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    placeholder = "My website",
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = "Local URL",
                    placeholder = "http://127.0.0.1:8080",
                    singleLine = true,
                    supportingText = "Loopback only: localhost, 127.0.0.1 or [::1].",
                )
                if (linkMode == TunnelLinks.MODE_NAMED) {
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = "Public path",
                        placeholder = "/site",
                        singleLine = true,
                        supportingText = "Optional on the main named hostname. Leave blank for hostname-only links.",
                    )
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = "Public hostname",
                        placeholder = "site.example.com",
                        singleLine = true,
                        supportingText = "Point this hostname at the local tunnel router in Zero Trust.",
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Strip public path", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text(
                                "Forward /site/page as /page to the local app.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = stripPrefix,
                            onCheckedChange = { stripPrefix = it },
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
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The public URL is generated when the tunnel starts. Quick links need no path or domain.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    TunnelLinks.fromDraft(
                        TunnelLinkDraft(
                            name = name,
                            host = host,
                            path = path,
                            target = target,
                            stripPrefix = stripPrefix,
                            enabled = initial?.enabled ?: true,
                            mode = linkMode,
                        ),
                        existingId = initial?.id,
                        blockedPorts = blockedPorts,
                    )
                }.onSuccess(onSave).onFailure { error = it.message ?: "Invalid local link" }
            }) {
                Text("Save", color = Brand)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}
