package ai.ninerouter.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.HostedKind
import ai.ninerouter.core.HostedService
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.Sky
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import ai.ninerouter.ui.theme.Violet
import kotlinx.coroutines.delay

/** Tabs of the hosting console, in display order. */
internal val HostingTabRows = listOf(
    "overview" to "Overview",
    "sites" to "Websites",
    "databases" to "Databases",
    "ftp" to "FTP",
    "ssl" to "SSL",
    "storage" to "Storage",
)

@Composable
internal fun HostingTabBar(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        HostingTabRows.forEach { (id, label) ->
            val active = id == selected
            val color by animateColorAsState(
                targetValue = if (active) Brand else AppSurfaceHigh,
                animationSpec = tween(180),
                label = "tab-$id",
            )
            val content by animateColorAsState(
                targetValue = if (active) Color.White else TextSecondary,
                animationSpec = tween(180),
                label = "tab-text-$id",
            )
            Surface(
                onClick = { onSelect(id) },
                shape = RoundedCornerShape(50),
                color = color,
                border = if (active) null else BorderStroke(1.dp, AppBorder),
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}

internal fun runtimeColor(kind: String): Color = when (kind) {
    "static" -> Sky
    "php" -> Violet
    "node" -> Emerald
    "python" -> Amber
    "mysql" -> Sky
    "postgres" -> Brand
    "redis" -> Rose
    else -> TextMuted
}

internal fun runtimeLabel(kind: String): String =
    HostedKindLabel[kind] ?: kind.replaceFirstChar { it.uppercase() }

private val HostedKindLabel = mapOf(
    "static" to "Static",
    "php" to "PHP",
    "node" to "Node.js",
    "python" to "Python",
    "mysql" to "MariaDB",
    "postgres" to "PostgreSQL",
    "redis" to "Redis",
)

internal fun statusColor(state: HostServiceState): Color = when (state.status) {
    HostStatus.RUNNING -> Emerald
    HostStatus.STARTING -> Amber
    HostStatus.ERROR -> Rose
    HostStatus.STOPPED -> TextMuted
}

internal fun statusLabel(state: HostServiceState): String = when (state.status) {
    HostStatus.RUNNING -> "Running"
    HostStatus.STARTING -> "Starting"
    HostStatus.ERROR -> "Error"
    HostStatus.STOPPED -> "Stopped"
}

/** Effective port: the runtime's reported port once it is running. */
internal fun effectivePort(service: HostedService, state: HostServiceState): Int =
    state.port.takeIf { it in 1..65535 } ?: service.port

@Composable
internal fun HostMetricTile(
    label: String,
    value: String,
    hint: String? = null,
    accent: Color = Brand,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AppSurface,
        border = BorderStroke(1.dp, AppBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HostingSection(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        Column(content = content)
    }
}

/** Rounded selectable option used by wizards and the theme picker. */
@Composable
internal fun HostingChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Brand,
    onClick: () -> Unit,
) {
    val border by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.55f) else AppBorder,
        animationSpec = tween(160),
        label = "choice-border",
    )
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (selected) accent.copy(alpha = 0.12f) else AppSurfaceHigh,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) accent else TextPrimary,
            )
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

/** Label + monospace value with copy (and optional reveal for secrets). */
@Composable
internal fun HostValueRow(
    label: String,
    value: String,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    var revealed by remember(value) { mutableStateOf(!secret) }
    androidx.compose.runtime.LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(
                if (revealed) value.ifBlank { "—" } else "•".repeat(12),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (secret) {
            IconButton(
                onClick = { revealed = !revealed },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide value" else "Reveal value",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(value))
                copied = true
            },
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "Copy $label",
                tint = if (copied) Emerald else TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Bullet chip such as a runtime or a status, aligned inside a row. */
@Composable
internal fun HostPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Thin usage bar for storage rows. */
@Composable
internal fun HostUsageBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "usage",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(AppSurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

@Composable
internal fun SiteGlyph(kind: String, size: Int = 40) {
    val color = runtimeColor(kind)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            runtimeLabel(kind).take(2),
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
    }
}

@Composable
internal fun Dot(color: Color, size: Int = 7) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}
