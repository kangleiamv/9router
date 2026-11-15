package ai.ninerouter.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.HostedKind
import ai.ninerouter.core.HostedKinds
import ai.ninerouter.core.HostedService
import ai.ninerouter.core.HostingStore
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.CodeBlock
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

/**
 * Runtimes hosted by the app itself. Binaries are discovered on the device
 * (app `bin` folder, PATH or Termux); the card starts, stops and publishes them
 * without bundling any runtime.
 */
@Composable
internal fun HostingCard(
    services: List<HostedService>,
    states: Map<String, HostServiceState>,
    availability: Map<String, Boolean>,
    blockedPorts: Set<Int>,
    onRefreshAvailability: () -> Unit,
    onSave: (HostedService) -> Unit,
    onDelete: (String) -> Unit,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onAutoStart: (String, Boolean) -> Unit,
    onPublish: (HostedService) -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HostedService?>(null) }
    var openLog by remember { mutableStateOf<String?>(null) }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brand.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    tint = Brand,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Hosting", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    "PHP, Node.js, Python, Redis, PostgreSQL and MySQL on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            TextButton(onClick = { onRefreshAvailability(); addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Brand, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("Add", color = Brand, style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "PHP, Node.js, MariaDB and PostgreSQL ship inside the app for arm64 and run " +
                "from the native library folder on loopback. Python and Redis need a " +
                "runtime on PATH; Android 10+ blocks binaries copied into app storage.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )

        val found = HostedKinds.all.filter { availability[it.id] == true }
        if (availability.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (found.isEmpty()) {
                    "No runtimes found. Install Python or Redis on PATH, or run the service " +
                        "in Termux and publish it as a local link."
                } else {
                    "Found: " + found.joinToString(" · ") { it.label }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (found.isEmpty()) TextMuted else Emerald,
            )
        }

        if (services.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Add a service to create a document root or data folder and start it on 127.0.0.1.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        services.forEach { service ->
            val spec = service.spec ?: return@forEach
            val state = states[service.id] ?: HostServiceState(port = service.port)
            ServiceRow(
                service = service,
                spec = spec,
                state = state,
                available = availability[spec.id] != false,
                logOpen = openLog == service.id,
                onToggleLog = { openLog = if (openLog == service.id) null else service.id },
                onStart = { onStart(service.id) },
                onStop = { onStop(service.id) },
                onAutoStart = { onAutoStart(service.id, it) },
                onPublish = { onPublish(service) },
                onDelete = { deleting = service },
            )
        }

        TextButton(onClick = { onRefreshAvailability(); addOpen = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text("Add hosted service", color = Brand, style = MaterialTheme.typography.labelMedium)
        }
    }

    if (addOpen) {
        HostingEditorDialog(
            blockedPorts = blockedPorts,
            existing = services,
            onDismiss = { addOpen = false },
            onAdd = { service ->
                onSave(service)
                addOpen = false
            },
        )
    }

    deleting?.let { service ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = AppSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete ${service.name}?") },
            text = {
                Text(
                    "The process stops and its folder is left on disk so data is not lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(service.id)
                    deleting = null
                }) {
                    Text("Delete", color = Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel", color = TextMuted) }
            },
        )
    }
}

@Composable
private fun ServiceRow(
    service: HostedService,
    spec: HostedKind,
    state: HostServiceState,
    available: Boolean,
    logOpen: Boolean,
    onToggleLog: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onPublish: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusColor = when (state.status) {
        HostStatus.RUNNING -> Emerald
        HostStatus.STARTING -> Amber
        HostStatus.ERROR -> Rose
        HostStatus.STOPPED -> TextMuted
    }
    val statusText = when (state.status) {
        HostStatus.RUNNING -> "Running"
        HostStatus.STARTING -> "Starting"
        HostStatus.ERROR -> "Error"
        HostStatus.STOPPED -> "Stopped"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TextPrimary.copy(alpha = 0.03f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(service.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "${spec.label} · 127.0.0.1:${service.port}" +
                        if (service.entry.isNotBlank()) " · ${service.entry}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            StatusPill(statusText, statusColor)
        }

        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = if (state.status == HostStatus.ERROR) Rose else TextMuted,
            )
        }
        if (!available) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (spec.bundled) {
                    "The bundled ${spec.label} library is missing from this APK (arm64 build)."
                } else {
                    "Runtime not found. Install ${spec.binaries.joinToString(" or ")} on PATH, " +
                        "or run it in Termux and publish it as a link."
                },
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
            )
        }
        if (state.status == HostStatus.RUNNING && spec.web) {
            Spacer(Modifier.height(4.dp))
            Text(
                "http://127.0.0.1:${service.port}/",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextSecondary,
            )
        }
        if (spec.id in HostingStore.DATABASE_KINDS && state.status == HostStatus.RUNNING) {
            Spacer(Modifier.height(6.dp))
            CodeBlock(credentials(service, spec))
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.status == HostStatus.RUNNING || state.status == HostStatus.STARTING) {
                GhostButton(text = "Stop", onClick = onStop, icon = Icons.Filled.Stop, color = Rose)
            } else {
                GhostButton(
                    text = "Start",
                    onClick = onStart,
                    enabled = available,
                    icon = Icons.Filled.PlayArrow,
                    color = Emerald,
                )
            }
            Spacer(Modifier.width(8.dp))
            GhostButton(text = "Publish", onClick = onPublish)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleLog) {
                Text(
                    if (logOpen) "Hide log" else "Log",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${service.name}", tint = Rose, modifier = Modifier.size(17.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Start with 9Router",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = service.autoStart,
                onCheckedChange = onAutoStart,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Brand,
                ),
            )
        }

        if (logOpen) {
            Spacer(Modifier.height(8.dp))
            CodeBlock(
                if (state.log.isEmpty()) {
                    "No output yet."
                } else {
                    state.log.takeLast(14).joinToString("\n")
                },
            )
        }
    }
}

private fun credentials(service: HostedService, spec: HostedKind): String = when (spec.id) {
    "postgres" -> buildString {
        appendLine("host: 127.0.0.1:${service.port}")
        appendLine("user: ${service.username}")
        append("password: ${service.password}")
    }

    "mysql" -> buildString {
        appendLine("host: 127.0.0.1:${service.port}")
        appendLine("user: ${service.username}")
        appendLine("password: ${service.password}")
        append("database: app")
    }

    else -> "password: ${service.password}"
}

@Composable
private fun HostingEditorDialog(
    blockedPorts: Set<Int>,
    existing: List<HostedService>,
    onDismiss: () -> Unit,
    onAdd: (HostedService) -> Unit,
) {
    var kind by remember { mutableStateOf(HostedKinds.php) }
    var name by remember { mutableStateOf(kind.label) }
    var port by remember { mutableStateOf(kind.port.toString()) }
    var entry by remember { mutableStateOf("") }
    var autoStart by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pick(next: HostedKind) {
        kind = next
        if (name.isBlank() || HostedKinds.all.any { it.label == name }) name = next.label
        port = next.port.toString()
        entry = ""
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Add hosted service") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "PHP, Node.js, MariaDB and PostgreSQL are ready to run. Each service gets an " +
                        "app-owned folder on loopback; database services get a generated password " +
                        "and create their data files on first start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                HostedKinds.all.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { item ->
                            KindChip(
                                label = item.label,
                                selected = item.id == kind.id,
                                modifier = Modifier.weight(1f),
                                onClick = { pick(item) },
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    kind.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                if (kind.id == "postgres" || kind.id == "mysql") {
                    Text(
                        "The first start initializes the database, which can take a few seconds.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                    )
                }
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    placeholder = kind.label,
                )
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = "Loopback port",
                    placeholder = kind.port.toString(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = if (port.toIntOrNull() in blockedPorts) {
                        "Port $port is used by 9Router."
                    } else {
                        "Must be free on 127.0.0.1."
                    },
                )
                if (kind.id == "node" || kind.id == "python") {
                    Spacer(Modifier.height(10.dp))
                    AppTextField(
                        value = entry,
                        onValueChange = { entry = it },
                        label = "Entrypoint (optional)",
                        placeholder = if (kind.id == "node") "server.js" else "app.py",
                        supportingText = if (kind.id == "python") {
                            "Blank runs python3 -m http.server over the public folder."
                        } else {
                            "Blank runs server.js in the public folder."
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Start with 9Router",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { autoStart = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Brand,
                        ),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Add service",
                onClick = {
                    val parsed = port.toIntOrNull()
                    if (parsed == null) {
                        error = "Enter a numeric port."
                        return@PrimaryButton
                    }
                    onAdd(
                        HostedService(
                            id = HostingStore.newId(kind.id, existing),
                            kind = kind.id,
                            name = name.trim().ifBlank { kind.label },
                            port = parsed,
                            entry = entry.trim(),
                            autoStart = autoStart,
                        ),
                    )
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        },
    )
}

@Composable
private fun KindChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, if (selected) Brand.copy(alpha = 0.5f) else AppBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Brand.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (selected) Brand else TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
