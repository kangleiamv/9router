package ai.ninerouter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.HostingAiSession
import ai.ninerouter.core.HostingAiStatus
import ai.ninerouter.core.HostingAiStep
import ai.ninerouter.core.HostingAiTone
import ai.ninerouter.core.HostedService
import ai.ninerouter.data.model.Combo
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary

/**
 * The user-facing side of the hosting doctor. The transcript is intentionally
 * compact: full file contents go to the model, while the user sees what was
 * inspected, edited and restarted.
 */
@Composable
internal fun HostingAiDialog(
    service: HostedService,
    session: HostingAiSession?,
    defaultModel: String,
    combos: List<Combo>,
    onStart: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember(service.id, defaultModel) { mutableStateOf(defaultModel) }
    val running = session?.status == HostingAiStatus.RUNNING

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Brand,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Fix with AI", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        service.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                if (session == null) {
                    Text(
                        "The agent reads this site's logs and active release, edits source files, " +
                            "restarts the service and checks the result. Changes are backed up in " +
                            "the app's site folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    AppTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = "Model or combo",
                        placeholder = "Automatic · first configured provider",
                        supportingText = "Uses the provider connection already configured in 9Router.",
                        trailingIcon = if (model.isNotBlank()) {
                            {
                                TextButton(onClick = { model = "" }) {
                                    Text("Auto", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            null
                        },
                    )
                    if (combos.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Combos", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            combos.take(8).forEach { combo ->
                                TextButton(onClick = { model = combo.name }) {
                                    Text(
                                        combo.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (model == combo.name) Brand else TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "The agent only changes the active release. It cannot run arbitrary commands " +
                            "or write secret files.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor(session.status)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            session.model.ifBlank { "No model" },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = TextSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            statusLabel(session.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(session.status),
                        )
                    }
                    if (running) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = Brand,
                            trackColor = AppBorder,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(session.steps) { step -> AiStepRow(step) }
                    }
                    if (session.status != HostingAiStatus.RUNNING) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = AppSurfaceHigh,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    when (session.status) {
                                        HostingAiStatus.DONE -> "Result"
                                        HostingAiStatus.CANCELLED -> "Cancelled"
                                        else -> "Could not finish"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor(session.status),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    session.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                                if (session.status == HostingAiStatus.DONE) {
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        if (session.siteRunning) {
                                            "The site is running on loopback."
                                        } else {
                                            "The site is not running yet. Check the log before retrying."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (session.siteRunning) Emerald else Rose,
                                    )
                                }
                            }
                        }
                        if (session.changedFiles.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Files changed",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                            )
                            session.changedFiles.take(10).forEach { file ->
                                Text(
                                    "· $file",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (session.changedFiles.size > 10) {
                                Text(
                                    "… ${session.changedFiles.size - 10} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                        }
                        if (session.backupDir.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Backup: ${session.backupDir}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (running) {
                    TextButton(onClick = onCancel) {
                        Text("Stop", color = Rose, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(onClick = { onStart(model.trim()) }) {
                        Text(
                            if (session == null) "Start" else "Run again",
                            color = Brand,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    )
}

@Composable
private fun AiStepRow(step: HostingAiStep) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(toneColor(step.tone)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (step.tone == HostingAiTone.ERROR) Rose else TextPrimary,
            )
            if (step.detail.isNotBlank()) {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusLabel(status: HostingAiStatus): String = when (status) {
    HostingAiStatus.RUNNING -> "Working…"
    HostingAiStatus.DONE -> "Finished"
    HostingAiStatus.FAILED -> "Failed"
    HostingAiStatus.CANCELLED -> "Cancelled"
}

private fun statusColor(status: HostingAiStatus) = when (status) {
    HostingAiStatus.RUNNING -> Brand
    HostingAiStatus.DONE -> Emerald
    HostingAiStatus.FAILED -> Rose
    HostingAiStatus.CANCELLED -> TextMuted
}

private fun toneColor(tone: HostingAiTone) = when (tone) {
    HostingAiTone.INFO -> Brand
    HostingAiTone.TOOL -> TextMuted
    HostingAiTone.SUCCESS -> Emerald
    HostingAiTone.ERROR -> Rose
}
