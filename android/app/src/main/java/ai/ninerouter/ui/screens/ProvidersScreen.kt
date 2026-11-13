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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.CompatibleProbe
import ai.ninerouter.data.model.ANTHROPIC_COMPATIBLE_TYPE
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.OPENAI_COMPATIBLE_TYPE
import ai.ninerouter.data.model.ProviderConnection
import ai.ninerouter.engine.FallbackPolicy
import ai.ninerouter.engine.ProviderCatalog
import ai.ninerouter.engine.ProviderSpec
import ai.ninerouter.engine.oauth.OAuthProviders
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
    val customNodes by viewModel.customProviders.collectAsState()
    val customSpecs by viewModel.customSpecs.collectAsState()
    val allSpecs = remember(customSpecs) { ProviderCatalog.allWith(customSpecs) }
    val busy by viewModel.busy.collectAsState()
    val oauthBusy by viewModel.oauthBusy.collectAsState()
    var addProvider by remember { mutableStateOf<ProviderSpec?>(null) }
    var editing by remember { mutableStateOf<ProviderConnection?>(null) }
    var deleting by remember { mutableStateOf<ProviderConnection?>(null) }
    var createCompatible by remember { mutableStateOf<String?>(null) }
    var editCompatible by remember { mutableStateOf<CustomProvider?>(null) }
    var deleteCompatible by remember { mutableStateOf<CustomProvider?>(null) }
    var manageModels by remember { mutableStateOf<CustomProvider?>(null) }
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
                subtitle = "${connections.size} connections · ${customNodes.size} custom endpoints",
                trailing = {
                    PrimaryButton(
                        text = "Add",
                        onClick = { addProvider = allSpecs.firstOrNull() },
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
                            onClick = { addProvider = allSpecs.firstOrNull() },
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
            Spacer(Modifier.height(8.dp))
            SectionHeader(
                title = "Custom Providers (OpenAI/Anthropic Compatible)",
                subtitle = "Route prefix/model to any compatible endpoint"
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "Add OpenAI",
                    onClick = { createCompatible = OPENAI_COMPATIBLE_TYPE },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Add
                )
                GhostButton(
                    text = "Add Anthropic",
                    onClick = { createCompatible = ANTHROPIC_COMPATIBLE_TYPE },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Add
                )
            }
        }
        if (customNodes.isEmpty()) {
            item {
                Text(
                    "No custom providers — use the buttons above to add OpenAI/Anthropic compatible endpoints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        customNodes.forEach { node ->
            val spec = customSpecs.firstOrNull { it.id == node.id }
                ?: ProviderCatalog.customSpec(node)
            val rows = connections.filter { it.provider == node.id }
            item(key = "custom-${node.id}") {
                CustomProviderCard(
                    node = node,
                    spec = spec,
                    connectionCount = rows.size,
                    onAddKey = { addProvider = spec },
                    onModels = { manageModels = node },
                    onEdit = { editCompatible = node },
                    onDelete = { deleteCompatible = node }
                )
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
            options = allSpecs,
            oauthBusy = oauthBusy,
            onDismiss = { addProvider = null },
            onOAuth = { providerId -> viewModel.connectOAuth(providerId) { addProvider = null } },
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
            spec = allSpecs.firstOrNull { it.id == connection.provider }
                ?: ProviderCatalog.byId(connection.provider),
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

    createCompatible?.let { type ->
        CompatibleProviderDialog(
            type = type,
            node = null,
            onDismiss = { createCompatible = null },
            onCheck = { baseUrl, key, modelId, onResult ->
                viewModel.checkCustomProvider(type, baseUrl, key, modelId, onResult)
            },
            onSave = { name, prefix, apiType, baseUrl, models, apiKey ->
                viewModel.saveCustomProvider(
                    id = null,
                    type = type,
                    name = name,
                    prefix = prefix,
                    apiType = apiType,
                    baseUrl = baseUrl,
                    models = models,
                    apiKey = apiKey,
                    createConnection = true,
                    onDone = { created ->
                        createCompatible = null
                        manageModels = created
                    }
                )
            }
        )
    }

    editCompatible?.let { node ->
        CompatibleProviderDialog(
            type = node.type,
            node = node,
            onDismiss = { editCompatible = null },
            onCheck = { baseUrl, key, modelId, onResult ->
                viewModel.checkCustomProvider(node.type, baseUrl, key, modelId, onResult)
            },
            onSave = { name, prefix, apiType, baseUrl, models, apiKey ->
                viewModel.saveCustomProvider(
                    id = node.id,
                    type = node.type,
                    name = name,
                    prefix = prefix,
                    apiType = apiType,
                    baseUrl = baseUrl,
                    models = models,
                    apiKey = apiKey,
                    createConnection = false,
                    onDone = { editCompatible = null }
                )
            }
        )
    }

    manageModels?.let { node ->
        CustomModelsDialog(
            node = node,
            onDismiss = { manageModels = null },
            onSave = { models ->
                viewModel.saveCustomModels(node.id, models) { manageModels = null }
            },
            onDiscover = { key, onDone ->
                viewModel.discoverCustomModels(node.id, key, onDone)
            }
        )
    }

    deleteCompatible?.let { node ->
        val count = connections.count { it.provider == node.id }
        ConfirmDialog(
            title = "Delete custom provider?",
            message = "\"${node.name}\" and its $count connection${if (count == 1) "" else "s"} will be removed from this device.",
            onConfirm = {
                viewModel.deleteCustomProvider(node)
                deleteCompatible = null
            },
            onDismiss = { deleteCompatible = null }
        )
    }
}

@Composable
private fun CustomProviderCard(
    node: CustomProvider,
    spec: ProviderSpec,
    connectionCount: Int,
    onAddKey: () -> Unit,
    onModels: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = parseColor(spec.color) ?: Brand
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(spec, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (node.isAnthropic) "Anthropic compatible" else "OpenAI compatible · Chat Completions",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            StatusPill("Compatible", accent)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(node.prefix, accent)
            InfoChip("${node.modelIds().size} models")
            InfoChip(if (connectionCount == 1) "1 key" else "$connectionCount keys")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            node.baseUrl,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Route as ${node.prefix}/<model>",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Add key",
                onClick = onAddKey,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Add
            )
            GhostButton(
                text = "Models",
                onClick = onModels,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Edit",
                onClick = onEdit,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Edit
            )
            GhostButton(
                text = "Delete",
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Delete,
                color = Rose
            )
        }
    }
}

@Composable
private fun InfoChip(text: String, color: Color = TextSecondary) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AppSurfaceHigh)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompatibleProviderDialog(
    type: String,
    node: CustomProvider?,
    onDismiss: () -> Unit,
    onCheck: (
        baseUrl: String,
        apiKey: String,
        modelId: String,
        onResult: (CompatibleProbe) -> Unit
    ) -> Unit,
    onSave: (
        name: String,
        prefix: String,
        apiType: String,
        baseUrl: String,
        models: List<String>,
        apiKey: String
    ) -> Unit
) {
    val anthropic = type == ANTHROPIC_COMPATIBLE_TYPE
    val defaultBase = if (anthropic) "https://api.anthropic.com/v1" else "https://api.openai.com/v1"
    var name by remember(node?.id) { mutableStateOf(node?.name.orEmpty()) }
    var prefix by remember(node?.id) { mutableStateOf(node?.prefix.orEmpty()) }
    val apiType = node?.apiType ?: "chat"
    var baseUrl by remember(node?.id) { mutableStateOf(node?.baseUrl ?: defaultBase) }
    var modelsText by remember(node?.id) {
        mutableStateOf(node?.modelIds()?.joinToString(", ").orEmpty())
    }
    var apiKey by remember(node?.id) { mutableStateOf("") }
    var modelId by remember(node?.id) { mutableStateOf("") }
    var checking by remember(node?.id) { mutableStateOf(false) }
    var checkResult by remember(node?.id) { mutableStateOf<CompatibleProbe?>(null) }
    val models = modelsText.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    DialogContent(
        title = when {
            node != null && anthropic -> "Edit Anthropic Compatible"
            node != null -> "Edit OpenAI Compatible"
            anthropic -> "Add Anthropic Compatible"
            else -> "Add OpenAI Compatible"
        },
        confirmLabel = if (node == null) "Create" else "Save",
        confirmEnabled = name.isNotBlank() && prefix.isNotBlank() && baseUrl.isNotBlank() && !checking,
        onConfirm = {
            onSave(
                name.trim(),
                prefix.trim().removeSuffix("/"),
                if (anthropic) "chat" else apiType,
                baseUrl.trim(),
                models,
                apiKey
            )
        },
        onDismiss = onDismiss
    ) {
        Text(
            if (anthropic) {
                "Connect any Anthropic-compatible endpoint. Requests are sent to <base URL>/messages."
            } else {
                "Connect any OpenAI-compatible endpoint. Requests are sent to <base URL>/chat/completions."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(Modifier.height(12.dp))
        AppTextField(
            name,
            { name = it },
            "Name",
            placeholder = if (anthropic) "Anthropic Compatible (Prod)" else "OpenAI Compatible (Prod)",
            supportingText = "Required. A friendly label for this node."
        )
        Spacer(Modifier.height(10.dp))
        AppTextField(
            prefix,
            { prefix = it },
            "Prefix",
            placeholder = if (anthropic) "ac-prod" else "oc-prod",
            supportingText = "Required. Used as the model prefix: prefix/model."
        )
        Spacer(Modifier.height(10.dp))
        if (!anthropic) {
            Text("API type", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            InfoChip("Chat Completions", Brand)
            Spacer(Modifier.height(4.dp))
            Text(
                "Responses API is not supported yet — use Chat Completions.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
            Spacer(Modifier.height(10.dp))
        }
        AppTextField(
            baseUrl,
            { baseUrl = it },
            "Base URL",
            placeholder = defaultBase,
            supportingText = "Use the base URL ending in /v1. The app appends " +
                (if (anthropic) "/messages." else "/chat/completions.")
        )
        Spacer(Modifier.height(12.dp))
        Text("Check connection", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        AppTextField(
            apiKey,
            { apiKey = it },
            "API key",
            placeholder = "sk-...",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (node == null) {
                "Optional. Used for Check and saved as the first connection key."
            } else {
                "Only used for Check. Existing connection keys are unchanged."
            }
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            modelId,
            { modelId = it },
            "Model ID (optional)",
            placeholder = "gpt-4o, claude-3-opus",
            supportingText = "Only needed when the server has no /models endpoint."
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    checking = true
                    checkResult = null
                    onCheck(baseUrl, apiKey, modelId) { result ->
                        checking = false
                        checkResult = result
                        if (result.ok && result.models.isNotEmpty()) {
                            modelsText = (models + result.models)
                                .distinct()
                                .joinToString(", ")
                        }
                    }
                },
                enabled = baseUrl.isNotBlank() && !checking,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder)
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Brand)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (checking) "Checking…" else "Check", color = TextSecondary)
            }
            checkResult?.let { result ->
                Spacer(Modifier.width(10.dp))
                Text(
                    if (result.ok) "Valid" else "Invalid",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result.ok) Emerald else Rose
                )
            }
        }
        checkResult?.let { result ->
            Spacer(Modifier.height(4.dp))
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) Emerald else Rose
            )
        }
        Spacer(Modifier.height(12.dp))
        AppTextField(
            modelsText,
            { modelsText = it },
            "Model IDs (comma separated)",
            placeholder = "gpt-4o, gpt-4o-mini",
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            supportingText = "Shown in /v1/models and combo pickers. You can also fetch them later."
        )
    }
}

@Composable
private fun CustomModelsDialog(
    node: CustomProvider,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onDiscover: (apiKey: String, onDone: (List<String>?) -> Unit) -> Unit
) {
    var models by remember(node.id) { mutableStateOf(node.modelIds()) }
    var newModel by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }

    DialogContent(
        title = "Models · ${node.name}",
        confirmLabel = "Save",
        onConfirm = { onSave(models) },
        onDismiss = onDismiss
    ) {
        Text(
            "Model IDs exposed by ${node.prefix}. Requests use ${node.prefix}/<model>.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(Modifier.height(8.dp))
        if (models.isEmpty()) {
            Text(
                "No models yet. Add one manually or fetch the endpoint's /models list.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber
            )
        }
        models.forEach { id ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    id,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { models = models - id },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove model",
                        tint = Rose,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                newModel,
                { newModel = it },
                "Add model ID",
                modifier = Modifier.weight(1f),
                placeholder = "gpt-4o"
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val value = newModel.trim()
                    if (value.isNotEmpty() && !models.contains(value)) {
                        models = models + value
                    }
                    newModel = ""
                },
                enabled = newModel.isNotBlank()
            ) {
                Text("Add", color = Brand)
            }
        }
        Spacer(Modifier.height(12.dp))
        AppTextField(
            apiKey,
            { apiKey = it },
            "API key (optional)",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = "Leave blank to use the first saved connection key."
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                fetching = true
                onDiscover(apiKey) { discovered ->
                    fetching = false
                    if (discovered != null) models = (models + discovered).distinct()
                }
            },
            enabled = !fetching,
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            if (fetching) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Brand)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(if (fetching) "Fetching…" else "Fetch from /models", color = TextSecondary)
        }
    }
}

@Composable
private fun ProviderGroupHeader(spec: ProviderSpec, count: Int) {    Row(
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
    val coolingUntil = FallbackPolicy.earliestModelLock(connection)?.takeIf { it > now }
    val cooling = coolingUntil != null
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
            if (coolingUntil != null) {
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
    options: List<ProviderSpec>,
    oauthBusy: Set<String>,
    onDismiss: () -> Unit,
    onOAuth: (providerId: String) -> Unit,
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
    val oauth = OAuthProviders.byId(spec.id)
    val signingIn = oauthBusy.contains(spec.id)

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
                options.forEach { option ->
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
        if (oauth != null) {
            PrimaryButton(
                text = if (signingIn) "Waiting for browser…" else oauth.buttonLabel,
                onClick = { onOAuth(spec.id) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !signingIn
            )
            Spacer(Modifier.height(8.dp))
            if (signingIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Brand)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Complete the sign-in in your browser, then return to the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            } else {
                Text(
                    "Opens ${spec.name} in your browser and returns here automatically. " +
                        "Tokens are encrypted with the Android Keystore on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Or connect manually", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(8.dp))
        }
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
            supportingText = when {
                oauth != null -> "Optional when you use the browser sign-in above."
                spec.id == "opencode-zen" -> "Free models use the public inference lane and need no key."
                spec.id == "ollama" -> "No key needed for a local Ollama server."
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
        AppTextField(model, { model = it }, "Default model", placeholder = spec?.defaultModel ?: spec?.models?.firstOrNull()?.id)
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
