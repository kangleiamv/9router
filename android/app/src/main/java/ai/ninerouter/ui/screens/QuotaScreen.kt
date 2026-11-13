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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.engine.FallbackPolicy
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.specificData
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.ProviderAvatar
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatTile
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.components.formatCount
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun QuotaScreen(viewModel: MainViewModel) {
    val connections by viewModel.connections.collectAsState()
    val customSpecs by viewModel.customSpecs.collectAsState()
    val usage by viewModel.providerUsage.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Quota Tracker",
                subtitle = "Cooldowns, model locks and daily volume"
            )
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
                            Icon(Icons.Filled.DataUsage, contentDescription = null, tint = Emerald, modifier = Modifier.size(18.dp))
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
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Active cooldowns",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(
                        text = "Clear all",
                        onClick = { viewModel.clearCooldowns() },
                        icon = Icons.Filled.Refresh
                    )
                }
            }
            items(locked.size, key = { locked[it].first.id }) { index ->
                val (connection, locks) = locked[index]
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
                SectionHeader(title = "Daily volume", subtitle = "Requests routed in the last 24 hours")
                Spacer(Modifier.height(10.dp))
                if (usage.isEmpty()) {
                    Text("No traffic yet today.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    val max = usage.maxOf { it.requests }.coerceAtLeast(1)
                    usage.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    .background(Brand)
                            )
                        }
                        Spacer(Modifier.height(11.dp))
                    }
                }
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
            }
        }
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
