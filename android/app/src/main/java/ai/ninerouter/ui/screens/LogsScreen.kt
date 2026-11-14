package ai.ninerouter.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.ConfirmDialog
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.RequestDayHeader
import ai.ninerouter.ui.components.RequestFilterPill
import ai.ninerouter.ui.components.RequestLogRow
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.formatClock
import ai.ninerouter.ui.components.formatCount
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
import kotlinx.coroutines.delay

private enum class ConsoleFilter(val label: String) { ALL("All"), SUCCESS("OK"), ERRORS("Errors") }

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val requests by viewModel.requests.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf(ConsoleFilter.ALL) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }
    val filtered = remember(requests, filter, query) {
        val needle = query.trim()
        requests.filter { detail ->
            val matchesFilter = when (filter) {
                ConsoleFilter.ALL -> true
                ConsoleFilter.SUCCESS -> (detail.status ?: 0) in 200..299
                ConsoleFilter.ERRORS -> (detail.status ?: 0) >= 400
            }
            val matchesQuery = needle.isBlank() ||
                detail.model?.contains(needle, ignoreCase = true) == true ||
                detail.provider?.contains(needle, ignoreCase = true) == true ||
                detail.connectionName?.contains(needle, ignoreCase = true) == true ||
                detail.error?.contains(needle, ignoreCase = true) == true ||
                detail.status?.toString()?.contains(needle) == true ||
                detail.id.contains(needle, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }
    val successCount = requests.count { (it.status ?: 0) in 200..299 }
    val errorCount = requests.count { (it.status ?: 0) >= 400 }
    val searchVisible = searchOpen || query.isNotBlank()
    val dayStats = remember(filtered, zone) {
        filtered.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> rows.size to rows.count { (it.status ?: 0) >= 400 } }
    }

    LaunchedEffect(filter, query) { expanded = null }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        SectionHeader(
            title = "Console Log",
            subtitle = "${formatCount(requests.size)} stored requests",
            trailing = {
                IconButton(
                    onClick = { confirmClear = true },
                    enabled = requests.isNotEmpty(),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppSurfaceHigh)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Clear request log",
                        tint = if (requests.isEmpty()) TextMuted.copy(alpha = 0.4f) else Rose,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (query.isBlank()) "Request stream" else "Search results",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.width(7.dp))
                    LiveBadge()
                }
                Text(
                    when {
                        filtered.isEmpty() -> "No matching traffic"
                        filtered.size == requests.size -> "Tap a row to inspect payload"
                        else -> "${filtered.size} of ${requests.size} requests"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            IconButton(
                onClick = {
                    if (searchVisible) {
                        searchOpen = false
                        query = ""
                    } else {
                        searchOpen = true
                    }
                },
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
                selected = filter == ConsoleFilter.ALL,
                accent = Brand,
                onClick = { filter = ConsoleFilter.ALL },
                modifier = Modifier.weight(1f)
            )
            RequestFilterPill(
                label = "OK",
                count = successCount,
                selected = filter == ConsoleFilter.SUCCESS,
                accent = Emerald,
                onClick = { filter = ConsoleFilter.SUCCESS },
                modifier = Modifier.weight(1f)
            )
            RequestFilterPill(
                label = "Errors",
                count = errorCount,
                selected = filter == ConsoleFilter.ERRORS,
                accent = Rose,
                onClick = { filter = ConsoleFilter.ERRORS },
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
                ConsoleSearchField(query = query, onQueryChange = { query = it })
            }
        }
        Spacer(Modifier.height(6.dp))
        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.Article,
                title = if (requests.isEmpty()) "No requests captured" else "No matching entries",
                body = if (requests.isEmpty()) {
                    "The console fills as soon as the gateway handles traffic. Start the gateway or run the quick test in Endpoint & Key."
                } else {
                    "Try a different status filter or clear the search query."
                },
                action = if (query.isNotBlank() || filter != ConsoleFilter.ALL) {
                    {
                        GhostButton(
                            text = "Reset filters",
                            onClick = {
                                query = ""
                                filter = ConsoleFilter.ALL
                            }
                        )
                    }
                } else {
                    null
                }
            )
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(filtered, key = { _, detail -> detail.id }) { index, detail ->
                    val day = Instant.ofEpochMilli(detail.timestamp).atZone(zone).toLocalDate()
                    val previousDay = filtered.getOrNull(index - 1)
                        ?.let { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                    if (index == 0 || day != previousDay) {
                        val stats = dayStats[day] ?: (0 to 0)
                        RequestDayHeader(day = day, total = stats.first, errors = stats.second)
                    }
                    LogEntry(
                        detail = detail,
                        expanded = expanded == detail.id,
                        onToggle = {
                            expanded = if (expanded == detail.id) null else detail.id
                        }
                    )
                }
                item {
                    Text(
                        "Showing the latest ${formatCount(requests.size)} requests. Use Settings to prune older logs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 14.dp)
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear request log?",
            message = "This deletes all stored request metadata and payloads. Connections and keys are not affected.",
            confirmLabel = "Clear",
            onConfirm = {
                viewModel.clearLogs()
                expanded = null
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun LiveBadge() {
    val pulse = rememberInfiniteTransition(label = "console-live")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "console-live-dot"
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Emerald.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(Emerald.copy(alpha = dotAlpha))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = Emerald
        )
    }
}

@Composable
private fun ConsoleSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                "Model, provider, status, connection or error",
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
private fun LogEntry(
    detail: RequestDetail,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        RequestLogRow(detail = detail, onClick = onToggle)
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LogDetail(detail)
        }
    }
}

@Composable
private fun LogDetail(detail: RequestDetail) {
    val promptTokens = detail.promptTokens ?: 0
    val completionTokens = detail.completionTokens ?: 0
    val error = detail.error?.takeIf { it.isNotBlank() }
    val requestBody = detail.requestBody?.takeIf { it.isNotBlank() }
    val responseBody = detail.responseBody?.takeIf { it.isNotBlank() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, bottom = 12.dp)
    ) {
        HairLine(Modifier.padding(top = 2.dp, bottom = 8.dp))
        MetaLine("time", formatClock(detail.timestamp))
        MetaLine("id", detail.id)
        MetaLine("connection", detail.connectionName ?: detail.connectionId ?: "none")
        if (promptTokens + completionTokens > 0) {
            MetaLine(
                "tokens",
                "in ${formatCount(promptTokens)} · out ${formatCount(completionTokens)} · " +
                    "total ${formatCount(promptTokens + completionTokens)}"
            )
        }
        if (error != null) {
            Spacer(Modifier.height(9.dp))
            Text("Error", style = MaterialTheme.typography.titleSmall, color = Rose)
            Spacer(Modifier.height(3.dp))
            Text(
                error.take(2000),
                style = MaterialTheme.typography.bodySmall,
                color = Rose
            )
        }
        if (requestBody != null) {
            Spacer(Modifier.height(10.dp))
            PayloadBlock("Request", requestBody)
        }
        if (responseBody != null) {
            Spacer(Modifier.height(10.dp))
            PayloadBlock("Response", responseBody)
        }
        if (error == null && requestBody == null && responseBody == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No payload captured for this request.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.width(78.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PayloadBlock(label: String, body: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(body) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label · ${formatCount(body.length)} chars",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        if (copied) {
            Text(
                "Copied",
                style = MaterialTheme.typography.labelSmall,
                color = Emerald
            )
            Spacer(Modifier.width(3.dp))
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(body))
                copied = true
            },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "Copy $label body",
                tint = if (copied) Emerald else TextMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
    Spacer(Modifier.height(2.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0C0C10),
        border = BorderStroke(1.dp, AppBorder)
    ) {
        Text(
            body.take(4000),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            ),
            color = Color(0xFFD4D4D8),
            softWrap = false
        )
    }
}
