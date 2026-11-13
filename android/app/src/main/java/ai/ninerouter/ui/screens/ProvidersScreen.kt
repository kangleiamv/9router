package ai.ninerouter.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.engine.FallbackPolicy
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.ConfirmDialog
import ai.ninerouter.ui.components.DialogContent
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.ProviderAvatar
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.components.parseColor
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun ProvidersScreen(viewModel: MainViewModel) {
    val connections by viewModel.connections.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var addProvider by remember { mutableStateOf<ProviderSpec?>(null) }
    var editing by remember { mutableStateOf<ProviderConnection?>(null) }
    var deleting by remember { mutableStateOf<ProviderConnection?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Providers",
                subtitle = "${connections.size} connections · priority ordered",
                trailing = {
                    PrimaryButton(
                        text = "Add",
                        onClick = { addProvider = ProviderCatalog.all.first() },
                        icon = Icons.Filled.Add
                    )
                }
            )
        }
        if (connections.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "No providers connected",
                    body = "Add an API key or OAuth token to start routing requests. Everything is stored on this device.",
                    action = {
                        PrimaryButton(
                            text = "Add your first provider",
                            onClick = { addProvider = ProviderCatalog.all.first() },
                            icon = Icons.Filled.Add
                        )
                    }
                )
            }
        }
        ProviderCatalog.all.forEach { spec ->
            val rows = connections.filter { it.provider == spec.id }
            if (rows.isNotEmpty()) {
                item(key = "header-${spec.id}") {
                    ProviderGroupHeader(spec, rows.size)
                }
                items(rows, key = { it.id }) { connection ->
                    ConnectionCard(
                        connection = connection,
                        spec = spec,
                        busy = busy.contains(connection.id),
                        now = now,
                        onTest = { viewModel.testConnection(connection) },
                        onToggle = { viewModel.toggleConnection(connection) },
                        onEdit = { editing = connection },
                        onDelete = { deleting = connection }
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader(
                title = "Add more",
                subtitle = "Tap a provider to create a new connection"
            )
        }
        items(ProviderCatalog.all.filter { spec -> connections.none { it.provider == spec.id } }, key = { it.id }) { spec ->
            AppCard(onClick = { addProvider = spec }, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderAvatar(spec, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(spec.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "${spec.models.size} models · ${spec.category}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Text("Add", style = MaterialTheme.typography.labelLarge, color = Brand)
                }
            }
        }
    }

    addProvider?.let { spec ->
        AddConnectionDialog(
            initial = spec,
            onDismiss = { addProvider = null },
            onAdd = { providerId, name, key, email, baseUrl, advanced ->
                viewModel.addConnection(
                    providerId = providerId,
                    name = name,
                    apiKey = key,
                    email = email,
                    baseUrl = baseUrl,
                    advanced = advanced,
                    onDone = { addProvider = null }
                )
            }
        )
    }

    editing?.let { connection ->
        EditConnectionDialog(
            connection = connection,
            spec = ProviderCatalog.byId(connection.provider),
            onDismiss = { editing = null },
            onSave = { name, model, key ->
                viewModel.saveConnection(connection, name, model, key) { editing = null }
            }
        )
    }

    deleting?.let { connection ->
        ConfirmDialog(
            title = "Delete connection?",
            message = "This removes ${connection.name ?: connection.provider} and its stored credentials from this device.",
            onConfirm = {
                viewModel.deleteConnection(connection)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun ProviderGroupHeader(spec: ProviderSpec, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(parseColor(spec.color) ?: Brand)
        )
        Spacer(Modifier.width(8.dp))
        Text(spec.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.width(6.dp))
        Text("· $count", style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

@Composable
private fun ConnectionCard(
    connection: ProviderConnection,
    spec: ProviderSpec,
    busy: Boolean,
    now: Long,
    onTest: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val coolingUntil = FallbackPolicy.earliestModelLock(connection)
    val cooling = coolingUntil != null && coolingUntil > now
    val status = when {
        !connection.isActive -> "Paused"
        cooling -> "Cooling"
        connection.testStatus == "error" -> "Error"
        connection.testStatus == "unavailable" -> "Limited"
        else -> "Ready"
    }
    val statusTint = when (status) {
        "Ready" -> Emerald
        "Cooling", "Limited" -> Amber
        "Error" -> Rose
        else -> TextMuted
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(spec, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    connection.displayName ?: connection.name ?: spec.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    connection.email ?: "${spec.name} · ${connection.authType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(status, statusTint)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = connection.isActive,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = Brand.copy(alpha = 0.55f),
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = AppSurfaceHigh,
                    uncheckedBorderColor = AppBorder
                )
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppSurfaceHigh)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    connection.defaultModel ?: "no default model",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary
                )
            }
            if (cooling && coolingUntil != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "⏱ ${((coolingUntil - now) / 1000).coerceAtLeast(0)}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                connection.lastUsedAt?.let {
                    val millis = runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                    if (millis != null) "used ${ai.ninerouter.ui.components.formatRelative(millis)}" else "used recently"
                } ?: "never used",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        if (connection.lastError != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                connection.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onTest,
                enabled = !busy,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
                modifier = Modifier.weight(1f).height(38.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Brand)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Test", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onEdit,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
                modifier = Modifier.weight(1f).height(38.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Rose, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AddConnectionDialog(
    initial: ProviderSpec,
    onDismiss: () -> Unit,
    onAdd: (providerId: String, name: String, apiKey: String, email: String, baseUrl: String, advanced: String) -> Unit
) {
    var spec by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    DialogContent(
        title = "Add connection",
        confirmLabel = "Add",
        confirmEnabled = true,
        onConfirm = {
            onAdd(spec.id, name, apiKey, email, baseUrl, advanced)
        },
        onDismiss = onDismiss
    ) {
        Text("Provider", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(
                onClick = { pickerOpen = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder)
            ) {
                ProviderAvatar(spec, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(spec.name, color = TextPrimary, modifier = Modifier.weight(1f))
                Text("▾", color = TextMuted)
            }
            DropdownMenu(
                expanded = pickerOpen,
                onDismissRequest = { pickerOpen = false },
                modifier = Modifier.heightIn(max = 340.dp)
            ) {
                ProviderCatalog.all.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProviderAvatar(option, size = 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(option.name, color = TextPrimary)
                            }
                        },
                        onClick = {
                            spec = option
                            pickerOpen = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        AppTextField(name, { name = it }, "Display name", placeholder = spec.name)
        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = if (spec.id == "antigravity") "Access token" else "API key",
            placeholder = if (spec.id == "opencode-zen") "sk-... or leave blank for free models" else "Paste your key",
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = TextMuted
                    )
                }
            },
            supportingText = when (spec.id) {
                "antigravity" -> "Paste a Google access token, or put refreshToken/projectId in Advanced JSON."
                "opencode-zen" -> "Free models use the public inference lane and need no key."
                "ollama" -> "No key needed for a local Ollama server."
                else -> null
            }
        )
        Spacer(Modifier.height(10.dp))
        AppTextField(email, { email = it }, "Account email (optional)", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(10.dp))
        AppTextField(baseUrl, { baseUrl = it }, "Base URL override (optional)")
        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = advanced,
            onValueChange = { advanced = it },
            label = "Advanced JSON (optional)",
            placeholder = "\"refreshToken\":\"…\",\"projectId\":\"…\"",
            singleLine = false,
            minLines = 2,
            maxLines = 4
        )
    }
}

@Composable
private fun EditConnectionDialog(
    connection: ProviderConnection,
    spec: ProviderSpec?,
    onDismiss: () -> Unit,
    onSave: (name: String, model: String, apiKey: String) -> Unit
) {
    var name by remember { mutableStateOf(connection.name.orEmpty()) }
    var model by remember { mutableStateOf(connection.defaultModel.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    DialogContent(
        title = "Edit connection",
        confirmLabel = "Save",
        onConfirm = { onSave(name, model, apiKey) },
        onDismiss = onDismiss
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(spec, label = connection.provider, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(spec?.name ?: connection.provider, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(connection.authType, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
        AppTextField(name, { name = it }, "Display name")
        Spacer(Modifier.height(10.dp))
        AppTextField(model, { model = it }, "Default model", placeholder = spec?.models?.firstOrNull()?.id)
        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = "Replace key (optional)",
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = TextMuted
                    )
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Credentials are encrypted with the Android Keystore before they are written to Room.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }
}
