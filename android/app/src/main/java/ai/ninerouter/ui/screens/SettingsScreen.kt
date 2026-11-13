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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import ai.ninerouter.ui.MainViewModel
import ai.ninerouter.ui.components.AppCard
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.GhostButton
import ai.ninerouter.ui.components.HairLine
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.components.SectionHeader
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val running by viewModel.running.collectAsState()
    val port by viewModel.port.collectAsState()
    val requests by viewModel.requests.collectAsState()
    val connections by viewModel.connections.collectAsState()
    val combos by viewModel.combos.collectAsState()

    var portText by remember { mutableStateOf(port.toString()) }
    var autoStart by remember { mutableStateOf(true) }
    var retention by remember { mutableStateOf("7") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshSettings() }
    LaunchedEffect(settings, port) {
        if (!loaded && settings.isNotEmpty()) {
            portText = settings["proxy_port"] ?: port.toString()
            autoStart = settings["auto_start"] != "false"
            retention = settings["retention_days"] ?: "7"
            loaded = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            SectionHeader(
                title = "Settings",
                subtitle = "Gateway, storage and device behaviour",
                trailing = { StatusPill(if (running) "Running" else "Stopped", if (running) Emerald else Rose) }
            )
        }
        item {
            AppCard {
                Text("Gateway", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = "Listen port",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = "Loopback host is fixed to 127.0.0.1. Range 1024–65535."
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-start on boot", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Re-arm the foreground gateway after a reboot",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { autoStart = it },
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
                AppTextField(
                    value = retention,
                    onValueChange = { retention = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = "Log retention (days)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Save & restart",
                        onClick = {
                            val newPort = portText.toIntOrNull()?.coerceIn(1024, 65535) ?: port
                            viewModel.saveSettingsAndRestart(
                                mapOf(
                                    "proxy_port" to newPort.toString(),
                                    "auto_start" to autoStart.toString(),
                                    "retention_days" to (retention.toIntOrNull()?.coerceIn(1, 365) ?: 7).toString()
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    GhostButton(
                        text = "Prune logs",
                        onClick = { viewModel.pruneLogs(retention.toIntOrNull()?.coerceIn(1, 365) ?: 7) },
                        icon = Icons.Filled.Refresh,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item {
            AppCard {
                Text("Storage", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StorageTile("Connections", connections.size.toString(), Modifier.weight(1f))
                    StorageTile("Combos", combos.size.toString(), Modifier.weight(1f))
                    StorageTile("Requests", requests.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                HairLine()
                Spacer(Modifier.height(10.dp))
                Text(
                    "Data lives in a private Room (SQLite) database named nine_router.db. API keys are encrypted with a non-exportable Android Keystore AES-GCM key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = "Clear request log",
                    onClick = { viewModel.clearLogs() },
                    icon = Icons.Filled.Delete,
                    color = Rose,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            AppCard {
                Text("Runtime", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                InfoRow("Proxy port", port.toString())
                InfoRow("Bind address", "127.0.0.1")
                InfoRow("Wire protocol", "OpenAI-compatible")
                InfoRow("Engine", "Ktor CIO + OkHttp")
                InfoRow("UI", "Jetpack Compose · Material 3")
                InfoRow("WebView", "none")
                InfoRow("Version", "1.0.0")
                Spacer(Modifier.height(8.dp))
                Text(
                    "The foreground service keeps the gateway and Telegram polling alive while the app is backgrounded. " +
                        "Android may stop it when the battery is critically low.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        item {
            AppCard {
                Text("About", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "9Router Android is a native port of the 9Router gateway. Same routing rules, account fallback, " +
                        "model locks, combo rotation, API keys, usage tracking, quota view and Telegram bridge — " +
                        "running entirely on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "9router · android 1.0.0",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun StorageTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppSurfaceHigh)
            .padding(10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
