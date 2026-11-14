package ai.ninerouter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ninerouter.core.ProviderUsage
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AnimatedBar
import ai.ninerouter.ui.components.AnimatedBarChart
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.RequestRow
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatTile
import ai.ninerouter.ui.components.formatClock
import ai.ninerouter.ui.components.formatCount
import ai.ninerouter.ui.components.formatLatency
import ai.ninerouter.ui.components.providerColor
import ai.ninerouter.ui.components.roundPercent
import ai.ninerouter.ui.components.statusColor
import ai.ninerouter.ui.components.statusLabel
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private enum class UsageFilter { ALL, SUCCESS, ERRORS }

@Composable
fun UsageScreen(viewModel: MainViewModel) {
    val stats by viewModel.stats.collectAsState()
    val usage by viewModel.providerUsage.collectAsState()
    val requests by viewModel.requests.collectAsState()
    var filter by remember { mutableStateOf(UsageFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var logLimit by remember { mutableStateOf(LOG_PAGE_SIZE) }
    var selected by remember { mutableStateOf<RequestDetail?>(null) }

    LaunchedEffect(filter, query) { logLimit = LOG_PAGE_SIZE }

    val filtered = remember(requests, filter, query) {
        requests.filter { detail ->
            val status = detail.status ?: 0
            val matchesFilter = when (filter) {
                UsageFilter.ALL -> true
                UsageFilter.SUCCESS -> status in 200..299
                UsageFilter.ERRORS -> status >= 400
            }
            val needle = query.trim()
            val matchesQuery = needle.isBlank() ||
                detail.model?.contains(needle, ignoreCase = true) == true ||
                detail.provider?.contains(needle, ignoreCase = true) == true ||
                detail.connectionName?.contains(needle, ignoreCase = true) == true ||
                detail.error?.contains(needle, ignoreCase = true) == true
            matchesFilter && matchesQuery
        }
    }
    val successCount = requests.count { (it.status ?: 0) in 200..299 }
    val errorCount = requests.count { (it.status ?: 0) >= 400 }

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
                    "$errorCount errors logged",
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
                SectionHeader(
                    title = "Last 7 days",
                    subtitle = "Request volume, newest day on the right"
                )
                Spacer(Modifier.height(12.dp))
                SevenDayCard(requests)
            }
        }
        item {
            AppCard {
                SectionHeader(
                    title = "Today by hour",
                    subtitle = "Three-hour buckets in your local timezone"
                )
                Spacer(Modifier.height(12.dp))
                HourlyCard(requests)
            }
        }
        item {
            AppCard {
                SectionHeader(title = "Today by provider", subtitle = "Requests and token volume")
                Spacer(Modifier.height(12.dp))
                if (usage.isEmpty()) {
                    Text("No provider traffic yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    val max = usage.maxOf { it.requests }.coerceAtLeast(1)
                    usage.forEach { item ->
                        ProviderUsageBar(item, max)
                        Spacer(Modifier.height(11.dp))
                    }
                }
            }
        }
        item {
            AppCard {
                SectionHeader(title = "Status mix", subtitle = "Distribution across the latest requests")
                Spacer(Modifier.height(12.dp))
                StatusMix(requests)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Request log",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${filtered.size} shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        item {
            AppCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UsageChip("All ${requests.size}", filter == UsageFilter.ALL) { filter = UsageFilter.ALL }
                        UsageChip("OK $successCount", filter == UsageFilter.SUCCESS) { filter = UsageFilter.SUCCESS }
                        UsageChip("Errors $errorCount", filter == UsageFilter.ERRORS) { filter = UsageFilter.ERRORS }
                    }
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Search",
                        placeholder = "Model, provider, connection or error",
                        trailingIcon = {
                            if (query.isBlank()) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                    tint = TextMuted,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { query = "" }
                                )
                            }
                        }
                    )
                }
                HairLine()
                if (filtered.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Article,
                        title = if (requests.isEmpty()) "Nothing here yet" else "No matching requests",
                        body = if (requests.isEmpty()) {
                            "Requests appear after the gateway handles traffic. Try the quick test in Endpoint & Key."
                        } else {
                            "Adjust the status filter or clear the search query."
                        }
                    )
                } else {
                    filtered.take(logLimit).forEachIndexed { index, detail ->
                        if (index > 0) HairLine(Modifier.padding(horizontal = 14.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = detail }
                                .padding(horizontal = 14.dp)
                        ) {
                            RequestRow(detail)
                        }
                    }
                    if (filtered.size > logLimit) {
                        HairLine()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Showing ${minOf(logLimit, filtered.size)} of ${filtered.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { logLimit += LOG_PAGE_SIZE }
                            ) {
                                Text(
                                    "Show ${minOf(LOG_PAGE_SIZE, filtered.size - logLimit)} more",
                                    color = Brand
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { detail ->
        RequestDetailDialog(detail = detail, onDismiss = { selected = null })
    }
}

private const val LOG_PAGE_SIZE = 40

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
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${item.requests} req · ${formatCount(item.tokens)} tok",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
    Spacer(Modifier.height(5.dp))
    AnimatedBar(
        progress = item.requests.toFloat() / max,
        color = providerColor(item.providerId),
        height = 7.dp
    )
    if (item.errors > 0) {
        Spacer(Modifier.height(3.dp))
        Text("${item.errors} errors", style = MaterialTheme.typography.labelSmall, color = Rose)
    }
}

@Composable
private fun SevenDayCard(requests: List<RequestDetail>) {
    val zone = ZoneId.systemDefault()
    val days = remember { (6 downTo 0).map { LocalDate.now(zone).minusDays(it.toLong()) } }
    val counts = remember(requests) {
        days.map { day ->
            requests.count {
                Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == day
            }.toFloat()
        }
    }
    val labels = remember { days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${counts.sum().toInt()} requests",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.weight(1f))
        val peakDay = days.getOrNull(counts.indexOf(counts.maxOrNull() ?: 0f))
        Text(
            "peak ${peakDay?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "—"}",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
    Spacer(Modifier.height(8.dp))
    AnimatedBarChart(
        values = counts,
        labels = labels,
        color = Brand,
        height = 112.dp
    )
}

@Composable
private fun HourlyCard(requests: List<RequestDetail>) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val buckets = remember(requests) {
        val values = MutableList(8) { 0f }
        requests.forEach { detail ->
            val time = Instant.ofEpochMilli(detail.timestamp).atZone(zone)
            if (time.toLocalDate() == today) {
                values[(time.hour / 3).coerceIn(0, 7)] += 1f
            }
        }
        values
    }
    val labels = listOf("00", "03", "06", "09", "12", "15", "18", "21")
    val peakIndex = buckets.indices.maxByOrNull { buckets[it] } ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${buckets.sum().toInt()} requests today",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(Modifier.weight(1f))
        Text(
            "busiest ${labels[peakIndex]}:00",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
    Spacer(Modifier.height(8.dp))
    AnimatedBarChart(
        values = buckets,
        labels = labels,
        color = Amber,
        height = 92.dp
    )
}

@Composable
private fun StatusMix(requests: List<RequestDetail>) {
    val total = requests.size
    val success = requests.count { (it.status ?: 0) in 200..299 }
    val clientErrors = requests.count { (it.status ?: 0) in 400..499 }
    val serverErrors = requests.count { (it.status ?: 0) >= 500 }
    val avg = if (total == 0) 0L else requests.sumOf { it.latencyMs ?: 0 } / total
    StatusBar("Success", success, total, Emerald)
    Spacer(Modifier.height(9.dp))
    StatusBar("Client errors", clientErrors, total, Amber)
    Spacer(Modifier.height(9.dp))
    StatusBar("Server errors", serverErrors, total, Rose)
    Spacer(Modifier.height(11.dp))
    HairLine()
    Spacer(Modifier.height(9.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Average latency", style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.weight(1f))
        Text(formatLatency(avg.takeIf { it > 0 }), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
    }
}

@Composable
private fun StatusBar(label: String, count: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(
            if (total == 0) "0" else "$count · ${roundPercent(count * 100.0 / total)}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (count == 0) TextMuted else color
        )
    }
    Spacer(Modifier.height(5.dp))
    AnimatedBar(
        progress = if (total == 0) 0f else count.toFloat() / total,
        color = color,
        height = 6.dp
    )
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
