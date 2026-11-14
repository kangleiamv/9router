package ai.ninerouter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.data.model.RequestDetail
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.Obsidian
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Dense request feed row used by the dashboard, Usage and Console Log screens.
 * Leads with the provider logo and status dot, keeps errors visible, and puts
 * the status code in a readable badge.
 */
@Composable
fun RequestLogRow(
    detail: RequestDetail,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val color = statusColor(detail.status)
    val isError = (detail.status ?: 0) >= 400
    val modelName = detail.model?.let { model ->
        detail.provider?.let { provider -> ProviderCatalog.displayName(provider, model) } ?: model
    } ?: "unknown"
    val providerLabel = detail.provider
        ?.let { provider -> ProviderCatalog.byId(provider)?.name ?: provider }
        ?: "local"
    val tokens = (detail.promptTokens ?: 0) + (detail.completionTokens ?: 0)
    val meta = buildString {
        append(providerLabel)
        detail.connectionName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (tokens > 0) append(" · ").append(formatCount(tokens)).append(" tok")
    }
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isError) Rose.copy(alpha = 0.05f) else Color.Transparent)
            .then(clickModifier)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            ProviderAvatar(
                spec = ProviderCatalog.byId(detail.provider ?: ""),
                label = detail.provider ?: "local",
                size = 36.dp
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, Obsidian, CircleShape)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                modelName,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!detail.error.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    detail.error.replace('\n', ' ').take(90),
                    style = MaterialTheme.typography.labelSmall,
                    color = Rose,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(color.copy(alpha = 0.13f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    detail.status?.toString() ?: "—",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = color
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatLogTime(detail.timestamp)} · ${formatLatency(detail.latencyMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}

private val LogTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatLogTime(timestamp: Long): String =
    LogTimeFormatter.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppBorder.copy(alpha = 0.7f))
    )
}
