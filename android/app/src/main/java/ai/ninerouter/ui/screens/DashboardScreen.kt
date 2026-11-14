package ai.ninerouter.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.ProxyRuntime
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.MetaRow
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.RequestLogRow
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatTile
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.components.formatCount
import ai.ninerouter.ui.components.formatLatency
import ai.ninerouter.ui.components.roundPercent
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.BrandSoft
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Obsidian
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit
) {
    val stats by viewModel.stats.collectAsState()
    val connections by viewModel.connections.collectAsState()
    val combos by viewModel.combos.collectAsState()
    val keys by viewModel.keys.collectAsState()
    val requests by viewModel.requests.collectAsState()
    val running by viewModel.running.collectAsState()
    val port by viewModel.port.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val endpoint = "http://127.0.0.1:$port/v1"
    val activeKey = keys.firstOrNull { it.isActive }?.key.orEmpty()

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            GatewayCard(
                running = running,
                endpoint = endpoint,
                apiKey = activeKey,
                copied = copied,
                onCopy = {
                    clipboard.setText(AnnotatedString(activeKey))
                    copied = true
                },
                onToggle = { viewModel.toggleProxy(!running) },
                onRestart = { viewModel.restartProxy() }
            )
        }
        if (lastError != null) {
            item {
                AppCard {
                    Text("Gateway error", style = MaterialTheme.typography.titleSmall, color = Rose)
                    Spacer(Modifier.height(4.dp))
                    Text(lastError ?: "", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Requests today",
                    value = formatCount(stats.todayRequests),
                    hint = "${stats.totalConnections} connections",
                    accent = Brand,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Tokens",
                    value = formatCount(stats.todayTokens),
                    hint = "prompt + completion",
                    accent = Amber,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Success rate",
                    value = if (stats.todayRequests == 0) "—" else "${roundPercent(stats.successRate)}%",
                    hint = if (stats.lockedConnections > 0) "${stats.lockedConnections} cooling down" else "all healthy",
                    accent = if (stats.successRate >= 95 || stats.todayRequests == 0) Emerald else Amber,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Avg latency",
                    value = formatLatency(stats.avgLatencyMs.takeIf { it > 0 }),
                    hint = "last 24h",
                    accent = Emerald,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            AppCard {
                SectionHeader(
                    title = "Routing",
                    subtitle = "Combos, providers and credentials at a glance"
                )
                Spacer(Modifier.height(12.dp))
                MetaRow("Combos", "${combos.size} configured")
                MetaRow("Providers", "${connections.size} connections")
                MetaRow("API keys", "${keys.count { it.isActive }} active")
                MetaRow(
                    "Vision adapter",
                    combos.firstOrNull { it.kind == "vision" }?.name ?: "not configured"
                )
                Spacer(Modifier.height(14.dp))
                HairLine()
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(
                        text = "Add provider",
                        onClick = { onNavigate("providers") },
                        icon = Icons.Filled.Add,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(
                        text = "Combos",
                        onClick = { onNavigate("combos") },
                        icon = Icons.Filled.Layers,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = "Gateway settings",
                    onClick = { onNavigate("settings") },
                    icon = Icons.Filled.Settings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            RecentActivitySection(
                requests = requests,
                onOpenLog = { onNavigate("logs") }
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "9Router · native Android gateway · no WebView",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun RecentActivitySection(
    requests: List<RequestDetail>,
    onOpenLog: () -> Unit
) {
    val recent = remember(requests) { requests.take(6) }
    val errors = recent.count { (it.status ?: 0) >= 400 }
    val ok = recent.count { (it.status ?: 0) in 200..299 }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Recent activity",
            subtitle = when {
                recent.isEmpty() -> "No requests captured yet"
                errors == 1 -> "Latest ${recent.size} requests · 1 error"
                errors > 1 -> "Latest ${recent.size} requests · $errors errors"
                ok == recent.size -> "Latest ${recent.size} requests · all OK"
                else -> "Latest ${recent.size} requests"
            },
            trailing = {
                if (recent.isNotEmpty()) {
                    TextButton(onClick = onOpenLog) {
                        Text(
                            "View all",
                            color = Brand,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        )
        if (recent.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Dns,
                title = "No requests yet",
                body = "Send an OpenAI-compatible request to the local endpoint and it will show up here."
            )
        } else {
            Spacer(Modifier.height(6.dp))
            HairLine(Modifier.padding(horizontal = 8.dp))
            recent.forEachIndexed { index, detail ->
                RequestLogRow(
                    detail = detail,
                    onClick = onOpenLog
                )
                if (index != recent.lastIndex) {
                    HairLine(Modifier.padding(start = 55.dp, end = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun GatewayCard(
    running: Boolean,
    endpoint: String,
    apiKey: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(BrandSoft, AppSurface, Obsidian)
                )
            )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brand.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Brand, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Local gateway", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        if (running) "Serving on loopback" else "Stopped",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
                StatusPill(
                    text = if (running) "Running" else "Stopped",
                    color = if (running) Emerald else Rose
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Obsidian.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column {
                    Text("OPENAI BASE URL", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Brand,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (apiKey.isBlank()) "No API key yet" else "Key · ${apiKey.take(10)}…${apiKey.takeLast(4)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onCopy, enabled = apiKey.isNotBlank()) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = if (copied) Emerald else Brand,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "Copied" else "Copy", color = if (copied) Emerald else Brand, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = if (running) "Stop" else "Start gateway",
                    onClick = onToggle,
                    icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
                GhostButton(
                    text = "Restart",
                    onClick = onRestart,
                    modifier = Modifier.width(104.dp),
                    enabled = running
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The gateway binds to 127.0.0.1 only. Use adb forward or a local client on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}
