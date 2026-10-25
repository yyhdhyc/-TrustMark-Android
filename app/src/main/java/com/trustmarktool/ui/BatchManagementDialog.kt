package com.trustmarktool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trustmarktool.data.BatchStore
import com.trustmarktool.model.BatchSummary
import com.trustmarktool.model.MappingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BatchManagementDialog(
    batches: List<BatchSummary>,
    onDismiss: () -> Unit,
    onCreateBatch: (name: String, recipients: List<String>, note: String) -> Unit,
    onUpsertMapping: (batchId: String, serialText: String, recipient: String, note: String) -> Unit,
    onDeleteMapping: (batchId: String, serial: Int) -> Unit,
    onDeleteBatch: (batchId: String) -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    val context = LocalContext.current
    var selectedBatchId by remember {
        mutableStateOf(batches.firstOrNull()?.batch?.batchId)
    }
    // Keep the user's choice across mapping edits. Only fall back when the
    // selected batch was deleted or no batch is selected yet.
    LaunchedEffect(batches) {
        if (batches.none { it.batch.batchId == selectedBatchId }) {
            selectedBatchId = batches.firstOrNull()?.batch?.batchId
        }
    }
    var showCreate by remember { mutableStateOf(batches.isEmpty()) }
    var confirmDeleteBatch by remember { mutableStateOf(false) }

    val selected = batches.firstOrNull { it.batch.batchId == selectedBatchId }
    var mappings by remember(selectedBatchId) { mutableStateOf<List<MappingEntry>>(emptyList()) }
    LaunchedEffect(selectedBatchId, batches) {
        mappings = withContext(Dispatchers.IO) {
            selectedBatchId?.let { BatchStore.mappingsFor(context, it) }.orEmpty()
        }
    }

    var serialText by remember(selectedBatchId) { mutableStateOf("") }
    var recipient by remember(selectedBatchId) { mutableStateOf("") }
    var note by remember(selectedBatchId) { mutableStateOf("") }

    LaunchedEffect(mappings) {
        // After a mapping is added/deleted, advance the quick-entry serial
        // instead of leaving the just-used number in the field.
        serialText = ((mappings.maxOfOrNull { it.serial } ?: 0) + 1).toString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("批次与映射表", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "加密保存在应用私有目录 · 卸载会丢失",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { showCreate = !showCreate }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (showCreate) "收起新建" else "新建批次")
                    }
                    OutlinedButton(onClick = onExportCsv, enabled = batches.isNotEmpty()) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("CSV")
                    }
                    OutlinedButton(onClick = onExportJson, enabled = batches.isNotEmpty()) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("JSON")
                    }
                }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(10.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "映射表是溯源的关键，卸载应用会丢失，请定期导出备份。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showCreate) {
                        item {
                            CreateBatchCard(
                                onCreate = { name, recipients, noteText ->
                                    onCreateBatch(name, recipients, noteText)
                                    showCreate = false
                                },
                            )
                        }
                    }

                    if (batches.isNotEmpty()) {
                        item {
                            BatchDropdown(
                                batches = batches,
                                selectedBatchId = selectedBatchId,
                                onSelected = {
                                    selectedBatchId = it
                                    serialText = ""
                                    recipient = ""
                                    note = ""
                                },
                            )
                        }
                    }

                    if (selected != null) {
                        item {
                            Column {
                                Text(
                                    "${selected.batch.batchId} · ${selected.batch.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "纠错 ${selected.batch.ecc} · 创建于 ${BatchStore.formatTime(selected.batch.createdAt)} · ${mappings.size} 条映射",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (selected.batch.note.isNotBlank()) {
                                    Text(
                                        selected.batch.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (mappings.isEmpty()) {
                            item {
                                Text(
                                    "该批次还没有接收人。补填编号后即可在生成页使用映射表；也可先直接生成，稍后再补表。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(mappings, key = { it.serial }) { entry ->
                                MappingRow(
                                    entry = entry,
                                    onDelete = {
                                        onDeleteMapping(selected.batch.batchId, entry.serial)
                                    },
                                )
                            }
                        }

                        item {
                            MappingEditor(
                                serialText = serialText,
                                recipient = recipient,
                                note = note,
                                onSerial = { serialText = it.filter(Char::isDigit) },
                                onRecipient = { recipient = it },
                                onNote = { note = it },
                                onSave = {
                                    onUpsertMapping(
                                        selected.batch.batchId,
                                        serialText,
                                        recipient,
                                        note,
                                    )
                                    serialText = (serialText.toIntOrNull()?.plus(1) ?: 1).toString()
                                    recipient = ""
                                    note = ""
                                },
                            )
                        }

                        item {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            TextButton(onClick = { confirmDeleteBatch = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("删除整个批次")
                            }
                            Text(
                                "删除批次会同时删除其映射与输出记录；已生成到相册的图片不会被删除。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        item {
                            Text(
                                "尚无批次。先“新建批次”并填写接收人，或在通用模式生成后回到这里补表。",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteBatch && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBatch = false },
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text("删除批次 ${selected.batch.batchId}？") },
            text = {
                Text("将删除批次、全部映射条目和输出文件记录。相册中的图片保留，但溯源将无法命中。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteBatch(selected.batch.batchId)
                        confirmDeleteBatch = false
                        selectedBatchId = batches.firstOrNull {
                            it.batch.batchId != selected.batch.batchId
                        }?.batch?.batchId
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBatch = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CreateBatchCard(
    onCreate: (String, List<String>, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var recipients by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("新建批次（先生成映射表）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("批次名称，如：2026 产品发布会") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = recipients,
                onValueChange = { recipients = it },
                label = { Text("接收人，每行一个") },
                supportingText = { Text("按输入顺序自动分配编号 1..N，最多 4095 位（12 位编号）") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("批次备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onCreate(
                        name,
                        recipients.lines().map(String::trim).filter(String::isNotEmpty),
                        note,
                    )
                },
            ) {
                Text("创建批次")
            }
        }
    }
}

@Composable
private fun BatchDropdown(
    batches: List<BatchSummary>,
    selectedBatchId: String?,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = batches.firstOrNull { it.batch.batchId == selectedBatchId }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selected?.let { "${it.batch.batchId} · ${it.batch.name}" } ?: "选择批次",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.Tune, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            batches.forEach { summary ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${summary.batch.batchId} · ${summary.batch.name}")
                            Text(
                                "${summary.mappingCount} 位接收人 · ${summary.batch.ecc}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(summary.batch.batchId)
                    },
                )
            }
        }
    }
}

@Composable
private fun MappingRow(entry: MappingEntry, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    entry.serial.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.recipientName, style = MaterialTheme.typography.bodyLarge)
                if (entry.note.isNotBlank()) {
                    Text(
                        entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除编号 ${entry.serial}")
            }
        }
    }
}

@Composable
private fun MappingEditor(
    serialText: String,
    recipient: String,
    note: String,
    onSerial: (String) -> Unit,
    onRecipient: (String) -> Unit,
    onNote: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("补填 / 修改映射", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = serialText,
                onValueChange = onSerial,
                label = { Text("编号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(100.dp),
            )
            OutlinedTextField(
                value = recipient,
                onValueChange = onRecipient,
                label = { Text("接收人") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            label = { Text("备注（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = onSave) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("保存编号")
        }
    }
}
