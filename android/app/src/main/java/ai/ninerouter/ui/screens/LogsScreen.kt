package ai.ninerouter.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.ConfirmDialog
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.RequestRow
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.formatClock
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

private enum class LogFilter { ALL, SUCCESS, ERRORS }

@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val requests by viewModel.requests.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = requests.filter { detail ->
        val matchesQuery = query.isBlank() ||
            detail.model?.contains(query, ignoreCase = true) == true ||
            detail.provider?.contains(query, ignoreCase = true) == true ||
            detail.error?.contains(query, ignoreCase = true) == true ||
            detail.connectionName?.contains(query, ignoreCase = true) == true
        val matchesFilter = when (filter) {
            LogFilter.ALL -> true
            LogFilter.SUCCESS -> (detail.status ?: 0) in 200..299
            LogFilter.ERRORS -> (detail.status ?: 0) >= 400
        }
        matchesQuery && matchesFilter
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Console Log",
                subtitle = "${requests.size} stored requests · ${filtered.size} shown",
                trailing = {
                    TextButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Rose, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", color = Rose, style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
        item {
            AppTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search model, provider, error…",
                trailingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
                }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogChip("All", filter == LogFilter.ALL) { filter = LogFilter.ALL }
                LogChip("Success", filter == LogFilter.SUCCESS) { filter = LogFilter.SUCCESS }
                LogChip("Errors", filter == LogFilter.ERRORS) { filter = LogFilter.ERRORS }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Article,
                    title = "Log is empty",
                    body = "Every gateway request is stored with its status, latency, token usage and upstream error."
                )
            }
        } else {
            items(filtered, key = { it.id }) { detail ->
                LogCard(
                    detail = detail,
                    expanded = expanded == detail.id,
                    onToggle = { expanded = if (expanded == detail.id) null else detail.id }
                )
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
                confirmClear = false
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun LogChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
private fun LogCard(detail: RequestDetail, expanded: Boolean, onToggle: () -> Unit) {
    AppCard(onClick = onToggle, padding = 12.dp) {
        RequestRow(detail)
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            HairLine()
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatClock(detail.timestamp)} · ${detail.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "connection · ${detail.connectionName ?: detail.connectionId ?: "none"}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.error != null) {
                Spacer(Modifier.height(8.dp))
                Text("Error", style = MaterialTheme.typography.titleSmall, color = Rose)
                Spacer(Modifier.height(3.dp))
                Text(detail.error, style = MaterialTheme.typography.bodySmall, color = Rose)
            }
            if (detail.requestBody != null) {
                Spacer(Modifier.height(8.dp))
                PayloadBlock("Request", detail.requestBody)
            }
            if (detail.responseBody != null) {
                Spacer(Modifier.height(8.dp))
                PayloadBlock("Response", detail.responseBody)
            }
        } else {
            Text(
                "tap for payload",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun PayloadBlock(label: String, body: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    Spacer(Modifier.height(3.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = androidx.compose.ui.graphics.Color(0xFF0C0C10),
        border = BorderStroke(1.dp, AppBorder)
    ) {
        Text(
            body.take(3000),
            modifier = Modifier.padding(9.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp
            ),
            color = Color(0xFFD4D4D8)
        )
    }
}
