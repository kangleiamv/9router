package ai.ninerouter.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ninerouter.core.ProviderUsage
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AnimatedBar
import ai.ninerouter.ui.components.AnimatedBarChart
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.RequestDayHeader
import ai.ninerouter.ui.components.RequestFilterPill
import ai.ninerouter.ui.components.RequestLogRow
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
import ai.ninerouter.ui.theme.AppSurfaceHigh
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

private enum class UsageTab(val label: String) { OVERVIEW("Overview"), LOG("Request log") }

private enum class UsageFilter(val label: String) { ALL("All"), SUCCESS("OK"), ERRORS("Errors") }

@Composable
fun UsageScreen(viewModel: MainViewModel) {
    val stats by viewModel.stats.collectAsState()
    val usage by viewModel.providerUsage.collectAsState()
    val requests by viewModel.requests.collectAsState()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var filter by remember { mutableStateOf(UsageFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var logLimit by rememberSaveable { mutableStateOf(LOG_PAGE_SIZE) }
    var selected by remember { mutableStateOf<RequestDetail?>(null) }
    val tab = if (tabIndex == 0) UsageTab.OVERVIEW else UsageTab.LOG

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

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        SectionHeader(
            title = "Usage",
            subtitle = "Request analytics from the on-device database"
        )
        Spacer(Modifier.height(12.dp))
        UsageTabSwitcher(
            selected = tab,
            requestCount = requests.size,
            onSelect = { selectedTab -> tabIndex = selectedTab.ordinal }
        )
        when (tab) {
            UsageTab.OVERVIEW -> OverviewContent(
                stats = stats,
                usage = usage,
                requests = requests,
                errorCount = errorCount,
                modifier = Modifier.weight(1f)
            )

            UsageTab.LOG -> RequestLogContent(
                requests = requests,
                filtered = filtered,
                filter = filter,
                query = query,
                searchOpen = searchOpen,
                logLimit = logLimit,
                successCount = successCount,
                errorCount = errorCount,
                onFilterChange = { filter = it },
                onQueryChange = { query = it },
                onToggleSearch = {
                    if (searchOpen || query.isNotBlank()) {
                        searchOpen = false
                        query = ""
                    } else {
                        searchOpen = true
                    }
                },
                onLoadMore = { logLimit += LOG_PAGE_SIZE },
                onSelect = { selected = it },
                modifier = Modifier.weight(1f)
            )
        }
    }

    selected?.let { detail ->
        RequestDetailDialog(detail = detail, onDismiss = { selected = null })
    }
}

private const val LOG_PAGE_SIZE = 40

@Composable
private fun UsageTabSwitcher(
    selected: UsageTab,
    requestCount: Int,
    onSelect: (UsageTab) -> Unit
) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppSurface)
            .border(1.dp, AppBorder, shape)
            .padding(4.dp)
    ) {
        UsageTabButton(
            label = UsageTab.OVERVIEW.label,
            selected = selected == UsageTab.OVERVIEW,
            onClick = { onSelect(UsageTab.OVERVIEW) },
            modifier = Modifier.weight(1f)
        )
        UsageTabButton(
            label = UsageTab.LOG.label,
            selected = selected == UsageTab.LOG,
            count = requestCount,
            onClick = { onSelect(UsageTab.LOG) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UsageTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null
) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Brand else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else TextSecondary,
                maxLines = 1
            )
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White.copy(alpha = 0.20f) else AppSurfaceHigh)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        formatCount(count),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Color.White else TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewContent(
    stats: ai.ninerouter.core.DashboardStats,
    usage: List<ProviderUsage>,
    requests: List<RequestDetail>,
    errorCount: Int,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp)
    ) {
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
    }
}

@Composable
private fun RequestLogContent(
    requests: List<RequestDetail>,
    filtered: List<RequestDetail>,
    filter: UsageFilter,
    query: String,
    searchOpen: Boolean,
    logLimit: Int,
    successCount: Int,
    errorCount: Int,
    onFilterChange: (UsageFilter) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (RequestDetail) -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    val dayStats = remember(filtered, zone) {
        filtered.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> rows.size to rows.count { (it.status ?: 0) >= 400 } }
    }
    val searchVisible = searchOpen || query.isNotBlank()

    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (query.isBlank()) "Latest requests" else "Search results",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    when {
                        filtered.isEmpty() -> "No matching traffic"
                        filtered.size == requests.size -> "Tap a row to inspect the payload"
                        else -> "${filtered.size} of ${requests.size} requests"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (searchVisible) Brand.copy(alpha = 0.16f) else AppSurfaceHigh)
            ) {
                Icon(
                    if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchVisible) "Close search" else "Search requests",
                    tint = if (searchVisible) Brand else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RequestFilterPill(
                label = "All",
                count = requests.size,
                selected = filter == UsageFilter.ALL,
                accent = Brand,
                onClick = { onFilterChange(UsageFilter.ALL) },
                modifier = Modifier.weight(1f)
            )
            RequestFilterPill(
                label = "OK",
                count = successCount,
                selected = filter == UsageFilter.SUCCESS,
                accent = Emerald,
                onClick = { onFilterChange(UsageFilter.SUCCESS) },
                modifier = Modifier.weight(1f)
            )
            RequestFilterPill(
                label = "Errors",
                count = errorCount,
                selected = filter == UsageFilter.ERRORS,
                accent = Rose,
                onClick = { onFilterChange(UsageFilter.ERRORS) },
                modifier = Modifier.weight(1f)
            )
        }
        AnimatedVisibility(
            visible = searchVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(9.dp))
                RequestSearchField(
                    query = query,
                    onQueryChange = onQueryChange
                )
            }
        }
        Spacer(Modifier.height(4.dp))
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
            val visible = filtered.take(logLimit)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(visible, key = { _, detail -> detail.id }) { index, detail ->
                    val day = Instant.ofEpochMilli(detail.timestamp).atZone(zone).toLocalDate()
                    val previousDay = visible.getOrNull(index - 1)
                        ?.let { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                    if (index == 0 || day != previousDay) {
                        val stats = dayStats[day] ?: (0 to 0)
                        RequestDayHeader(day = day, total = stats.first, errors = stats.second)
                    }
                    RequestLogRow(detail = detail, onClick = { onSelect(detail) })
                }
                if (filtered.size > logLimit) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        GhostButton(
                            text = "Load ${minOf(LOG_PAGE_SIZE, filtered.size - logLimit)} more",
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${filtered.size - logLimit} remaining",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                "Search model, provider, connection or error",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChange("") }
                )
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = Brand,
            focusedBorderColor = Brand,
            unfocusedBorderColor = AppBorder,
            focusedContainerColor = AppSurface,
            unfocusedContainerColor = AppSurface,
            focusedPlaceholderColor = TextMuted,
            unfocusedPlaceholderColor = TextMuted
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
        Text(
            "Average latency",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.weight(1f)
        )
        Text(formatLatency(avg.takeIf { it > 0 }), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
    }
}

@Composable
private fun StatusBar(label: String, count: Int, total: Int, color: Color) {
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
                        "${formatLatency(detail.latencyMs)} · " +
                            "${formatCount((detail.promptTokens ?: 0) + (detail.completionTokens ?: 0))} tokens",
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        ),
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        ),
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
