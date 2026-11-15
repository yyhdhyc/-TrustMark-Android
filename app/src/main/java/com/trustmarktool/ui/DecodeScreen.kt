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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trustmarktool.MainViewModel
import com.trustmarktool.model.DecodeRecord
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState
import com.trustmarktool.ui.components.TaskProgressCard
import java.util.Locale

private val SuccessGreen = Color(0xFF2E7D32)
private val LocalMissAmber = Color(0xFFF9A825)

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("批量解密", style = MaterialTheme.typography.headlineSmall)
            Text(
                "自动识别帧类型：通用帧恢复文本；批次帧校验 CRC-7 后恢复 4 字母批次码与 12 位编号，并查询本地映射表。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            DecodePickerCard(
                images = decode.selected,
                enabled = !running,
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
                    Text("正在解密")
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始批量解密")
                }
            }
        }

        decode.task?.let { task ->
            item { TaskProgressCard(task = task, onCancel = viewModel::cancelDecode) }
        }

        decode.lastSummary?.let { summary ->
            if (decode.task == null) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
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

            itemsIndexed(
                items = decode.results,
                key = { index, record -> "${record.fileName}-$index" },
            ) { _, record ->
                DecodeResultRow(record)
            }

            item {
                Text(
                    "绿色：通用帧解码成功，或批次帧命中映射表；黄色：批次帧有效但本地无此批次记录；红色：水印损坏、CRC 校验失败或模型不匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!hasResults && !running && decode.task == null) {
            item {
                Text(
                    "选择图片后开始解密。结果按“文件名 → 帧类型 → 文本/批次编号 → 置信度”展示，可导出 CSV/JSON。",
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
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("待解密图片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${images.size} 张",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (images.isEmpty()) {
                Text(
                    "单批最多 ${MainViewModel.MAX_BATCH} 张，支持 JPEG / PNG。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    itemsIndexed(images, key = { index, _ -> index }) { index, image ->
                        Box {
                            AsyncImage(
                                model = image.uri,
                                contentDescription = image.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(12.dp))
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
                OutlinedButton(onClick = onPick, enabled = enabled) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (images.isEmpty()) "从相册选择" else "继续添加")
                }
                if (images.isNotEmpty()) {
                    TextButton(onClick = onClear, enabled = enabled) {
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
private fun DecodeResultRow(record: DecodeRecord) {
    var expanded by remember(record) { mutableStateOf(false) }
    val succeeded = record.succeeded
    val color = when {
        !succeeded -> MaterialTheme.colorScheme.error
        record.batchMatched -> SuccessGreen
        record.frameType == PayloadCodec.FrameType.BATCH.label -> LocalMissAmber
        else -> SuccessGreen
    }
    val icon = when {
        !succeeded -> Icons.Outlined.ErrorOutline
        record.frameType == PayloadCodec.FrameType.BATCH.label && !record.batchMatched -> Icons.Outlined.HelpOutline
        else -> Icons.Outlined.CheckCircle
    }

    Surface(color = Color.Transparent) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(icon, contentDescription = null, tint = color)
                },
                headlineContent = {
                    Text(
                        record.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        when {
                            !succeeded -> Text(
                                record.error ?: "解码失败",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            record.frameType == PayloadCodec.FrameType.BATCH.label -> {
                                Text(
                                    "批次 ${record.batchId ?: "—"} · 编号 ${record.serial ?: "—"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (record.batchMatched) {
                                    Text(
                                        listOfNotNull(record.batchName, record.recipient)
                                            .joinToString(" · ")
                                            .ifBlank { "本地映射命中" },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Text(
                                        "本地无匹配：批次有效，但映射表中没有该记录",
                                        color = LocalMissAmber,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            else -> Text(
                                record.decodedText?.ifBlank { "（空文本）" } ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            buildString {
                                append(record.frameType ?: "未知帧")
                                record.ecc?.let { append(" · ").append(it) }
                                append(" · 置信度 ").append(formatConfidence(record.confidence))
                                append(" · 翻转 ").append(record.bitflips)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingContent = {
                    IconButtonCompact(
                        expanded = expanded,
                        onClick = { expanded = !expanded },
                    )
                },
            )

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HorizontalDivider()
                    Text(
                        "原始 payload · ${record.payloadBits.length} 位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        PayloadCodec.binaryGroups(record.payloadBits),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun IconButtonCompact(expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
        )
    }
}

private fun formatConfidence(value: Float): String =
    String.format(Locale.US, "%.3f", value)
