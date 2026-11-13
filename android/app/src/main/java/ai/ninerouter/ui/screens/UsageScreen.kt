package ai.ninerouter.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ninerouter.core.ProviderUsage
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.RequestRow
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatTile
import ai.ninerouter.ui.components.formatClock
import ai.ninerouter.ui.components.formatCount
import ai.ninerouter.ui.components.formatLatency
import ai.ninerouter.ui.components.formatRelative
import ai.ninerouter.ui.components.providerColor
import ai.ninerouter.ui.components.roundPercent
import ai.ninerouter.ui.components.statusColor
import ai.ninerouter.ui.components.statusLabel
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

private enum class UsageFilter { ALL, SUCCESS, ERRORS }

@Composable
fun UsageScreen(viewModel: MainViewModel) {
    val stats by viewModel.stats.collectAsState()
    val usage by viewModel.providerUsage.collectAsState()
    val requests by viewModel.requests.collectAsState()
    var filter by remember { mutableStateOf(UsageFilter.ALL) }
    var selected by remember { mutableStateOf<RequestDetail?>(null) }

    val filtered = when (filter) {
        UsageFilter.ALL -> requests
        UsageFilter.SUCCESS -> requests.filter { (it.status ?: 0) in 200..299 }
        UsageFilter.ERRORS -> requests.filter { (it.status ?: 0) >= 400 }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Usage",
                subtitle = "Request analytics from the on-device database"
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Requests",
                    formatCount(stats.todayRequests),
                    "today",
                    Brand,
                    Modifier.weight(1f)
                )
                StatTile(
                    "Tokens",
                    formatCount(stats.todayTokens),
                    "today",
                    Amber,
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Success",
                    if (stats.todayRequests == 0) "—" else "${roundPercent(stats.successRate)}%",
                    "${requests.count { (it.status ?: 0) >= 400 }} errors logged",
                    if (stats.successRate >= 95 || stats.todayRequests == 0) Emerald else Amber,
                    Modifier.weight(1f)
                )
                StatTile(
                    "Avg latency",
                    formatLatency(stats.avgLatencyMs.takeIf { it > 0 }),
                    "this session",
                    Emerald,
                    Modifier.weight(1f)
                )
            }
        }
        item {
            AppCard {
                SectionHeader(title = "Today by provider", subtitle = "Requests and token volume")
                Spacer(Modifier.height(10.dp))
                if (usage.isEmpty()) {
                    Text("No provider traffic yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    val max = usage.maxOf { it.requests }.coerceAtLeast(1)
                    usage.forEach { item ->
                        ProviderUsageBar(item, max)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Request log", style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                Text("${filtered.size} shown", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageChip("All", filter == UsageFilter.ALL) { filter = UsageFilter.ALL }
                UsageChip("Success", filter == UsageFilter.SUCCESS) { filter = UsageFilter.SUCCESS }
                UsageChip("Errors", filter == UsageFilter.ERRORS) { filter = UsageFilter.ERRORS }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = androidx.compose.material.icons.Icons.Filled.Article,
                    title = "Nothing here yet",
                    body = "Requests appear after the gateway handles traffic. Try the quick test in Endpoint & Key."
                )
            }
        } else {
            items(filtered, key = { it.id }) { detail ->
                AppCard(onClick = { selected = detail }, padding = 12.dp) {
                    RequestRow(detail)
                }
            }
        }
    }

    selected?.let { detail ->
        RequestDetailDialog(detail = detail, onDismiss = { selected = null })
    }
}

@Composable
private fun UsageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Brand.copy(alpha = 0.18f),
            selectedLabelColor = Brand,
            labelColor = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = AppBorder,
            selectedBorderColor = Brand.copy(alpha = 0.5f)
        )
    )
}

@Composable
private fun ProviderUsageBar(item: ProviderUsage, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(providerColor(item.providerId)))
        Spacer(Modifier.width(8.dp))
        Text(item.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(
            "${item.requests} req · ${formatCount(item.tokens)} tok",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
    Spacer(Modifier.height(5.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(50))
            .background(AppSurfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxWidth((item.requests.toFloat() / max).coerceIn(0.02f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(providerColor(item.providerId))
        )
    }
    if (item.errors > 0) {
        Spacer(Modifier.height(3.dp))
        Text("${item.errors} errors", style = MaterialTheme.typography.labelSmall, color = Rose)
    }
}

@Composable
private fun RequestDetailDialog(detail: RequestDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    detail.model ?: "request",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${detail.provider ?: "local"} · ${formatClock(detail.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        },
        text = {
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(statusColor(detail.status).copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "${detail.status ?: "—"} ${statusLabel(detail.status)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(detail.status)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatLatency(detail.latencyMs)} · ${formatCount((detail.promptTokens ?: 0) + (detail.completionTokens ?: 0))} tokens",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "id · ${detail.id}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted
                )
                Text(
                    "connection · ${detail.connectionName ?: detail.connectionId ?: "none"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted
                )
                if (detail.error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Error", style = MaterialTheme.typography.titleSmall, color = Rose)
                    Spacer(Modifier.height(4.dp))
                    Text(detail.error, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
                if (detail.requestBody != null) {
                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(8.dp))
                    Text("Request body", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail.requestBody.take(3500),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                        color = TextSecondary
                    )
                }
                if (detail.responseBody != null) {
                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(8.dp))
                    Text("Response body", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail.responseBody.take(3500),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Brand) }
        }
    )
}
