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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.ProviderUsage
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.engine.FallbackPolicy
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderQuota
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.QuotaWindow
import ai.ninerouter.engine.specificData
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AnimatedBar
import ai.ninerouter.ui.components.AnimatedBarChart
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.MiniSparkline
import ai.ninerouter.ui.components.ProviderAvatar
import ai.ninerouter.ui.components.QuotaGauge
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatTile
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.components.formatCount
import ai.ninerouter.ui.components.formatRelative
import ai.ninerouter.ui.components.providerColor
import ai.ninerouter.ui.components.roundPercent
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.Sky
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

@Composable
fun QuotaScreen(viewModel: MainViewModel) {
    val connections by viewModel.connections.collectAsState()
    val customSpecs by viewModel.customSpecs.collectAsState()
    val usage by viewModel.providerUsage.collectAsState()
    val requests by viewModel.requests.collectAsState()
    val quotas by viewModel.quotas.collectAsState()
    val quotaBusy by viewModel.quotaBusy.collectAsState()
    val refreshAllAt by viewModel.quotaRefreshAllAt.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val latestConnections by rememberUpdatedState(connections)
    LaunchedEffect(connections.map { it.id }) {
        while (true) {
            val snapshot = latestConnections
            if (snapshot.isNotEmpty()) viewModel.refreshQuotas(snapshot)
            delay(120_000)
        }
    }

    val locked = connections.mapNotNull { connection ->
        val locks = connection.specificData().filterKeys {
            it.startsWith(FallbackPolicy.MODEL_LOCK_PREFIX)
        }.mapNotNull { (key, value) ->
            val until = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            if (until <= now) null
            else key.removePrefix(FallbackPolicy.MODEL_LOCK_PREFIX) to until
        }
        if (locks.isEmpty() && !FallbackPolicy.isUnavailable(connection)) null
        else connection to locks
    }

    val liveConnections = connections.count { quotas[it.id]?.hasWindows == true }
    val allWindows = quotas.values.flatMap { it.windows }
    val tightest = allWindows.minByOrNull { it.remainingPercent }
    val tightestOwner = tightest?.let { window ->
        quotas.values.firstOrNull { it.windows.contains(window) }
    }
    val refreshing = quotaBusy.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Quota Tracker",
                subtitle = "Live provider limits, cooldowns and model locks"
            ) {
                GhostButton(
                    text = if (refreshing) "Refreshing" else "Refresh all",
                    onClick = { viewModel.refreshQuotas(connections, force = true) },
                    enabled = !refreshing && connections.isNotEmpty(),
                    icon = Icons.Filled.Refresh
                )
            }
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tightest != null) {
                        QuotaGauge(
                            remainingPercent = tightest.remainingPercent.toFloat(),
                            color = quotaColor(tightest.remainingPercent),
                            size = 96.dp
                        )
                    } else {
                        Box(
                            Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(AppSurfaceHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Brand
                                )
                            } else {
                                Icon(
                                    Icons.Filled.DataUsage,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (tightest != null) "Tightest window" else "Live quota",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tightest?.name ?: if (refreshing) "Fetching provider limits…" else "Waiting for quota data",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            tightestOwner?.let { quota ->
                                "${connectionLabel(connections, quota.connectionId)} · ${quota.plan ?: "live quota"}"
                            } ?: "$liveConnections of ${connections.size} connections reporting live limits",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tightest?.resetAt != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "resets ${formatReset(tightest.resetAt, now)}",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = quotaColor(tightest.remainingPercent)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (refreshAllAt == 0L) "Not refreshed yet"
                            else "Updated ${formatRelative(refreshAllAt)} · auto every 2 min",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Live providers",
                    "$liveConnections/${connections.size}",
                    "with quota data",
                    if (liveConnections == connections.size && connections.isNotEmpty()) Emerald else Brand,
                    Modifier.weight(1f)
                )
                StatTile(
                    "Windows",
                    allWindows.size.toString(),
                    "tracked limits",
                    if (allWindows.isEmpty()) TextMuted else Sky,
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Cooling down",
                    locked.size.toString(),
                    "connections",
                    if (locked.isEmpty()) Emerald else Amber,
                    Modifier.weight(1f)
                )
                StatTile(
                    "Model locks",
                    locked.sumOf { it.second.size }.toString(),
                    "active",
                    if (locked.isEmpty()) Emerald else Rose,
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Tokens today",
                    formatCount(usage.sumOf { it.tokens }),
                    "all providers",
                    Brand,
                    Modifier.weight(1f)
                )
                StatTile(
                    "Errors today",
                    formatCount(usage.sumOf { it.errors }),
                    "4xx + 5xx",
                    if (usage.sumOf { it.errors } == 0) Emerald else Rose,
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Provider quotas",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${connections.size} connections",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        if (connections.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.DataUsage,
                    title = "No connections yet",
                    body = "Add a provider connection and its live quota, local usage and rotation state will appear here."
                )
            }
        } else {
            items(connections, key = { it.id }) { connection ->
                QuotaConnectionCard(
                    connection = connection,
                    spec = customSpecs.firstOrNull { it.id == connection.provider }
                        ?: ProviderCatalog.byId(connection.provider),
                    quota = quotas[connection.id],
                    busy = quotaBusy.contains(connection.id),
                    now = now,
                    requests = requests,
                    locks = locked.firstOrNull { it.first.id == connection.id }?.second.orEmpty(),
                    onRefresh = { viewModel.refreshQuota(connection) }
                )
            }
        }
        item {
            AppCard {
                SectionHeader(
                    title = "Activity",
                    subtitle = "Last 7 days and today by provider"
                )
                Spacer(Modifier.height(12.dp))
                SevenDayChart(requests)
                Spacer(Modifier.height(16.dp))
                HairLine()
                Spacer(Modifier.height(12.dp))
                if (usage.isEmpty()) {
                    Text("No traffic yet today.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    val max = usage.maxOf { it.requests }.coerceAtLeast(1)
                    usage.forEach { item ->
                        ProviderUsageRow(item, max)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cooldowns & locks",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (locked.isNotEmpty()) {
                    GhostButton(
                        text = "Clear all",
                        onClick = { viewModel.clearCooldowns() },
                        icon = Icons.Filled.Refresh
                    )
                }
            }
        }
        if (locked.isEmpty()) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Emerald.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.DataUsage,
                                contentDescription = null,
                                tint = Emerald,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("No active cooldowns", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text(
                                "Every connection is eligible for rotation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        } else {
            items(locked, key = { it.first.id }) { entry ->
                val (connection, locks) = entry
                CooldownCard(
                    connection = connection,
                    spec = customSpecs.firstOrNull { it.id == connection.provider }
                        ?: ProviderCatalog.byId(connection.provider),
                    locks = locks,
                    now = now,
                    onClear = { viewModel.clearCooldowns() }
                )
            }
        }
        item {
            AppCard {
                Text("How cooldowns work", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "401/402/403 lock a connection for 2 minutes. 429 and quota wording use exponential backoff " +
                        "(2s base, 5min cap, level 15 max) and lock only the affected model. 400/404 are request-scoped: " +
                        "the credential stays healthy and the combo moves to its next member.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Quota cards are read directly from each provider's usage API. Providers without a public usage " +
                        "endpoint still appear with local request volume, status and cooldown information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun QuotaConnectionCard(
    connection: ProviderConnection,
    spec: ProviderSpec?,
    quota: ProviderQuota?,
    busy: Boolean,
    now: Long,
    requests: List<RequestDetail>,
    locks: List<Pair<String, Long>>,
    onRefresh: () -> Unit
) {
    val today = remember(requests, connection.id) {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        requests.filter { it.connectionId == connection.id && it.timestamp >= start }
    }
    val tokens = today.sumOf { ((it.promptTokens ?: 0) + (it.completionTokens ?: 0)).toLong() }
    val errors = today.count { (it.status ?: 0) >= 400 }
    val series = remember(requests, connection.id) { dailySeries(requests, connection.id) }
    val hasWindows = quota?.hasWindows == true
    val tightest = quota?.windows?.minByOrNull { it.remainingPercent }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(spec, label = connection.provider, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    connection.displayName ?: connection.name ?: connection.provider,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spec?.name ?: connection.provider,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!connection.isActive) {
                        Text(" · paused", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    if (quota?.plan != null) {
                        Text(
                            " · ${quota.plan}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Brand
                )
                Spacer(Modifier.width(6.dp))
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh ${spec?.name ?: connection.provider} quota",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (locks.isNotEmpty() || FallbackPolicy.isUnavailable(connection)) {
                StatusPill("Locked", Amber)
            } else if (tightest != null) {
                StatusPill(
                    "${roundPercent(tightest.remainingPercent)}% left",
                    quotaColor(tightest.remainingPercent)
                )
            }
        }
        if (hasWindows) {
            Spacer(Modifier.height(12.dp))
            quota!!.windows.forEachIndexed { index, window ->
                QuotaWindowRow(window, now)
                if (index != quota.windows.lastIndex) Spacer(Modifier.height(10.dp))
            }
            if (quota.message != null) {
                Spacer(Modifier.height(8.dp))
                Text(quota.message, style = MaterialTheme.typography.labelSmall, color = Amber)
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (busy) Brand else TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        busy -> "Fetching live quota…"
                        quota == null -> "Live quota loads automatically. Tap refresh to retry."
                        quota.message != null -> quota.message!!
                        else -> "No live quota endpoint for this provider. Local usage still tracks its rotation health."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quota?.message != null && !busy) Amber else TextMuted
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HairLine()
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${today.size} req · ${formatCount(tokens)} tok today",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    if (errors > 0) "$errors errors · ${if (locks.isEmpty()) "healthy rotation" else "${locks.size} model locks"}"
                    else if (locks.isEmpty()) "No active locks" else "${locks.size} model locks",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (errors > 0 || locks.isNotEmpty()) Rose else TextMuted
                )
            }
            MiniSparkline(
                values = series,
                color = providerColor(connection.provider),
                modifier = Modifier.width(86.dp),
                height = 26.dp
            )
        }
    }
}

@Composable
private fun QuotaWindowRow(window: QuotaWindow, now: Long) {
    val color = quotaColor(window.remainingPercent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            window.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (window.unlimited) "unlimited" else "${roundPercent(window.remainingPercent)}%",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = color
        )
    }
    Spacer(Modifier.height(5.dp))
    AnimatedBar(
        progress = if (window.unlimited) 1f else (window.remainingPercent / 100.0).toFloat(),
        color = color,
        height = 7.dp
    )
    Spacer(Modifier.height(3.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                window.unlimited && window.unit != null ->
                    "${window.unit} ${formatAmount(window.used)} used · no cap"
                window.unlimited -> "no hard limit reported"
                window.unit != null && window.credit ->
                    "${window.unit} ${formatAmount(window.total)} balance"
                window.unit != null ->
                    "${window.unit} ${formatAmount((window.total - window.used).coerceAtLeast(0.0))} left of " +
                        formatAmount(window.total)
                else -> "${roundPercent(window.usedPercent)}% used"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(Modifier.weight(1f))
        if (window.resetAt != null) {
            Text(
                formatReset(window.resetAt, now),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted
            )
        }
    }
}

@Composable
private fun SevenDayChart(requests: List<RequestDetail>) {
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
        val errors = requests.count { (it.status ?: 0) >= 400 }
        Text(
            if (errors == 0) "clean window" else "$errors errors in history",
            style = MaterialTheme.typography.labelSmall,
            color = if (errors == 0) Emerald else Rose
        )
    }
    Spacer(Modifier.height(8.dp))
    AnimatedBarChart(
        values = counts,
        labels = labels,
        color = Brand,
        height = 100.dp
    )
}

@Composable
private fun ProviderUsageRow(item: ProviderUsage, max: Int) {
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
        height = 6.dp
    )
    if (item.errors > 0) {
        Spacer(Modifier.height(3.dp))
        Text("${item.errors} errors", style = MaterialTheme.typography.labelSmall, color = Rose)
    }
}

@Composable
private fun CooldownCard(
    connection: ProviderConnection,
    spec: ProviderSpec?,
    locks: List<Pair<String, Long>>,
    now: Long,
    onClear: () -> Unit
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(spec, label = connection.provider, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    connection.displayName ?: connection.name ?: connection.provider,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(spec?.name ?: connection.provider, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            StatusPill("Cooling", Amber)
        }
        Spacer(Modifier.height(8.dp))
        if (locks.isEmpty()) {
            Text(
                "Connection-level cooldown · ${connection.lastError ?: "rate limited"}",
                style = MaterialTheme.typography.bodySmall,
                color = Amber
            )
        } else {
            locks.forEach { (model, until) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Amber))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (model == "__all") "all models" else ProviderCatalog.displayName(connection.provider, model),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${((until - now) / 1000).coerceAtLeast(0)}s",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Amber
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        GhostButton(text = "Clear", onClick = onClear, modifier = Modifier.fillMaxWidth())
    }
}

private fun quotaColor(percent: Double): androidx.compose.ui.graphics.Color = when {
    percent >= 50.0 -> Emerald
    percent >= 20.0 -> Amber
    else -> Rose
}

private fun connectionLabel(connections: List<ProviderConnection>, connectionId: String): String {
    val connection = connections.firstOrNull { it.id == connectionId }
    return connection?.displayName ?: connection?.name ?: connection?.provider ?: "connection"
}

private fun formatReset(resetAt: String, now: Long): String {
    val instant = runCatching { Instant.parse(resetAt) }.getOrNull() ?: return "soon"
    val diff = instant.toEpochMilli() - now
    if (diff <= 0) return "now"
    val minutes = ceil(diff / 60_000.0).toLong()
    return when {
        minutes < 60 -> "in ${minutes}m"
        minutes < 1_440 -> "in ${minutes / 60}h ${minutes % 60}m"
        else -> "in ${minutes / 1_440}d ${(minutes % 1_440) / 60}h"
    }
}

private fun formatAmount(value: Double): String =
    if (value >= 100.0) String.format(Locale.US, "%.0f", value)
    else String.format(Locale.US, "%.2f", value)

private fun dailySeries(requests: List<RequestDetail>, connectionId: String): List<Float> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return (6 downTo 0).map { offset ->
        val day = today.minusDays(offset.toLong())
        requests.count {
            it.connectionId == connectionId &&
                Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == day
        }.toFloat()
    }
}
