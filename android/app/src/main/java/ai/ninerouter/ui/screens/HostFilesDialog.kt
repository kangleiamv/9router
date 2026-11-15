package ai.ninerouter.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.ninerouter.core.HostFileEntry
import ai.ninerouter.core.HostStorage
import ai.ninerouter.ui.components.AppTextField
import ai.ninerouter.ui.components.PrimaryButton
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024

/**
 * Browse and edit the files a hosted service serves. Only `public` is opened
 * here; database folders stay private to their server.
 */
@Composable
internal fun HostFilesDialog(
    title: String,
    root: File,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<File?>(null) }
    var text by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<HostFileEntry?>(null) }

    val dir = remember(path, reload) { HostStorage.resolve(root, path) ?: root }
    val entries = remember(dir, reload) { HostStorage.entries(dir) }

    fun changed() {
        reload++
        onChanged()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val display = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    val name = (display ?: "upload.bin")
                        .substringAfterLast('/')
                        .ifBlank { "upload.bin" }
                    val next = if (path.isBlank()) name else "$path/$name"
                    val target = HostStorage.resolve(root, next)
                        ?: error("That file name cannot be stored here.")
                    if (target.isDirectory) error("A folder with that name already exists.")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    written += read
                                    if (written > MAX_UPLOAD_BYTES) {
                                        error("Uploads are limited to 64 MB.")
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        } ?: error("Could not read the picked file.")
                    } catch (t: Throwable) {
                        // Never leave a truncated file where the runtime can
                        // start serving it.
                        runCatching { target.delete() }
                        throw t
                    }
                }.exceptionOrNull()?.message
            }
            if (failure != null) error = failure else changed()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (path.isBlank()) "Hosted files · public" else "public/$path",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("Upload", color = Brand, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = {
                        folderName = ""
                        creating = true
                    }) {
                        Text("New folder", color = Brand, style = MaterialTheme.typography.labelMedium)
                    }
                    if (path.isNotBlank()) {
                        TextButton(onClick = {
                            path = path.substringBeforeLast('/', "")
                            error = null
                        }) {
                            Text("Up", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Rose)
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                "This folder is empty. Upload a file to start serving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(entries, key = { it.name }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    error = null
                                    val next = if (path.isBlank()) entry.name else "$path/${entry.name}"
                                    if (entry.directory) {
                                        path = next
                                    } else {
                                        val file = HostStorage.resolve(root, next)
                                        when {
                                            file == null -> error = "That name cannot be opened."
                                            HostStorage.isEditable(file) -> {
                                                editing = file
                                                text = runCatching { file.readText() }
                                                    .getOrElse {
                                                        error = it.message
                                                        ""
                                                    }
                                            }
                                            else -> error =
                                                "Too large or binary for the editor. Upload a replacement."
                                        }
                                    }
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name + if (entry.directory) "/" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (entry.directory) Brand else TextPrimary,
                                    maxLines = 1,
                                )
                                Text(
                                    if (entry.directory) "folder"
                                    else HostStorage.formatBytes(entry.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            TextButton(onClick = { deleting = entry }) {
                                Text("Delete", color = Rose, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Brand) }
        },
    )

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = AppSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("New folder") },
            text = {
                AppTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = "Folder name",
                    placeholder = "assets",
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "Create",
                    onClick = {
                        val name = folderName.trim()
                        val next = if (path.isBlank()) name else "$path/$name"
                        val target = HostStorage.resolve(root, next)
                        if (name.isBlank() || name.contains('/') || target == null) {
                            error = "Enter a plain folder name."
                        } else if (!target.mkdirs() && !target.isDirectory) {
                            error = "Could not create that folder."
                        } else {
                            creating = false
                            changed()
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("Cancel", color = TextMuted) }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = AppSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    if (entry.directory) "The folder and everything inside it is removed."
                    else "The file is removed from the hosted folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = if (path.isBlank()) entry.name else "$path/${entry.name}"
                    val target = HostStorage.resolve(root, next)
                    if (target == null || !target.deleteRecursively()) {
                        error = "Could not delete ${entry.name}."
                    } else {
                        changed()
                    }
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

    editing?.let { file ->
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = AppSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(file.name, maxLines = 1)
            },
            text = {
                Column {
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = "Contents",
                        singleLine = false,
                        minLines = 10,
                        maxLines = 16,
                    )
                    Text(
                        "${HostStorage.formatBytes(text.length.toLong())} · saved as UTF-8",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            },
            confirmButton = {
                PrimaryButton(
                    text = "Save",
                    onClick = {
                        runCatching { HostStorage.writeText(file, text) }
                            .onSuccess {
                                editing = null
                                changed()
                            }
                            .onFailure { error = it.message }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel", color = TextMuted) }
            },
        )
    }
}
