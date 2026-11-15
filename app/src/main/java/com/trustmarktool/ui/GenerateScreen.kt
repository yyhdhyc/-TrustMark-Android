package com.trustmarktool.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trustmarktool.MainViewModel
import com.trustmarktool.model.BatchSummary
import com.trustmarktool.model.EccLevel
import com.trustmarktool.model.GenerateMode
import com.trustmarktool.model.GenerateUiState
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState
import com.trustmarktool.ui.components.TaskProgressCard
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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
    var showDetails by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val previewPlan = when (generate.mode) {
        GenerateMode.UNIVERSAL -> PayloadCodec.planUniversal(generate.text, generate.ecc)
        GenerateMode.BATCH -> {
            val batchId = generate.selectedBatchId
            if (batchId == null) null else PayloadCodec.planBatch(batchId, 1, EccLevel.BCH_SUPER)
        }
    }
    val running = generate.task?.running == true
    val canStart = generate.selected.isNotEmpty() &&
        previewPlan?.valid == true &&
        !running &&
        (generate.mode == GenerateMode.UNIVERSAL || generate.selectedBatchId != null)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModeSelector(
            mode = generate.mode,
            enabled = !running,
            onSelect = viewModel::setGenerateMode,
        )

        ImagePickerCard(
            images = generate.selected.map { it.uri to it.displayName },
            enabled = !running,
            onPick = onPickImages,
            onRemove = viewModel::removeGenerateImage,
        )

        if (generate.mode == GenerateMode.UNIVERSAL) {
            UniversalConfigCard(
                state = generate,
                plan = previewPlan,
                enabled = !running,
                onTextChange = viewModel::setGenerateText,
                onEccChange = viewModel::setEcc,
                // v1.2.2: universal mode shares the adjustable strength slider.
                onStrengthChange = viewModel::setStrength,
                onDetails = { showDetails = true },
            )
        } else {
            BatchConfigCard(
                state = generate,
                plan = previewPlan,
                batches = state.batches,
                enabled = !running,
                onBatchChange = viewModel::selectBatchForGenerate,
                onStrengthChange = viewModel::setStrength,
                onDetails = { showDetails = true },
                onManageBatches = onManageBatches,
            )
        }

        ModelCard(
            state = state,
            onDownload = onDownloadModels,
        )

        Button(
            onClick = onStart,
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                when {
                    running -> "处理中…"
                    generate.mode == GenerateMode.UNIVERSAL -> "生成通用水印"
                    else -> "生成批次水印"
                },
            )
        }

        if (generate.selected.isEmpty()) {
            Text(
                text = "请选择 1–${MainViewModel.MAX_BATCH} 张 JPEG/PNG 图片；通用模式每张图生成 1 份，批次模式按映射人数生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        generate.task?.let { TaskProgressCard(task = it, onCancel = viewModel::cancelEncode) }

        Spacer(Modifier.height(8.dp))
    }

    if (showDetails && previewPlan != null) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = sheetState,
        ) {
            PlanDetails(plan = previewPlan)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    mode: GenerateMode,
    enabled: Boolean,
    onSelect: (GenerateMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        GenerateMode.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                enabled = enabled,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, GenerateMode.entries.size),
                label = {
                    Text(if (item == GenerateMode.UNIVERSAL) "通用" else "批次")
                },
            )
        }
    }
}

@Composable
private fun ImagePickerCard(
    images: List<Pair<Uri, String>>,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: (Uri) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("源图片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${images.size} 张", style = MaterialTheme.typography.labelMedium)
            }

            if (images.isEmpty()) {
                OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("从相册多选图片")
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(images, key = { index, _ -> index }) { _, (uri, name) ->
                        Box(modifier = Modifier.size(112.dp)) {
                            AsyncImage(
                                model = uri,
                                contentDescription = name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                IconButton(
                                    onClick = { onRemove(uri) },
                                    enabled = enabled,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = onPick,
                            enabled = enabled,
                            modifier = Modifier.size(104.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "添加图片")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UniversalConfigCard(
    state: GenerateUiState,
    plan: PayloadCodec.PayloadPlan?,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onEccChange: (EccLevel) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onDetails: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("通用水印", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "固定文本，不含编号。解码后直接恢复文字。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                label = { Text("水印文本") },
                supportingText = {
                    Text("A–Z、0–9、空格、- _ .，最多 ${PayloadCodec.MAX_TEXT_LENGTH} 字符；空格会保留")
                },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            DropdownField(
                label = "BCH 纠错等级",
                selected = state.ecc,
                options = EccLevel.orderedForMenu,
                optionLabel = { it.menuTitle },
                optionSubtitle = { it.description },
                enabled = enabled,
                onSelect = onEccChange,
            )

            // v1.2.2: strength is adjustable in universal mode too.
            StrengthSlider(
                value = state.strength,
                enabled = enabled,
                onValueChange = onStrengthChange,
            )

            if (plan != null) {
                CapacityBar(
                    used = plan.totalBits,
                    capacity = plan.capacityBits,
                    textMode = plan.textMode,
                    remaining = plan.remainingBits,
                    onDetails = onDetails,
                )
                if (plan.valid) {
                    val previewBits = runCatching {
                        PayloadCodec.encodeUniversal(state.text, state.ecc).bits
                    }.getOrDefault("")
                    Text(
                        "预览：${PayloadCodec.binaryGroups(previewBits)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        plan.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchConfigCard(
    state: GenerateUiState,
    plan: PayloadCodec.PayloadPlan?,
    batches: List<BatchSummary>,
    enabled: Boolean,
    onBatchChange: (String) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onDetails: () -> Unit,
    onManageBatches: () -> Unit,
) {
    val selectedBatch = batches.firstOrNull { it.batch.batchId == state.selectedBatchId }
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("批次水印", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onManageBatches, enabled = enabled) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("批次与映射")
                }
            }
            Text(
                "每个接收人一个编号；4 字母批次短码 + 12 位编号 + CRC-7，共 40 位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (batches.isEmpty()) {
                Text(
                    "还没有批次。请先创建批次并填写接收人。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                DropdownField(
                    label = "选择批次",
                    selected = state.selectedBatchId.orEmpty(),
                    options = batches.map { it.batch.batchId },
                    optionLabel = { id ->
                        val summary = batches.firstOrNull { it.batch.batchId == id }
                        val name = summary?.batch?.name ?: id
                        "$id · $name · ${summary?.mappingCount ?: 0} 人"
                    },
                    enabled = enabled,
                    onSelect = onBatchChange,
                )
            }

            if (selectedBatch != null) {
                Text(
                    "批次“${selectedBatch.batch.name}”：${selectedBatch.mappingCount} 个编号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v1.2.2: same shared slider as universal mode; one strength value
            // is persisted across both modes.
            StrengthSlider(
                value = state.strength,
                enabled = enabled,
                onValueChange = onStrengthChange,
            )

            AssistChip(onClick = {}, label = { Text("BCH Super · 固定 40 位") })

            if (plan != null) {
                CapacityBar(
                    used = PayloadCodec.BATCH_TOTAL_BITS,
                    capacity = EccLevel.BCH_SUPER.dataBits,
                    textMode = null,
                    remaining = EccLevel.BCH_SUPER.dataBits - PayloadCodec.BATCH_TOTAL_BITS,
                    onDetails = onDetails,
                )
            }
        }
    }
}

/**
 * v1.2.2: shared embedding-strength slider for BOTH universal and batch modes.
 * One value is persisted in GeneratePreferences and applied to every task.
 */
@Composable
private fun StrengthSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("嵌入强度", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.2f", value),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = MainViewModel.MIN_STRENGTH..MainViewModel.MAX_STRENGTH,
            steps = 29,
            enabled = enabled,
        )
        Text(
            "0.50–2.00，步长 0.05。越高越抗压缩，但原图失真越明显；默认 1.30。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapacityBar(
    used: Int,
    capacity: Int,
    textMode: PayloadCodec.TextMode?,
    remaining: Int,
    onDetails: () -> Unit,
) {    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { if (capacity <= 0) 0f else (used.toFloat() / capacity).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已用 $used 位 / 剩余 $remaining 位" +
                    if (textMode != null) " · ${textMode.label}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDetails) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("详情")
            }
        }
    }
}

@Composable
private fun PlanDetails(plan: PayloadCodec.PayloadPlan) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Payload 位预算", style = MaterialTheme.typography.titleLarge)
        Text(
            "${plan.frameType.label}帧 · ${plan.capacityBits} 位 BCH 容量",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DetailRow("帧类型", "1 位（${if (plan.frameType == PayloadCodec.FrameType.UNIVERSAL) "0" else "1"}）")
        if (plan.frameType == PayloadCodec.FrameType.UNIVERSAL) {
            DetailRow("文本字母表", if (plan.textMode == PayloadCodec.TextMode.ALPHA) "A–Z / 5 位" else "字母数字 / 6 位")
            DetailRow("文本长度", "4 位")
            DetailRow("文本内容", "${plan.text.length} 字符 = ${plan.textBits} 位")
            DetailRow("纯字母上限", "${plan.maxAlphaLength} 字符")
            DetailRow("字母数字上限", "${plan.maxAlnumLength} 字符")
        } else {
            DetailRow("批次短码", "20 位（4 × 5）")
            DetailRow("编号", "12 位（1–${PayloadCodec.MAX_BATCH_SERIAL}）")
            DetailRow("CRC-7", "7 位（MMC，poly 0x09）")
            if (plan.crc7 != null) {
                DetailRow("CRC 值", plan.crc7.toString(2).padStart(7, '0'))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DetailRow("总位数", "${plan.totalBits} 位")
        DetailRow("剩余", "${plan.remainingBits} 位")
        if (plan.error != null) {
            Text(plan.error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ModelCard(
    state: MainUiState,
    onDownload: () -> Unit,
) {
    val model = state.model
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (model.ready) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (model.ready) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (model.ready) "Q 模型已就绪" else "需要准备 Q 模型",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (!model.ready && !model.downloading) {
                    TextButton(onClick = onDownload) { Text("下载") }
                }
            }
            Text(
                model.message ?: "首次使用需下载 encoder_Q / decoder_Q（约 64.7 MB）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.downloading) {
                LinearProgressIndicator(
                    progress = { model.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatNumber(model.downloadedBytes)} / ${formatNumber(model.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    optionSubtitle: ((T) -> String?)? = null,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(optionLabel(option))
                            optionSubtitle?.invoke(option)?.let { subtitle ->
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun formatNumber(value: Long): String = when {
    value >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / 1024.0 / 1024.0)
    value >= 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
    else -> "$value B"
}
