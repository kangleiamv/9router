package ai.ninerouter.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.FtpAccount
import ai.ninerouter.core.FtpHostSettings
import ai.ninerouter.core.FtpHostState
import ai.ninerouter.core.FtpHostStore
import ai.ninerouter.core.HostServiceState
import ai.ninerouter.core.HostStatus
import ai.ninerouter.core.HostStorage
import ai.ninerouter.core.HostedService
import ai.ninerouter.service.server.HostingManager
import ai.ninerouter.core.SiteDatabases
import ai.ninerouter.core.SiteTls
import ai.ninerouter.core.TunnelPorts
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.ConfirmDialog
import ai.ninerouter.ui.components.EmptyState
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.MetaRow
import ai.ninerouter.ui.components.PrimaryButton
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

/* ------------------------------------------------------------------ */
/* Databases                                                           */
/* ------------------------------------------------------------------ */

@Composable
internal fun DatabasesTab(
    viewModel: MainViewModel,
    services: List<HostedService>,
    states: Map<String, HostServiceState>,
) {
    val databases by viewModel.databases.collectAsState()
    val busy by viewModel.databaseBusy.collectAsState()
    val version by viewModel.databaseVersion.collectAsState()

    val mysql = services.firstOrNull { it.kind == "mysql" }
    val postgres = services.firstOrNull { it.kind == "postgres" }
    var createOpen by remember { mutableStateOf(false) }
    var dropFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mysql?.id, mysql?.port, mysql?.let { states[it.id]?.status }) {
        if (mysql != null) viewModel.refreshDatabases()
    }

    val running = mysql != null && states[mysql.id]?.status == HostStatus.RUNNING

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Databases", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "Schemas served by the MariaDB runtime on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                IconButton(
                    onClick = { viewModel.refreshDatabases() },
                    enabled = running && !busy,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh databases",
                        tint = if (running) TextSecondary else TextMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        if (mysql == null) {
            item {
                EmptyState(
                    icon = Icons.Filled.Storage,
                    title = "No database server",
                    body = "Create a MariaDB service. The app starts it on loopback, generates a " +
                        "password and lets you manage schemas without any external client.",
                    action = {
                        PrimaryButton(
                            "Add MariaDB",
                            onClick = {
                                viewModel.createHostedSite(
                                    kind = "mysql",
                                    name = "MariaDB",
                                    autoStart = true,
                                )
                            },
                            icon = Icons.Filled.Add,
                        )
                    },
                )
            }
            item {
                PostgresCard(postgres, states)
            }
            return@LazyColumn
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteGlyph("mysql")
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MariaDB", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            version ?: "127.0.0.1:${mysql.port}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HostPill(statusLabel(states[mysql.id] ?: HostServiceState(port = mysql.port)), statusColor(states[mysql.id] ?: HostServiceState()))
                }
                Spacer(Modifier.height(8.dp))
                HostValueRow(label = "Host", value = "127.0.0.1")
                HostValueRow(label = "Port", value = mysql.port.toString())
                HostValueRow(label = "User", value = mysql.username.ifBlank { "9router" })
                HostValueRow(label = "Password", value = mysql.password, secret = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton(
                        text = if (running) "Stop" else "Start",
                        onClick = {
                            if (running) viewModel.stopHostedService(mysql.id)
                            else viewModel.startHostedService(mysql.id)
                        },
                        modifier = Modifier.weight(1f),
                        icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        color = if (running) Rose else Emerald,
                    )
                    PrimaryButton(
                        text = "New database",
                        onClick = { createOpen = true },
                        enabled = running,
                        icon = Icons.Filled.Add,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            HostingSection(
                "Schemas",
                subtitle = when {
                    !running -> "Start MariaDB to list databases"
                    busy -> "Reading information_schema…"
                    databases.isEmpty() -> "No schemas yet"
                    else -> "${databases.count { it.user }} user · ${databases.count { !it.user }} system"
                },
                trailing = {
                    if (busy) {
                        Text("Loading", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                },
            ) {
                if (databases.isEmpty()) {
                    Text(
                        if (running) "Create the first database — it appears here immediately."
                        else "The server is stopped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else {
                    databases.forEachIndexed { index, db ->
                        if (index > 0) {
                            Spacer(Modifier.height(6.dp))
                            HairLine()
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = null,
                                tint = if (db.user) Brand else TextMuted,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    db.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = if (db.user) TextPrimary else TextSecondary,
                                )
                                Text(
                                    if (db.user) HostStorage.formatBytes(db.bytes) else "system schema",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            if (db.user) {
                                IconButton(
                                    onClick = { dropFor = db.name },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Drop ${db.name}",
                                        tint = Rose,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            HostingSection(
                "Site databases",
                subtitle = "Attach a schema to a site and inject DB_HOST, DB_NAME, DB_USER, " +
                    "DB_PASSWORD and DATABASE_URL",
            ) {
                val web = services.filter { it.spec?.web == true }
                if (web.isEmpty()) {
                    Text(
                        "Create a website first; its schema is named after the site id.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else {
                    web.forEachIndexed { index, service ->
                        if (index > 0) {
                            Spacer(Modifier.height(6.dp))
                            HairLine()
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SiteGlyph(service.kind, 32)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    service.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                )
                                Text(
                                    service.dbName.ifBlank { SiteDatabases.siteDatabase(service.id) },
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (service.dbName.isBlank()) TextMuted else Emerald,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.attachSiteDatabase(service.id) },
                                enabled = running,
                            ) {
                                Text(
                                    if (service.dbName.isBlank()) "Attach" else "Refresh",
                                    color = if (running) Brand else TextMuted,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { PostgresCard(postgres, states) }
    }

    if (createOpen) {
        CreateDatabaseDialog(
            suggested = "app_${System.currentTimeMillis().toString().takeLast(4)}",
            onDismiss = { createOpen = false },
            onCreate = { name ->
                viewModel.createDatabase(name)
                createOpen = false
            },
        )
    }

    if (dropFor != null) {
        ConfirmDialog(
            title = "Drop ${dropFor!!}?",
            message = "Every table and row in this schema is deleted. This cannot be undone.",
            onConfirm = {
                viewModel.dropDatabase(dropFor!!)
                dropFor = null
            },
            onDismiss = { dropFor = null },
        )
    }
}

@Composable
private fun PostgresCard(postgres: HostedService?, states: Map<String, HostServiceState>) {
    if (postgres == null) return
    val state = states[postgres.id] ?: HostServiceState(port = postgres.port)
    HostingSection(
        "PostgreSQL",
        subtitle = "Served from this device; schema administration stays in the connection details",
    ) {
        HostValueRow(label = "Host", value = "127.0.0.1")
        HostValueRow(label = "Port", value = postgres.port.toString())
        HostValueRow(label = "Database", value = postgres.username.ifBlank { "postgres" })
        HostValueRow(label = "Password", value = postgres.password, secret = true)
        Spacer(Modifier.height(4.dp))
        Text(
            "No psql or pdo_pgsql ships with the app, so SQL is not run from this screen. " +
                "Apps still connect with these values.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        HostPill(statusLabel(state), statusColor(state))
    }
}

@Composable
private fun CreateDatabaseDialog(
    suggested: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf(suggested) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("New database") },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it.lowercase(); error = null },
                    label = "Schema name",
                    placeholder = "app_production",
                    singleLine = true,
                    supportingText = "Letters, digits and underscores; created with utf8mb4.",
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching { SiteDatabases.normalize(name) }
                        .onSuccess { onCreate(it) }
                        .onFailure { error = it.message }
                },
            ) {
                Text("Create", color = Brand, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}

/* ------------------------------------------------------------------ */
/* FTP                                                                 */
/* ------------------------------------------------------------------ */

@Composable
internal fun FtpTab(
    viewModel: MainViewModel,
    services: List<HostedService>,
    saved: FtpHostSettings,
    state: FtpHostState,
) {
    val context = LocalContext.current
    var draft by remember(saved) { mutableStateOf(saved) }
    var accountOpen by remember { mutableStateOf(false) }
    var removeFor by remember { mutableStateOf<FtpAccount?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FTP", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "File access for you and for site owners",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                HostPill(
                    if (state.running) "Running" else if (state.status == ai.ninerouter.core.FtpHostStatus.ERROR) "Error" else "Stopped",
                    if (state.running) Emerald else if (state.status == ai.ninerouter.core.FtpHostStatus.ERROR) Rose else TextMuted,
                )
            }
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FTP server", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            "Binds to loopback only; publish it through a tunnel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = {
                            draft = draft.copy(enabled = it)
                            viewModel.saveFtpHost(draft)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Brand,
                            checkedBorderColor = Brand,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = AppSurfaceHigh,
                            uncheckedBorderColor = AppBorder,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = draft.port.toString(),
                    onValueChange = { value ->
                        val port = value.filter(Char::isDigit).take(5).toIntOrNull()
                        draft = draft.copy(port = port ?: FtpHostStore.DEFAULT_PORT)
                    },
                    label = "Control port",
                    singleLine = true,
                    supportingText = "Default ${FtpHostStore.DEFAULT_PORT}; passive data ports " +
                        "${FtpHostStore.PASSIVE_PORTS} are opened too.",
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = draft.username,
                    onValueChange = { draft = draft.copy(username = it) },
                    label = "Primary username",
                    singleLine = true,
                    supportingText = "The account with full access to the root folder.",
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = draft.password,
                    onValueChange = { draft = draft.copy(password = it) },
                    label = "Primary password",
                    singleLine = true,
                    supportingText = "Blank allows anonymous access (not recommended).",
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read-only", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Uploads and deletes are rejected for the primary account.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Switch(
                        checked = draft.readOnly,
                        onCheckedChange = { draft = draft.copy(readOnly = it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Brand,
                            checkedBorderColor = Brand,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = AppSurfaceHigh,
                            uncheckedBorderColor = AppBorder,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Root folder",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Text(
                    draft.root.ifBlank { FtpHostManagerRoot(context) },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (draft.root.isNotBlank()) {
                    TextButton(onClick = { draft = draft.copy(root = "") }) {
                        Text("Use the app folder", color = Brand, style = MaterialTheme.typography.labelMedium)
                    }
                }
                MetaRow("Serving", state.root.ifBlank { FtpHostManagerRoot(context) })
                MetaRow("Data ports", FtpHostStore.PASSIVE_PORTS)
                if (state.message.isNotBlank() && !state.running) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                    )
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    "Save FTP host",
                    onClick = { viewModel.saveFtpHost(draft) },
                    icon = Icons.Filled.Lock,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            HostingSection(
                "Accounts",
                subtitle = "Each account gets its own folder and permissions",
                trailing = {
                    TextButton(
                        onClick = { accountOpen = true },
                        enabled = draft.accounts.size < FtpHostStore.MAX_ACCOUNTS,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (draft.accounts.size < FtpHostStore.MAX_ACCOUNTS) Brand else TextMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "Add",
                            color = if (draft.accounts.size < FtpHostStore.MAX_ACCOUNTS) Brand else TextMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            ) {
                if (draft.accounts.isEmpty()) {
                    Text(
                        "Only the primary account exists. Add a login per site so an owner " +
                            "sees just that site's folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else {
                    draft.accounts.forEachIndexed { index, account ->
                        if (index > 0) {
                            Spacer(Modifier.height(6.dp))
                            HairLine()
                            Spacer(Modifier.height(6.dp))
                        }
                        val owner = services.firstOrNull { it.id == account.serviceId }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Brand.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    account.username.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Brand,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    account.username,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                )
                                Text(
                                    buildString {
                                        append(if (account.readOnly) "read-only" else "read/write")
                                        append(" · ")
                                        append(owner?.name ?: account.root.ifBlank { "host root" })
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { removeFor = account },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove ${account.username}",
                                    tint = Rose,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            HostingSection("Connect") {
                MetaRow("Host", "127.0.0.1")
                MetaRow("Port", draft.port.toString())
                MetaRow(
                    "Users",
                    "${1 + draft.accounts.size} · ${draft.accounts.count { it.readOnly }} read-only",
                )
                MetaRow(
                    "Tunnel",
                    "Publish with a TCP link on 127.0.0.1:${draft.port}",
                )
            }
        }
    }

    if (accountOpen) {
        FtpAccountDialog(
            services = services,
            existing = draft.accounts.map { it.username },
            onDismiss = { accountOpen = false },
            onCreate = { account ->
                draft = draft.copy(accounts = draft.accounts + account)
                viewModel.saveFtpHost(draft)
                accountOpen = false
            },
        )
    }

    if (removeFor != null) {
        ConfirmDialog(
            title = "Remove ${removeFor!!.username}?",
            message = "The login stops working immediately. Files in its folder are untouched.",
            onConfirm = {
                draft = draft.copy(accounts = draft.accounts - removeFor!!)
                viewModel.saveFtpHost(draft)
                removeFor = null
            },
            onDismiss = { removeFor = null },
        )
    }
}

private fun FtpHostManagerRoot(context: android.content.Context): String =
    "${context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath}/ftp"

@Composable
private fun FtpAccountDialog(
    services: List<HostedService>,
    existing: List<String>,
    onDismiss: () -> Unit,
    onCreate: (FtpAccount) -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(FtpHostStore.newPassword()) }
    var readOnly by remember { mutableStateOf(false) }
    var siteId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("New FTP account") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                AppTextField(
                    value = username,
                    onValueChange = { username = it.trim(); error = null },
                    label = "Username",
                    placeholder = "studio",
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = "Password",
                    singleLine = true,
                    supportingText = "Generated; replace it with your own if you prefer.",
                )
                Spacer(Modifier.height(8.dp))
                Text("Folder", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                val options = listOf("Host root" to "") + services.filter { it.spec?.web == true }
                    .map { it.name to it.id }
                options.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (label, id) ->
                            HostingChoice(
                                title = label,
                                subtitle = if (id.isBlank()) "primary area" else "site files",
                                selected = siteId == id,
                                modifier = Modifier.weight(1f),
                                onClick = { siteId = id },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read-only", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "This login can browse and download but not upload.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Switch(
                        checked = readOnly,
                        onCheckedChange = { readOnly = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Brand,
                            checkedBorderColor = Brand,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = AppSurfaceHigh,
                            uncheckedBorderColor = AppBorder,
                        ),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = username.trim()
                    when {
                        name.isBlank() -> error = "Enter a username."
                        existing.any { it.equals(name, true) } -> error = "That username is already used."
                        password.isBlank() -> error = "Set a password."
                        else -> onCreate(
                            FtpAccount(
                                username = name,
                                password = password,
                                readOnly = readOnly,
                                serviceId = siteId,
                            ),
                        )
                    }
                },
            ) {
                Text("Create", color = Brand, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}

/* ------------------------------------------------------------------ */
/* SSL                                                                 */
/* ------------------------------------------------------------------ */

@Composable
internal fun SslTab(viewModel: MainViewModel, services: List<HostedService>) {
    val context = LocalContext.current
    val web = services.filter { it.spec?.web == true }
    var certificates by remember { mutableStateOf<Map<String, SiteTls.Certificate?>>(emptyMap()) }
    var confirmFor by remember { mutableStateOf<HostedService?>(null) }
    var removeFor by remember { mutableStateOf<HostedService?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(web.map { it.id + it.domain + it.active }, reload) {
        val result = withContext(Dispatchers.IO) {
            web.associate { service ->
                service.id to SiteTls.read(
                    SiteTls.certFile(HostingManager.layout(context, service)),
                )
            }
        }
        certificates = result
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Column {
                Text("SSL & TLS", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(
                    "Self-signed certificates for local HTTPS and for apps that need a pair",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Brand, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("How TLS works here", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Certificates are generated on-device with the bundled OpenSSL and stored in " +
                        "the site's data folder. Public visitors still get Cloudflare's trusted " +
                        "certificate at the tunnel edge; the local pair is injected as TLS_CERT, " +
                        "TLS_KEY and SSL_CERT_FILE so Node, Python and PHP can trust or serve it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        if (web.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Lock,
                    title = "No websites yet",
                    body = "Create a static, PHP, Node.js or Python site to issue a certificate.",
                )
            }
        }

        items(web, key = { it.id }) { service ->
            val certificate = certificates[service.id]
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteGlyph(service.kind)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(service.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            service.domain.ifBlank { "${service.id}.localhost" },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HostPill(
                        when {
                            certificate == null -> "None"
                            certificate.expired -> "Expired"
                            else -> "${certificate.daysLeft}d"
                        },
                        when {
                            certificate == null -> TextMuted
                            certificate.expired -> Rose
                            certificate.daysLeft < 21 -> ai.ninerouter.ui.theme.Amber
                            else -> Emerald
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
                if (certificate == null) {
                    Text(
                        "No certificate stored for this site.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                } else {
                    HostValueRow(
                        label = "Subject",
                        value = certificate.subject.substringAfter("CN=", certificate.subject),
                    )
                    HostValueRow(label = "SHA-256", value = certificate.sha256)
                    Text(
                        "Valid until ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(certificate.notAfter))}" +
                            if (certificate.names.isNotEmpty()) " · ${certificate.names.joinToString(", ")}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton(
                        text = if (certificate == null) "Generate" else "Renew",
                        onClick = {
                            viewModel.generateSiteCertificate(service.id)
                            reload++
                        },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Lock,
                    )
                    if (certificate != null) {
                        GhostButton(
                            text = "Remove",
                            onClick = { removeFor = service },
                            modifier = Modifier.weight(1f),
                            color = Rose,
                            icon = Icons.Filled.Delete,
                        )
                    }
                }
            }
        }

        item {
            HostingSection("Trust on a client") {
                Text(
                    "Download the certificate from the site folder (data/tls/cert.pem) and add it " +
                        "to the client trust store to remove the browser warning for local previews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                val clipboard = LocalClipboardManager.current
                val anyDomain = web.firstOrNull { it.domain.isNotBlank() }?.domain
                if (anyDomain != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "openssl s_client -connect $anyDomain:443",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString("openssl s_client -connect $anyDomain:443"))
                        }) {
                            Text("Copy", color = Brand, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (confirmFor != null) {
        ConfirmDialog(
            title = "Renew certificate?",
            message = "A new key pair replaces the old one. Running apps are restarted to pick " +
                "up TLS_CERT and TLS_KEY.",
            onConfirm = {
                viewModel.generateSiteCertificate(confirmFor!!.id)
                confirmFor = null
                reload++
            },
            onDismiss = { confirmFor = null },
        )
    }

    if (removeFor != null) {
        ConfirmDialog(
            title = "Remove certificate?",
            message = "The site keeps serving over HTTP; apps that read TLS_CERT fall back to " +
                "their defaults.",
            onConfirm = {
                viewModel.deleteSiteCertificate(removeFor!!.id)
                removeFor = null
                reload++
            },
            onDismiss = { removeFor = null },
        )
    }
}

/* ------------------------------------------------------------------ */
/* Storage                                                             */
/* ------------------------------------------------------------------ */

@Composable
internal fun StorageTab(
    services: List<HostedService>,
    states: Map<String, HostServiceState>,
    onFiles: (HostedService) -> Unit,
) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf<Map<String, LongArray>>(emptyMap()) }
    var free by remember { mutableStateOf(0L) }

    LaunchedEffect(services, states.size) {
        val result = withContext(Dispatchers.IO) {
            services.associate { service ->
                val layout = HostingManager.layout(context, service)
                service.id to longArrayOf(
                    HostStorage.usage(layout.content),
                    HostStorage.usage(layout.data),
                    HostStorage.usage(layout.logs),
                )
            } to HostStorage.freeBytes(context.filesDir)
        }
        usage = result.first
        free = result.second
    }

    val total = usage.values.sumOf { it.sum() }
    val largest = usage.values.maxOfOrNull { it.sum() }?.coerceAtLeast(1L) ?: 1L

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Column {
                Text("Storage", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(
                    "Releases, data folders and logs for every hosted runtime",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostMetricTile("Hosting", HostStorage.formatBytes(total), "on disk", Brand, Modifier.weight(1f))
                HostMetricTile("Free", HostStorage.formatBytes(free), "device", Emerald, Modifier.weight(1f))
                HostMetricTile("Sites", services.count { it.spec?.web == true }.toString(), "web", TextSecondary, Modifier.weight(1f))
            }
        }

        if (services.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Storage,
                    title = "Nothing stored yet",
                    body = "Sites, database files and releases appear here once you create a runtime.",
                )
            }
        }

        items(services, key = { it.id }) { service ->
            val sizes = usage[service.id] ?: longArrayOf(0, 0, 0)
            val siteTotal = sizes.sum()
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteGlyph(service.kind, 34)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(service.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "${runtimeLabel(service.kind)} · ${HostStorage.formatBytes(siteTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    IconButton(onClick = { onFiles(service) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = "Open ${service.name} files",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HostUsageBar(
                    fraction = siteTotal.toFloat() / largest.toFloat(),
                    color = runtimeColor(service.kind),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "content ${HostStorage.formatBytes(sizes[0])}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Text(
                        "data ${HostStorage.formatBytes(sizes[1])}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Text(
                        "logs ${HostStorage.formatBytes(sizes[2])}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    if (service.releases.isNotEmpty()) {
                        Text(
                            "${service.releases.size} releases",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }

        item {
            HostingSection("Where files live") {
                MetaRow("Sites", context.filesDir.absolutePath + "/hosting/<site>/public")
                MetaRow("Releases", context.filesDir.absolutePath + "/hosting/<site>/releases")
                MetaRow("Data & TLS", context.filesDir.absolutePath + "/hosting/<site>/data")
                MetaRow("Logs", context.filesDir.absolutePath + "/hosting/<site>/logs")
                Spacer(Modifier.height(6.dp))
                Text(
                    "The file manager edits the active release directly; each deploy still creates " +
                        "an immutable folder you can roll back to.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}
