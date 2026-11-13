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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.data.model.Combo
import ai.ninerouter.engine.ProviderCatalog
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
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.Sky
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import ai.ninerouter.ui.theme.Violet
import ai.ninerouter.util.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun CombosScreen(viewModel: MainViewModel) {
    val combos by viewModel.combos.collectAsState()
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Combo?>(null) }
    var deleting by remember { mutableStateOf<Combo?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Combo & Vision",
                subtitle = "Ordered model fallback chains",
                trailing = {
                    PrimaryButton(
                        text = "New",
                        onClick = {
                            editing = null
                            editorOpen = true
                        },
                        icon = Icons.Filled.Add
                    )
                }
            )
        }
        if (combos.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Layers,
                    title = "No combos yet",
                    body = "A combo tries its models in order, rotating on rate limits and request-scoped errors. Use a combo name as the model in any request.",
                    action = {
                        PrimaryButton(
                            text = "Create a combo",
                            onClick = {
                                editing = null
                                editorOpen = true
                            },
                            icon = Icons.Filled.Add
                        )
                    }
                )
            }
        }
        items(combos, key = { it.id }) { combo ->
            ComboCard(
                combo = combo,
                onEdit = {
                    editing = combo
                    editorOpen = true
                },
                onDelete = { deleting = combo }
            )
        }
        item {
            AppCard {
                Text("How combos work", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Send `model: \"<combo name>\"` to /v1/chat/completions. Members are tried top to bottom. " +
                        "Account-scoped errors (401/403/429) cool the connection down; request-scoped errors (400/404) " +
                        "skip to the next member without locking a healthy credential.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }

    if (editorOpen) {
        ComboEditorDialog(
            combo = editing,
            onDismiss = { editorOpen = false },
            onSave = { name, models, kind ->
                viewModel.saveCombo(editing?.id, name, models, kind) { editorOpen = false }
            }
        )
    }

    deleting?.let { combo ->
        ConfirmDialog(
            title = "Delete combo?",
            message = "\"${combo.name}\" will be removed. Requests using this name will fail until another combo or model is chosen.",
            onConfirm = {
                viewModel.deleteCombo(combo)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun ComboCard(combo: Combo, onEdit: () -> Unit, onDelete: () -> Unit) {
    val models = parseComboModels(combo)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Violet.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Layers, contentDescription = null, tint = Violet, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(combo.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${models.size} members · tries in order",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            StatusPill(
                text = if (combo.kind == "vision") "Vision" else "LLM",
                color = if (combo.kind == "vision") Sky else Violet
            )
        }
        Spacer(Modifier.height(12.dp))
        models.forEachIndexed { index, ref ->
            val (spec, modelId) = ProviderCatalog.resolve(ref)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(AppSurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    spec?.let { ProviderCatalog.displayName(it.id, modelId) } ?: modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    spec?.alias ?: spec?.id ?: "—",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "Edit",
                onClick = onEdit,
                icon = Icons.Filled.Edit,
                modifier = Modifier.weight(1f)
            )
            GhostButton(
                text = "Delete",
                onClick = onDelete,
                icon = Icons.Filled.Delete,
                color = Rose,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ComboEditorDialog(
    combo: Combo?,
    onDismiss: () -> Unit,
    onSave: (name: String, models: List<String>, kind: String?) -> Unit
) {
    var name by remember { mutableStateOf(combo?.name.orEmpty()) }
    var kind by remember { mutableStateOf(combo?.kind ?: "llm") }
    var models by remember { mutableStateOf(parseComboModels(combo)) }
    var pickerOpen by remember { mutableStateOf(false) }

    DialogContent(
        title = if (combo == null) "New combo" else "Edit combo",
        confirmLabel = "Save",
        confirmEnabled = name.isNotBlank() && models.isNotEmpty(),
        onConfirm = { onSave(name, models, kind.takeIf { it == "vision" }) },
        onDismiss = onDismiss
    ) {
        AppTextField(name, { name = it }, "Combo name", placeholder = "9vercel")
        Spacer(Modifier.height(12.dp))
        Text("Kind", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = kind != "vision",
                onClick = { kind = "llm" },
                label = { Text("LLM chain") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.copy(alpha = 0.18f),
                    selectedLabelColor = Brand,
                    labelColor = TextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = kind != "vision",
                    borderColor = AppBorder,
                    selectedBorderColor = Brand.copy(alpha = 0.5f)
                )
            )
            FilterChip(
                selected = kind == "vision",
                onClick = { kind = "vision" },
                label = { Text("Vision adapter") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Sky.copy(alpha = 0.18f),
                    selectedLabelColor = Sky,
                    labelColor = TextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = kind == "vision",
                    borderColor = AppBorder,
                    selectedBorderColor = Sky.copy(alpha = 0.5f)
                )
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Members", style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.weight(1f))
            TextButton(onClick = { pickerOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Brand, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add model", color = Brand, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (models.isEmpty()) {
            Text(
                "No models yet. Add at least one member.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        models.forEachIndexed { index, ref ->
            SelectedModelRow(
                index = index,
                ref = ref,
                canUp = index > 0,
                canDown = index < models.lastIndex,
                onUp = {
                    val copy = models.toMutableList()
                    val item = copy.removeAt(index)
                    copy.add(index - 1, item)
                    models = copy
                },
                onDown = {
                    val copy = models.toMutableList()
                    val item = copy.removeAt(index)
                    copy.add(index + 1, item)
                    models = copy
                },
                onRemove = {
                    models = models.toMutableList().also { it.removeAt(index) }
                }
            )
        }
    }

    if (pickerOpen) {
        ModelPickerDialog(
            alreadySelected = models.toSet(),
            onDismiss = { pickerOpen = false },
            onToggle = { ref ->
                models = if (models.contains(ref)) models - ref else models + ref
            }
        )
    }
}

@Composable
private fun SelectedModelRow(
    index: Int,
    ref: String,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    val (spec, modelId) = ProviderCatalog.resolve(ref)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = MaterialTheme.shapes.small,
        color = AppSurfaceHigh,
        border = BorderStroke(1.dp, AppBorder)
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.width(18.dp)
            )
            ProviderAvatar(spec, label = ref, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    spec?.let { ProviderCatalog.displayName(it.id, modelId) } ?: modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ref,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = if (canUp) TextSecondary else TextMuted.copy(alpha = 0.4f))
            }
            IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = if (canDown) TextSecondary else TextMuted.copy(alpha = 0.4f))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Rose, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    alreadySelected: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val groups = ProviderCatalog.all.map { spec ->
        spec to spec.models.filter {
            query.isBlank() ||
                it.id.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                spec.name.contains(query, ignoreCase = true)
        }
    }.filter { it.second.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add model", color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search models",
                    trailingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted)
                    }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    groups.forEach { (spec, models) ->
                        item(key = "p-${spec.id}") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderAvatar(spec, size = 20.dp)
                                Spacer(Modifier.width(7.dp))
                                Text(spec.name, style = MaterialTheme.typography.titleSmall, color = TextSecondary)
                            }
                        }
                        items(models, key = { "${spec.id}/${it.id}" }) { model ->
                            val ref = "${spec.id}/${model.id}"
                            val selected = alreadySelected.contains(ref)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggle(ref) }
                                    .background(if (selected) Brand.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(vertical = 7.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                                    Text(
                                        ref,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (selected) {
                                    Text("Added", style = MaterialTheme.typography.labelSmall, color = Brand)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Brand) }
        }
    )
}

private fun parseComboModels(combo: Combo?): List<String> {
    if (combo == null) return emptyList()
    return runCatching {
        val array = Json.lenient.parseToJsonElement(combo.models) as JsonArray
        array.mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())
}
