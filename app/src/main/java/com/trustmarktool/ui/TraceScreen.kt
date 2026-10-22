package com.trustmarktool.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trustmarktool.MainViewModel
import com.trustmarktool.data.BatchStore
import com.trustmarktool.model.BatchSummary
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.TraceStatus

@Composable
fun TraceScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onPickImage: () -> Unit,
    onStart: () -> Unit,
    onManageBatches: () -> Unit,
) {
    val trace = state.trace
    val canStart = trace.selected != null && !trace.running

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("溯源查询", style = MaterialTheme.typography.headlineSmall)
            Text(
                "对单张图片运行解码器，优先从文件名读取 b{批次短码}_n{编号}，再与本地加密映射表比对。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("疑似水印图片", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (trace.selected != null) {
                            TextButton(onClick = onPickImage) { Text("更换") }
                        }
                    }

                    val selected = trace.selected
                    if (selected == null) {
                        Text(
                            "选择一张图片。若文件名保留了 _tm_b..._n... 后缀，会自动解析批次与编号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        OutlinedButton(onClick = onPickImage) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("从相册选择")
                        }
                    } else {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = selected.uri,
                                contentDescription = selected.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    selected.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatBytes(selected.sizeBytes)} · ${selected.mimeType ?: "image"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tune, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("批次比对", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "文件名没有批次短码时，可手动指定本地批次；手动选择只用于比对，不会修改图片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TraceBatchDropdown(
                        batches = state.batches,
                        selectedBatchId = trace.selectedBatchId,
                        onSelected = viewModel::selectTraceBatch,
                    )
                    TextButton(onClick = onManageBatches) {
                        Icon(Icons.Outlined.Tune, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("打开批次与映射表")
                    }
                }
            }
        }

        item {
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (trace.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在解码与比对…")
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始溯源")
                }
            }
        }

        when (trace.status) {
            TraceStatus.IDLE -> Unit
            TraceStatus.DECODING -> item { TraceProgressCard() }
            TraceStatus.DECODE_FAILED -> item {
                TraceResultCard(
                    container = MaterialTheme.colorScheme.errorContainer,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = "无法读取水印",
                    message = trace.error ?: "水印可能已被破坏、被裁切，或模型变体不匹配。",
                )
            }

            TraceStatus.BATCH_NOT_FOUND -> item {
                TraceResultCard(
                    container = MaterialTheme.colorScheme.errorContainer,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = "批次短码存在，但本地无此批次记录",
                    message = trace.message
                        ?: "文件解析出批次 ${trace.batchIdFromFile ?: "?"}，但本机加密存储中找不到该批次。",
                )
            }

            TraceStatus.NO_MATCH -> item {
                TraceResultCard(
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    title = "水印已恢复，但未找到对应批次记录",
                    message = trace.message ?: "可尝试手动指定批次，或检查文件名中的批次短码。",
                )
            }

            TraceStatus.MATCH -> item { TraceMatchCard(state) }
        }

        if (trace.payloadBits.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("恢复的 Payload", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                PayloadCodec.binaryGroups(trace.payloadBits),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TraceRow("解码文本", trace.decodedText ?: "-")
                        TraceRow("编号", trace.serial?.toString() ?: "-")
                        TraceRow("置信度", "%.4f".format(trace.confidence))
                        TraceRow("BCH 翻转位", trace.bitflips.toString())
                        TraceRow("纠错等级", trace.ecc ?: "-")
                        TraceRow("文件名批次", trace.batchIdFromFile ?: "-")
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceProgressCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("正在解码并比对本地映射…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TraceResultCard(
    container: Color,
    iconTint: Color,
    title: String,
    message: String,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = iconTint)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TraceMatchCard(state: MainUiState) {
    val trace = state.trace
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("命中本地批次记录", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            TraceRow("批次短码", trace.batchId ?: "-")
            TraceRow("批次名称", trace.batchName ?: "-")
            TraceRow("接收人", trace.recipient ?: "（未填写）")
            TraceRow("编号", trace.serial?.toString() ?: "-")
            TraceRow(
                "批次创建时间",
                trace.batchCreatedAt?.let { BatchStore.formatTime(it) } ?: "-",
            )
            Text(
                trace.message ?: "该文件来自上述批次，映射表记录与 payload 编号一致。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TraceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TraceBatchDropdown(
    batches: List<BatchSummary>,
    selectedBatchId: String?,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = batches.firstOrNull {
        it.batch.batchId == selectedBatchId
    }?.let { "${it.batch.batchId} · ${it.batch.name}" } ?: "不手动指定（仅按文件名）"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selectedName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.Tune, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不手动指定（仅按文件名）") },
                onClick = {
                    expanded = false
                    onSelected(null)
                },
            )
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
