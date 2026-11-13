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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import ai.ninerouter.ui.theme.Sky
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

@Composable
fun TelegramScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val combos by viewModel.combos.collectAsState()

    var token by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var systemPrompt by remember { mutableStateOf("") }
    var defaultModel by remember { mutableStateOf("") }
    var combo by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshSettings() }
    LaunchedEffect(settings) {
        if (!loaded && settings.isNotEmpty()) {
            token = settings["telegram_bot_token"].orEmpty()
            chatId = settings["telegram_chat_id"].orEmpty()
            enabled = settings["telegram_enabled"] == "true"
            notifications = settings["telegram_notifications"] != "false"
            systemPrompt = settings["telegram_system_prompt"].orEmpty()
            defaultModel = settings["telegram_default_model"].orEmpty()
            combo = settings["telegram_combo"].orEmpty()
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
                title = "Telegram Bot",
                subtitle = "Long polling · no webhook · no WebView",
                trailing = {
                    StatusPill(
                        if (enabled && token.isNotBlank() && chatId.isNotBlank()) "Configured" else "Off",
                        if (enabled && token.isNotBlank()) Emerald else TextMuted
                    )
                }
            )
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Sky.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, tint = Sky, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bot bridge", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Chat with the gateway from Telegram",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Emerald.copy(alpha = 0.5f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = AppSurfaceHigh,
                            uncheckedBorderColor = AppBorder
                        )
                    )
                }
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = "Bot token",
                    placeholder = "123456:ABC-DEF…",
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
                    supportingText = "Create a bot with @BotFather, then paste the token here."
                )
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = "Owner chat id",
                    placeholder = "123456789",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = "Used for proactive alerts and the test message. Send /start to the bot, or use @userinfobot to find the id."
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Proactive notifications", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Alert the owner when every connection is cooling down",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = notifications,
                        onCheckedChange = { notifications = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = Brand.copy(alpha = 0.5f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = AppSurfaceHigh,
                            uncheckedBorderColor = AppBorder
                        )
                    )
                }
            }
        }
        item {
            AppCard {
                Text("Chat routing", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "The bot uses the same engine as /v1/chat/completions. Pick a combo or a specific model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = defaultModel,
                    onValueChange = {
                        defaultModel = it
                        if (it.isNotBlank()) combo = ""
                    },
                    label = "Default model",
                    placeholder = "antigravity/gemini-3.8-flash-medium"
                )
                Spacer(Modifier.height(10.dp))
                Text("Combo", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                if (combos.isEmpty()) {
                    Text("No combos yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        combos.forEach { item ->
                            val selected = combo == item.name
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) Brand.copy(alpha = 0.13f) else AppSurfaceHigh)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) Brand else TextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (item.kind == "vision") "vision" else "llm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Spacer(Modifier.width(10.dp))
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        combo = if (selected) "" else item.name
                                        if (!selected) defaultModel = ""
                                    }
                                ) {
                                    Text(if (selected) "Selected" else "Use", color = if (selected) Brand else TextSecondary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                AppTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = "System prompt",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "Save",
                    onClick = {
                        viewModel.saveSettings(
                            mapOf(
                                "telegram_bot_token" to token.trim(),
                                "telegram_chat_id" to chatId.trim(),
                                "telegram_enabled" to enabled.toString(),
                                "telegram_notifications" to notifications.toString(),
                                "telegram_system_prompt" to systemPrompt,
                                "telegram_default_model" to defaultModel.trim(),
                                "telegram_combo" to combo.trim()
                            ),
                            "Telegram settings saved"
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                GhostButton(
                    text = "Send test",
                    onClick = { viewModel.sendTelegramTest(token.trim(), chatId.trim()) },
                    icon = Icons.Filled.Send,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            AppCard {
                Text("Commands", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                CommandRow("/help", "Show the command reference")
                CommandRow("/model", "List models, or /model provider/model")
                CommandRow("/combo", "List combos, or /combo name")
                CommandRow("/vision", "Switch to the vision combo")
                CommandRow("/status", "Gateway and connection status")
                CommandRow("/quota", "Today's usage per provider")
                CommandRow("/clear", "Reset the conversation")
                Spacer(Modifier.height(6.dp))
                HairLine()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Plain text messages are routed through the selected combo/model and keep a short per-chat history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        item {
            AppCard {
                Text("Setup", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Step(1, "Create a bot with @BotFather and copy its token.")
                Step(2, "Paste the token and your chat id above, then enable the bridge.")
                Step(3, "Save. The foreground service starts polling automatically and the bot answers within a few seconds.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "The polling loop also removes a stale webhook if one exists, so a bot migrated from Vercel keeps working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun CommandRow(command: String, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Brand,
            modifier = Modifier.width(78.dp)
        )
        Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            Modifier
                .size(19.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brand.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelSmall, color = Brand)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
