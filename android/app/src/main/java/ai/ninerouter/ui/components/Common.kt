package ai.ninerouter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = base,
            shape = MaterialTheme.shapes.medium,
            color = AppSurface,
            border = BorderStroke(1.dp, AppBorder)
        ) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(
            modifier = base,
            shape = MaterialTheme.shapes.medium,
            color = AppSurface,
            border = BorderStroke(1.dp, AppBorder)
        ) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.32f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun StatTile(label: String, value: String, hint: String? = null, accent: Color = Brand, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AppSurface,
        border = BorderStroke(1.dp, AppBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(label.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = TextPrimary, maxLines = 1)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1)
            }
        }
    }
}

/** Registry ids that use a differently named logo asset. */
private val logoAliases = mapOf(
    "opencode-zen" to "opencode",
    "ollama-cloud" to "ollama",
    "ollama" to "ollama-local",
    "google-gemini" to "gemini",
    "nvidia-nim" to "nvidia"
)

private object ProviderLogoCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(96)

    suspend fun load(context: android.content.Context, providerId: String): ImageBitmap? {
        val key = logoAliases[providerId.lowercase()] ?: providerId.lowercase()
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                context.assets.open("providers/$key.png").use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }
    }
}

@Composable
fun ProviderAvatar(providerId: String, size: Dp = 40.dp) {
    val spec = ProviderCatalog.byId(providerId)
    ProviderAvatar(spec = spec, label = providerId, size = size)
}

@Composable
fun ProviderAvatar(spec: ProviderSpec?, label: String = "", size: Dp = 40.dp) {
    val color = parseColor(spec?.color) ?: Brand
    val shape = RoundedCornerShape(size * 0.3f)
    val initial = (spec?.name ?: label).firstOrNull()?.uppercase().orEmpty()
    val key = spec?.id ?: label
    val context = LocalContext.current
    val logo by produceState<ImageBitmap?>(null, key) {
        value = ProviderLogoCache.load(context, key)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.30f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (logo != null) {
            Image(
                bitmap = logo!!,
                contentDescription = spec?.name ?: label,
                modifier = Modifier.fillMaxSize().padding(size * 0.12f),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = initial.ifBlank { "?" },
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp
            )
        }
    }
}

fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching {
        val clean = hex.removePrefix("#")
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000 or value)
            8 -> Color(value)
            else -> null
        }
    }.getOrNull()
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    supportingText: String? = null
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder != null) {
                { Text(placeholder, color = TextMuted) }
            } else {
                null
            },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                disabledTextColor = TextMuted,
                cursorColor = Brand,
                focusedBorderColor = Brand,
                unfocusedBorderColor = AppBorder,
                disabledBorderColor = AppBorder,
                focusedContainerColor = AppSurfaceHigh,
                unfocusedContainerColor = AppSurfaceHigh,
                disabledContainerColor = AppSurfaceHigh,
                focusedLabelColor = Brand,
                unfocusedLabelColor = TextMuted,
                disabledLabelColor = TextMuted
            )
        )
        if (supportingText != null) {
            Spacer(Modifier.height(4.dp))
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand,
            contentColor = Color.White,
            disabledContainerColor = Brand.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = TextSecondary
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, AppBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brand.copy(alpha = 0.10f))
                .border(1.dp, Brand.copy(alpha = 0.22f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Brand, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(5.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Rose else Brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun DialogContent(
    title: String,
    confirmLabel: String = "Save",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    confirmLabel,
                    color = if (confirmEnabled) Brand else TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = Color(0xFF0C0C10),
        border = BorderStroke(1.dp, AppBorder)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFFD4D4D8),
            maxLines = 14,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
fun MetaRow(label: String, value: String, valueColor: Color = TextSecondary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

fun formatCount(value: Int): String = formatCount(value.toLong())

fun formatLatency(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1000 -> "${ms}ms"
    else -> String.format(Locale.US, "%.1fs", ms / 1000.0)
}

fun formatRelative(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> {
            val time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("MMM d, HH:mm").format(time)
        }
    }
}

fun formatClock(timestamp: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

fun statusColor(status: Int?): Color = when {
    status == null -> TextMuted
    status in 200..299 -> Emerald
    status in 400..499 -> Amber
    else -> Rose
}

fun statusLabel(status: Int?): String = when {
    status == null -> "pending"
    status in 200..299 -> "OK"
    status == 401 -> "unauthorized"
    status == 402 -> "billing"
    status == 403 -> "forbidden"
    status == 404 -> "not found"
    status == 429 -> "rate limited"
    else -> "error"
}

fun providerColor(providerId: String?): Color =
    parseColor(providerId?.let { ProviderCatalog.byId(it)?.color }) ?: Brand

fun percentage(value: Int, total: Int): Float = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)

fun roundPercent(value: Double): Int = value.roundToLong().toInt()
