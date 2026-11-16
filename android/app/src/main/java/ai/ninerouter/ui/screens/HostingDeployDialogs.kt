package ai.ninerouter.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.HostRelease
import ai.ninerouter.core.HostStorage
import ai.ninerouter.core.HostedService
import ai.ninerouter.core.NpmRuntime
import ai.ninerouter.ui.components.AppTextField
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
import java.text.DateFormat
import java.util.Date

/** Deployment history with activate/rollback and delete. */
@Composable
internal fun HostReleasesDialog(
    service: HostedService,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("${service.name} releases") },
        text = {
            Column {
                Text(
                    "Each deploy is kept as an immutable folder. Activate an older " +
                        "release to roll back; the last 5 are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                if (service.releases.isEmpty()) {
                    Text(
                        "No releases yet. Use Deploy ZIP to upload the project.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(service.releases, key = { it.id }) { release ->
                        ReleaseRow(
                            release = release,
                            active = release.id == service.active,
                            created = format.format(Date(release.createdAt)),
                            onActivate = { onActivate(release.id) },
                            onDelete = { onDelete(release.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Brand) }
        },
    )
}

@Composable
private fun ReleaseRow(
    release: HostRelease,
    active: Boolean,
    created: String,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Release ${release.id}" + if (active) " · active" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (active) Emerald else TextPrimary,
            )
            Text(
                "$created · ${HostStorage.formatBytes(release.bytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        if (!active) {
            TextButton(onClick = onActivate) {
                Text("Activate", color = Brand, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = Rose, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Environment variables and the optional build command for a web runtime. */
@Composable
internal fun HostEnvDialog(
    service: HostedService,
    onSave: (Map<String, String>, String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(service.id) {
        mutableStateOf(service.env.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var build by remember(service.id) { mutableStateOf(service.build) }
    var npmInstall by remember(service.id) { mutableStateOf(service.npmInstall) }
    var npmBuild by remember(service.id) { mutableStateOf(service.npmBuild) }
    var error by remember(service.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("${service.name} environment") },
        text = {
            Column {
                Text(
                    "One NAME=value per line. PORT, HOST, DOCROOT, PATH and " +
                        "LD_LIBRARY_PATH are set by the runtime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Environment",
                    placeholder = "API_URL=https://example.com",
                    singleLine = false,
                    minLines = 4,
                    maxLines = 8,
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = build,
                    onValueChange = { build = it },
                    label = "Build command (optional)",
                    placeholder = "npm install && npm run build",
                    supportingText = "Runs inside the new release before it is activated. " +
                        "node, npm, npx and node_modules/.bin use the bundled runtimes. " +
                        "npm install -g writes to files/npm/global.",
                )
                if (service.kind == "node") {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "npm · ${NpmRuntime.VERSION} bundled",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Deploy installs package.json dependencies and can run the build script. " +
                            "The runtime port is detected from npm and child Node processes. " +
                            "Global installs use files/npm/global, with its bin folder on PATH.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Install dependencies", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text(
                                "npm ci with a lockfile, otherwise npm install",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = npmInstall,
                            onCheckedChange = { npmInstall = it },
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Run npm build", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text(
                                "Only when package.json has a build script and no custom command is set",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = npmBuild,
                            onCheckedChange = { npmBuild = it },
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
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Rose)
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Save",
                onClick = {
                    val env = linkedMapOf<String, String>()
                    var bad: String? = null
                    text.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { line ->
                            val at = line.indexOf('=')
                            if (at <= 0) {
                                bad = "Use NAME=value on every line."
                            } else {
                                env[line.substring(0, at).trim()] = line.substring(at + 1)
                            }
                        }
                    if (bad != null) {
                        error = bad
                    } else {
                        onSave(env, build.trim(), npmInstall, npmBuild)
                        onDismiss()
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        },
    )
}
