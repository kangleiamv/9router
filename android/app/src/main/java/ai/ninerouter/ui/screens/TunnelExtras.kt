package ai.ninerouter.ui.screens

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.ninerouter.core.FtpHostRuntime
import ai.ninerouter.core.FtpHostSettings
import ai.ninerouter.core.FtpHostState
import ai.ninerouter.core.FtpHostStatus
import ai.ninerouter.core.FtpHostStore
import ai.ninerouter.core.LocalServicePreset
import ai.ninerouter.core.LocalServices
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.MetaRow
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.theme.Amber
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import ai.ninerouter.util.StorageAccess

/**
 * Optional FTP server hosted by the app. The tunnel publishes it through the
 * HTTP file browser, or through a separate Zero Trust TCP hostname.
 */
@Composable
internal fun FtpHostCard(
    initial: FtpHostSettings,
    state: FtpHostState,
    blockedPorts: Set<Int>,
    onSave: (FtpHostSettings) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var portText by remember(initial) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember(initial) { mutableStateOf(initial.password) }
    var root by remember(initial) { mutableStateOf(initial.root) }
    var readOnly by remember(initial) { mutableStateOf(initial.readOnly) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    var hasAccess by remember { mutableStateOf(StorageAccess.hasBroadAccess(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = StorageAccess.hasBroadAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { hasAccess = StorageAccess.hasBroadAccess(context) }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasAccess = StorageAccess.hasBroadAccess(context) }

    fun requestAccess() {
        val intent = StorageAccess.allFilesIntent(context)
        if (intent != null) {
            settingsLauncher.launch(intent)
        } else {
            val permissions = StorageAccess.legacyPermissions()
            if (permissions.isNotEmpty()) legacyLauncher.launch(permissions)
        }
    }

    val statusColor = when (state.status) {
        FtpHostStatus.RUNNING -> Emerald
        FtpHostStatus.STARTING -> Amber
        FtpHostStatus.ERROR -> Rose
        FtpHostStatus.STOPPED -> TextMuted
    }
    val statusText = when (state.status) {
        FtpHostStatus.RUNNING -> "Serving"
        FtpHostStatus.STARTING -> "Starting"
        FtpHostStatus.ERROR -> "Error"
        FtpHostStatus.STOPPED -> "Off"
    }
    val sharedRoot = StorageAccess.isSharedStorage(root)
    val port = portText.toIntOrNull()
    val portIssue = when {
        port == null -> "Port must be a number."
        port !in FtpHostStore.MIN_PORT..65535 -> "Port must be between ${FtpHostStore.MIN_PORT} and 65535."
        port in blockedPorts -> "That port belongs to the 9Router gateway."
        FtpHostStore.isPassivePort(port) -> "That port range is reserved for FTP data connections."
        else -> null
    }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Storage,
                contentDescription = null,
                tint = Brand,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("FTP host on this device", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    "Serve a folder from the phone. The server binds to loopback only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
            StatusPill(statusText, statusColor)
        }

        if (state.status == FtpHostStatus.RUNNING) {
            Spacer(Modifier.height(10.dp))
            MetaRow("FTP target", FtpHostRuntime.target(state.port.takeIf { it > 0 } ?: 2121))
            MetaRow("Folder", state.root.ifBlank { "app folder" })
            MetaRow("Access", if (state.readOnly) "Read-only" else "Read and write")
        }
        if (state.status == FtpHostStatus.ERROR) {
            Spacer(Modifier.height(8.dp))
            Text(state.message.ifBlank { "The FTP host stopped." }, style = MaterialTheme.typography.bodySmall, color = Rose)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Run with the gateway", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Text(
                    "Starts and stops with the local proxy service.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it },
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

        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = portText,
            onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
            label = "Control port",
            placeholder = "2121",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = "Data connections use ${FtpHostStore.PASSIVE_PORTS}.",
        )
        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = username,
            onValueChange = { username = it },
            label = "Username",
            placeholder = "android",
            singleLine = true,
            supportingText = "Leave blank for anonymous FTP.",
        )
        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = "Stored with the Android Keystore.",
        )
        Spacer(Modifier.height(10.dp))
        AppTextField(
            value = root,
            onValueChange = { root = it },
            label = "Folder",
            placeholder = "App folder (no permission needed)",
            singleLine = true,
            supportingText = if (root.isBlank()) {
                "Defaults to Android/data/ai.ninerouter/files/ftp."
            } else {
                root
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = "App folder",
                onClick = { root = ""; error = null },
                icon = Icons.Filled.Folder,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "Shared storage",
                onClick = {
                    root = Environment.getExternalStorageDirectory().absolutePath
                    error = null
                },
                icon = Icons.Filled.Storage,
                modifier = Modifier.weight(1f),
            )
        }
        if (sharedRoot && !hasAccess) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Shared storage needs All files access. Without it, use the app folder.",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
            )
            Spacer(Modifier.height(6.dp))
            GhostButton(
                text = "Grant file access",
                onClick = { requestAccess() },
                icon = Icons.Filled.Folder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read-only", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Text(
                    "Clients can list and download but not upload or delete.",
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
        if (error != null || portIssue != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error ?: portIssue.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = Rose,
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = if (enabled) "Save and start FTP host" else "Save FTP host",
            onClick = {
                if (portIssue != null) {
                    error = portIssue
                    return@PrimaryButton
                }
                error = null
                onSave(
                    FtpHostSettings(
                        enabled = enabled,
                        port = port ?: 2121,
                        username = username,
                        password = password,
                        root = root,
                        readOnly = readOnly,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Then add an FTP local link with target ${FtpHostRuntime.target(port ?: 2121)}. " +
                "Quick FTP shows the browser; Named FTP can also route native FTP.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}


/** Horizontal chips that fill the editor from a service preset. */
@Composable
internal fun PresetChips(
    items: List<LocalServicePreset>,
    onPick: (LocalServicePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { preset ->
            Surface(
                onClick = { onPick(preset) },
                shape = RoundedCornerShape(50),
                color = AppSurfaceHigh,
                border = BorderStroke(1.dp, AppBorder),
            ) {
                Text(
                    preset.name,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
        }
    }
}

/** Port scan results with a one-tap way to fill the link editor. */
@Composable
internal fun DetectedServices(
    detected: Set<Int>?,
    scanning: Boolean,
    onScan: () -> Unit,
    onPick: (LocalServicePreset) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Running services",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onScan, enabled = !scanning) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = if (scanning) TextMuted else Brand,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (scanning) "Scanning…" else "Detect",
                color = if (scanning) TextMuted else Brand,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    when {
        detected == null -> Text(
            "Probe common web, FTP and database ports on loopback.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )

        detected.isEmpty() -> Text(
            "No catalog service is listening on loopback right now.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )

        else -> detected.sorted().forEach { port ->
            val preset = LocalServices.byPort(port)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        preset?.name ?: "Local service",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                    )
                    Text(
                        "127.0.0.1:$port · listening",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Emerald,
                    )
                }
                if (preset != null) {
                    TextButton(onClick = { onPick(preset) }) {
                        Text("Use", color = Brand, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Small "listening" marker used by the link rows. */
@Composable
internal fun ListeningBadge(port: Int, detected: Set<Int>?) {
    if (detected == null) return
    val live = port in detected
    Spacer(Modifier.height(4.dp))
    Text(
        if (live) "Listening on 127.0.0.1:$port" else "Nothing is listening on 127.0.0.1:$port",
        style = MaterialTheme.typography.labelSmall,
        color = if (live) Emerald else Amber,
    )
}
