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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.data.model.ApiKey
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.CodeBlock
import ai.ninerouter.ui.components.ConfirmDialog
import ai.ninerouter.ui.components.DialogContent
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.components.formatRelative
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

@Composable
fun KeysScreen(viewModel: MainViewModel) {
    val keys by viewModel.keys.collectAsState()
    val port by viewModel.port.collectAsState()
    val clipboard = LocalClipboardManager.current
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ApiKey?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }
    val endpoint = "http://127.0.0.1:$port/v1"

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Endpoint & Key",
                subtitle = "OpenAI-compatible credentials for local clients",
                trailing = {
                    PrimaryButton(
                        text = "New key",
                        onClick = { creating = true },
                        icon = Icons.Filled.Add
                    )
                }
            )
        }
        item {
            AppCard {
                Text("BASE URL", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Brand,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(endpoint)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy endpoint", tint = TextSecondary, modifier = Modifier.size(17.dp))
                    }
                }
                Text(
                    "Bind address is loopback only. Forward with  adb forward tcp:20127 tcp:20127  for a desktop client, " +
                        "or open the Tunnel screen to publish /v1 through Cloudflare.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        if (keys.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "No API keys",
                    body = "Create a key for each client so requests can be authenticated at the local gateway.",
                    action = { PrimaryButton("Create a key", { creating = true }, icon = Icons.Filled.Add) }
                )
            }
        }
        items(keys, key = { it.id }) { key ->
            KeyCard(
                key = key,
                copied = copiedKey == key.id,
                onCopy = {
                    clipboard.setText(AnnotatedString(key.key))
                    copiedKey = key.id
                },
                onToggle = { viewModel.toggleKey(key) },
                onDelete = { deleting = key }
            )
        }
        item {
            AppCard {
                Text("Quick test", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Use any active key as a Bearer token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                CodeBlock(
                    """
curl $endpoint/chat/completions \
  -H "Authorization: Bearer ${keys.firstOrNull()?.key ?: "sk-9r-..."}" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "9vercel",
    "messages": [{"role": "user", "content": "hi"}]
  }'
                    """.trimIndent()
                )
            }
        }
    }

    if (creating) {
        CreateKeyDialog(
            onDismiss = { creating = false },
            onCreate = { viewModel.createKey(it) { creating = false } }
        )
    }

    deleting?.let { key ->
        ConfirmDialog(
            title = "Delete API key?",
            message = "\"${key.name}\" will immediately stop authenticating requests.",
            onConfirm = {
                viewModel.deleteKey(key)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun KeyCard(
    key: ApiKey,
    copied: Boolean,
    onCopy: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var revealed by remember { mutableStateOf(false) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (key.isActive) Emerald.copy(alpha = 0.14f) else AppSurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    key.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (key.isActive) Emerald else TextMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "created ${runCatching { java.time.Instant.parse(key.createdAt).toEpochMilli() }.getOrNull()?.let { formatRelative(it) } ?: "recently"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            StatusPill(if (key.isActive) "Active" else "Disabled", if (key.isActive) Emerald else TextMuted)
            Switch(
                checked = key.isActive,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = Emerald.copy(alpha = 0.5f),
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = AppSurfaceHigh,
                    uncheckedBorderColor = AppBorder
                )
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppSurfaceHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (revealed) key.key else maskKey(key.key),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (revealed) TextPrimary else TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy key",
                    tint = if (copied) Emerald else TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete key", tint = Rose, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    DialogContent(
        title = "New API key",
        confirmLabel = "Create",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onCreate(name) },
        onDismiss = onDismiss
    ) {
        AppTextField(name, { name = it }, "Key name", placeholder = "Laptop, phone, automation…")
        Spacer(Modifier.height(8.dp))
        Text(
            "The key is generated on-device and can be revoked at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}

private fun maskKey(key: String): String {
    if (key.length <= 14) return "•".repeat(key.length.coerceAtLeast(6))
    return key.take(9) + "•".repeat(14) + key.takeLast(4)
}
