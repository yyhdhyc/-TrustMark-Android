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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.trustmarktool.MainViewModel
import com.trustmarktool.model.EccLevel
import com.trustmarktool.model.GenerateUiState
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.ModelUiState
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.SelectedImage
import com.trustmarktool.model.SerialWidth
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState

@Composable
fun GenerateScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onPickImages: () -> Unit,
    onStart: () -> Unit,
    onDownloadModels: () -> Unit,
    onManageBatches: () -> Unit,
) {
    val generate = state.generate
    val copies = generate.copiesText.toIntOrNull() ?: 0
    val selectedBatch = state.batches.firstOrNull {
        it.batch.batchId == generate.selectedBatchId
    }
    val mappingSerials = selectedBatch?.mappingSerials.orEmpty()

    val previewSerial = if (generate.useBatch) {
        mappingSerials.firstOrNull() ?: 1
    } else {
        1
    }
    val width = if (generate.serialWidth == SerialWidth.NONE) SerialWidth.BITS_16 else generate.serialWidth
    val plan = remember(generate.text, generate.ecc, width, previewSerial) {
        PayloadCodec.plan(
            text = generate.text,
            autoNumber = true,
            serialWidth = width,
            serial = previewSerial.toLong(),
            ecc = generate.ecc,
        )
    }
    val encoded = remember(plan) {
        if (!plan.valid) {
            null
        } else {
            runCatching {
                PayloadCodec.encode(
                    text = generate.text,
                    autoNumber = true,
                    serialWidth = width,
                    serial = previewSerial.toLong(),
                    ecc = generate.ecc,
                )
            }.getOrNull()
        }
    }

    val lastSerial = if (generate.useBatch) {
        mappingSerials.lastOrNull() ?: 0
    } else {
        copies
    }
    val lastPlan = remember(generate.text, generate.ecc, width, lastSerial) {
        if (lastSerial <= 0) {
            null
        } else {
            PayloadCodec.plan(
                text = generate.text,
                autoNumber = true,
                serialWidth = width,
                serial = lastSerial.toLong(),
                ecc = generate.ecc,
            )
        }
    }
    val batchError = when {
        generate.useBatch && selectedBatch == null -> "请选择一个批次，或关闭“使用映射表”"
        generate.useBatch && mappingSerials.isEmpty() -> "该批次还没有接收人条目，请先补全映射表"
        !generate.useBatch && copies !in 1..MainViewModel.MAX_COPIES_PER_IMAGE ->
            "每张图片份数必须在 1–${MainViewModel.MAX_COPIES_PER_IMAGE} 之间"
        lastPlan != null && !lastPlan.valid -> lastPlan.error
        else -> null
    }
    val totalTasks = when {
        generate.useBatch -> generate.selected.size * mappingSerials.size
        copies > 0 -> generate.selected.size * copies
        else -> 0
    }
    val running = generate.task?.running == true
    val canStart = generate.selected.isNotEmpty() &&
        plan.valid &&
        batchError == null &&
        !running

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("为每张图片生成唯一 payload", style = MaterialTheme.typography.headlineSmall)
            Text(
                "每张源图独立编号（1..N），每份副本都从原图重新取像素编码；文本和编号编码为 TrustMark 二进制 payload。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            ImagePickerCard(
                images = generate.selected,
                onPick = onPickImages,
                onClear = viewModel::clearGenerateImages,
            )
        }

        item {
            ConfigurationCard(
                state = generate,
                batches = state.batches,
                mappingCount = mappingSerials.size,
                onText = viewModel::setGenerateText,
                onCopies = viewModel::setCopiesText,
                onUseBatch = viewModel::setUseBatch,
                onSelectBatch = viewModel::selectBatchForGenerate,
                onSerialWidth = viewModel::setSerialWidth,
                onEcc = viewModel::setEcc,
                onManageBatches = onManageBatches,
            )
        }

        item {
            PayloadBudgetCard(
                plan = plan,
                bits = encoded?.bits,
                batchError = batchError,
                totalTasks = totalTasks,
                sourceCount = generate.selected.size,
                copiesPerSource = if (generate.useBatch) mappingSerials.size else copies,
                useBatch = generate.useBatch,
                batchCode = selectedBatch?.batch?.batchId,
                mappingCount = mappingSerials.size,
            )
        }

        item { ModelCard(model = state.model, onDownload = onDownloadModels) }

        generate.task?.let { task ->
            item { TaskCard(task = task, onCancel = viewModel::cancelEncode) }
        }

        item {
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "任务进行中" else "开始批量生成")
            }
            val hint = when {
                !plan.valid -> plan.error.orEmpty()
                batchError != null -> batchError
                else -> "输出目录：Pictures/TrustMarkTool/；文件名格式：原文件名_tm_b{批次短码}_n{编号}.扩展名。"
            }
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.valid && batchError == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ImagePickerCard(
    images: List<SelectedImage>,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("待处理图片", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${images.size} 张",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (images.isEmpty()) {
                Text(
                    "支持 JPEG / PNG，单批最多 ${MainViewModel.MAX_BATCH} 张。可重复选择同一张图来生成多份副本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    // Same URI may occur more than once; the index keeps keys unique.
                    itemsIndexed(images) { index, image ->
                        Thumbnail(image, index + 1)
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
private fun Thumbnail(image: SelectedImage, index: Int) {
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
                "#$index",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ConfigurationCard(
    state: GenerateUiState,
    batches: List<com.trustmarktool.model.BatchSummary>,
    mappingCount: Int,
    onText: (String) -> Unit,
    onCopies: (String) -> Unit,
    onUseBatch: (Boolean) -> Unit,
    onSelectBatch: (String) -> Unit,
    onSerialWidth: (SerialWidth) -> Unit,
    onEcc: (EccLevel) -> Unit,
    onManageBatches: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Payload 设置", style = MaterialTheme.typography.titleMedium)
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = onText,
                label = { Text("固定文本") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "仅 A–Z、0–9、空格、- _ .；自动转大写 · ${state.text.length}/${PayloadCodec.MAX_TEXT_LENGTH}",
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
            )

            DropdownField(
                label = "编号位宽",
                value = state.serialWidth.label,
                supporting = "最大值 ${formatNumber(state.serialWidth.maxValue)}",
                options = SerialWidth.numberedOptions().map {
                    DropdownOption(it, it.label, "编号空间 ${formatNumber(it.maxValue + 1)}")
                },
                onSelected = onSerialWidth,
            )

            DropdownField(
                label = "BCH 纠错等级",
                value = state.ecc.label,
                supporting = state.ecc.description,
                options = EccLevel.entries.map {
                    DropdownOption(it, it.label, it.description)
                },
                onSelected = onEcc,
            )

            Text(
                PayloadCodec.capacitySummary(state.ecc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("使用映射表", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "批次模式：份数和编号来自接收人列表，生成时自动写 OutputRecord。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.useBatch, onCheckedChange = onUseBatch)
            }

            if (state.useBatch) {
                if (batches.isEmpty()) {
                    Text(
                        "还没有批次。先创建批次并填写接收人，或关闭开关使用通用模式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    DropdownField(
                        label = "选择批次",
                        value = batches.firstOrNull {
                            it.batch.batchId == state.selectedBatchId
                        }?.batch?.name ?: "请选择",
                        supporting = "共 ${batches.size} 个批次 · 当前 $mappingCount 个编号",
                        options = batches.map {
                            DropdownOption(
                                it.batch.batchId,
                                "${it.batch.batchId} · ${it.batch.name}",
                                "${it.mappingCount} 位接收人 · ${it.batch.ecc}",
                            )
                        },
                        onSelected = onSelectBatch,
                    )
                }
                Text(
                    "批次模式下每张源图使用映射表全部编号；份数 = 映射条目数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = state.copiesText,
                    onValueChange = onCopies,
                    label = { Text("每张图片生成份数") },
                    singleLine = true,
                    isError = state.copiesText.toIntOrNull()
                        ?.let { it in 1..MainViewModel.MAX_COPIES_PER_IMAGE } != true,
                    supportingText = {
                        Text("范围 1–${MainViewModel.MAX_COPIES_PER_IMAGE}；每张源图独立编号 1..N")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TextButton(onClick = onManageBatches) {
                Icon(Icons.Outlined.Tune, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("批次与映射表")
            }
        }
    }
}

@Composable
private fun PayloadBudgetCard(
    plan: PayloadCodec.PayloadPlan,
    bits: String?,
    batchError: String?,
    totalTasks: Int,
    sourceCount: Int,
    copiesPerSource: Int,
    useBatch: Boolean,
    batchCode: String?,
    mappingCount: Int,
) {
    val valid = plan.valid && batchError == null
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (valid) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Payload 预算与二进制预览", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                ) {
                    Text(
                        "${plan.remainingBits} 位剩余",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            LinearProgressIndicator(
                progress = {
                    if (plan.capacityBits <= 0) 0f
                    else (plan.totalBits.toFloat() / plan.capacityBits).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )

            BudgetRow("数据容量", "${plan.capacityBits} 位")
            BudgetRow("帧头", "${plan.headerBits} 位（magic + 模式 + 位宽 + 长度）")
            BudgetRow("文本", "${plan.textBits} 位（${plan.textMode.label}，${plan.textMode.bitsPerChar} 位/字符）")
            BudgetRow("编号", "${plan.serialBits} 位")
            HorizontalDivider()
            BudgetRow("已用 / 容量", "${plan.totalBits} / ${plan.capacityBits} 位")
            BudgetRow(
                "当前组合最多",
                "${plan.maxAlphaLength} 个大写字母 或 ${plan.maxAlnumLength} 个字母数字",
            )
            BudgetRow("首个编号", formatNumber(plan.serial ?: 1))
            BudgetRow("源图数量", "$sourceCount 张")
            BudgetRow("每源份数", "$copiesPerSource 份")
            BudgetRow("总任务数", "$totalTasks 个文件")
            if (useBatch) {
                BudgetRow("映射条目", "$mappingCount 条")
            }
            batchCode?.let { BudgetRow("批次短码", it) }

            if (plan.error != null) {
                ErrorLine(plan.error)
            }
            if (batchError != null) {
                ErrorLine(batchError)
            }

            if (bits != null) {
                Text("逻辑 payload · ${bits.length} 位", style = MaterialTheme.typography.labelLarge)
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        PayloadCodec.binaryGroups(bits),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Text(
                    "原生层会补齐到数据容量，再追加 BCH 校验位与 4 位 schema，最终严格为 100 位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BudgetRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModelCard(model: ModelUiState, onDownload: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model.downloading || model.checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (model.ready) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                        contentDescription = null,
                        tint = if (model.ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("TrustMark Q 模型", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (!model.ready && !model.downloading) {
                    TextButton(onClick = onDownload) { Text("预下载") }
                }
            }

            Text(
                model.message ?: "正在检查模型…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (model.downloading) {
                LinearProgressIndicator(
                    progress = { model.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape),
                )
                Text(
                    "${model.downloadedBytes / 1024 / 1024} MB / ${model.totalBytes / 1024 / 1024} MB",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Text(
                "ONNX 模型不包含在 Adobe 仓库中；本应用从官方 CDN 下载并校验 SHA-256。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskCard(task: TaskProgress, onCancel: () -> Unit) {
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
                task.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
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
private fun <T> DropdownField(
    label: String,
    value: String,
    supporting: String,
    options: List<DropdownOption<T>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Outlined.Tune, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.value)
                        },
                    )
                }
            }
        }
    }
}

private data class DropdownOption<T>(
    val value: T,
    val title: String,
    val subtitle: String,
)

private fun formatNumber(value: Long): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()
