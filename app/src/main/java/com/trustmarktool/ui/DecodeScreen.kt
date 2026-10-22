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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.trustmarktool.model.DecodeRecord
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState

@Composable
fun DecodeScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onPickImages: () -> Unit,
    onStart: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    val decode = state.decode
    val running = decode.task?.running == true
    val hasResults = decode.results.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("批量解密", style = MaterialTheme.typography.headlineSmall)
            Text(
                "逐张运行 TrustMark 解码器，恢复二进制 payload 并反向解析文本与编号。支持 JPEG / PNG。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            DecodePickerCard(
                images = decode.selected,
                onPick = onPickImages,
                onClear = viewModel::clearDecodeImages,
            )
        }

        item {
            Button(
                onClick = onStart,
                enabled = decode.selected.isNotEmpty() && !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在处理中")
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始批量解密")
                }
            }
        }

        decode.task?.let { task ->
            item { DecodeTaskCard(task = task, onCancel = viewModel::cancelDecode) }
        }

        decode.lastSummary?.let { summary ->
            if (decode.task == null) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }

        if (hasResults) {
            item {
                ResultsHeader(
                    count = decode.results.size,
                    successCount = decode.results.count { it.succeeded },
                    exporting = decode.exporting,
                    onExportCsv = onExportCsv,
                    onExportJson = onExportJson,
                )
            }

            itemsIndexed(decode.results) { _, record ->
                DecodeResultCard(record)
            }

            item {
                Text(
                    "置信度是解码器输出的 soft confidence，不等同于“100% 正确”；BCH 翻转位过多时建议人工复核。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!hasResults && !running && decode.task == null) {
            item {
                Text(
                    "选择图片后点击“开始批量解密”。结果会显示文件名、解码文本、编号、原始二进制与置信度，并可导出 CSV/JSON。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DecodePickerCard(
    images: List<com.trustmarktool.model.SelectedImage>,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("待解密图片", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${images.size} 张",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (images.isEmpty()) {
                Text(
                    "单批最多 ${MainViewModel.MAX_BATCH} 张。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    itemsIndexed(images) { index, image ->
                        Box {
                            AsyncImage(
                                model = image.uri,
                                contentDescription = image.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.58f),
                                shape = RoundedCornerShape(bottomEnd = 10.dp),
                                modifier = Modifier.align(Alignment.TopStart),
                            ) {
                                Text(
                                    "#${index + 1}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (images.isEmpty()) "从相册选择" else "继续添加")
                }
                if (images.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("清空")
                    }
                }
            }
        }
    }
}

@Composable
private fun DecodeTaskCard(task: TaskProgress, onCancel: () -> Unit) {
    val (title, icon) = when (task.state) {
        TaskState.RUNNING -> "正在处理中" to null
        TaskState.COMPLETED -> "处理完成" to Icons.Outlined.CheckCircle
        TaskState.CANCELLED -> "已取消" to Icons.Outlined.Stop
        TaskState.ERROR -> "处理失败" to Icons.Outlined.ErrorOutline
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.state == TaskState.ERROR) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.state == TaskState.RUNNING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else if (icon != null) {
                    Icon(icon, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (task.total > 0) "${task.completed}/${task.total}" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (task.state == TaskState.RUNNING) {
                if (task.indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { task.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape),
                    )
                }
                Text(
                    task.currentFile.ifBlank { "处理中…" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("取消任务")
                }
            } else {
                task.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                task.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsHeader(
    count: Int,
    successCount: Int,
    exporting: Boolean,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("解密结果", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "$successCount / $count 成功",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportCsv, enabled = !exporting) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导出 CSV")
            }
            OutlinedButton(onClick = onExportJson, enabled = !exporting) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导出 JSON")
            }
            if (exporting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun DecodeResultCard(record: DecodeRecord) {
    val success = record.succeeded
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (success) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (success) {
                    ConfidenceBadge(record.confidence)
                } else {
                    Tag("失败", MaterialTheme.colorScheme.error)
                }
            }

            if (success) {
                Text(
                    record.decodedText?.ifBlank { "（无文本，仅编号）" } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("编号 ${record.serial ?: "—"}")
                    record.ecc?.let { Tag(it) }
                    Tag("${record.format} · 翻转 ${record.bitflips}")
                }
                HorizontalDivider()
                Text(
                    "原始 payload · ${record.payloadBits.length} 位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    PayloadCodec.binaryGroups(record.payloadBits),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            } else {
                Text(
                    record.error ?: "解码失败",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "置信度 ${"%.3f".format(record.confidence)} · 翻转位 ${record.bitflips}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: Float) {
    val color = when {
        confidence >= 0.85f -> MaterialTheme.colorScheme.primary
        confidence >= 0.60f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Tag("置信度 ${"%.3f".format(confidence)}", color)
}

@Composable
private fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
