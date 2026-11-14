package ai.ninerouter.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.TunnelPorts
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
 * public hostname configured in the dashboard.
 */
@Composable
fun TunnelScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val tunnel by viewModel.tunnelState.collectAsState()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
    ) {
        item {
            SectionHeader(
                title = "Cloudflare Tunnel",
                subtitle = "Expose the local gateway through trycloudflare.com",
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
                MetaRow("Exposed surface", "/v1 chat + models only")
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
                    "Quick tunnels are anonymous and get a new URL every start. Named tunnels keep a stable hostname from your Cloudflare account.",
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
                    "Only the OpenAI-compatible /v1 routes are published. Connection management, settings, keys and logs stay on 127.0.0.1. " +
                        "Anyone with the URL and an active 9Router API key can spend your provider quota, so rotate the key if the URL leaks.",
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
