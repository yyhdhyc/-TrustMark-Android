package com.trustmarktool

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.trustmarktool.data.BatchStore
import com.trustmarktool.data.DecodeStore
import com.trustmarktool.data.ExportFormatter
import com.trustmarktool.data.ImageOrientation
import com.trustmarktool.data.ImageStore
import com.trustmarktool.data.ModelRepository
import com.trustmarktool.data.OutputNaming
import com.trustmarktool.data.TaskPlanStore
import com.trustmarktool.model.BatchSummary
import com.trustmarktool.model.DecodeRecord
import com.trustmarktool.model.DecodeUiState
import com.trustmarktool.model.EccLevel
import com.trustmarktool.model.GenerateTask
import com.trustmarktool.model.GenerateUiState
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.ModelUiState
import com.trustmarktool.model.OutputRecord
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.SelectedImage
import com.trustmarktool.model.SerialWidth
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState
import com.trustmarktool.model.TraceStatus
import com.trustmarktool.model.TraceUiState
import com.trustmarktool.nativebridge.TrustMarkEngine
import com.trustmarktool.nativebridge.TrustMarkNative
import com.trustmarktool.worker.DecodeWorker
import com.trustmarktool.worker.EncodeWorker
import com.trustmarktool.worker.WorkerKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val application: Application = getApplication()
    private val workManager = WorkManager.getInstance(application)
    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(MainUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<MainUiState> = _uiState

    init {
        refreshModelState()
        refreshBatches()
        resumeRunningWork()
    }

    // ------------------------------------------------------------------ tabs

    fun selectTab(index: Int) {
        _uiState.update { it.copy(tab = index.coerceIn(0, 2)) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    // --------------------------------------------------------------- generate

    /**
     * 同一 URI 允许重复选择：一张源图可以生成多份副本。这里只按“源图数量”
     * 限制 50，不做 distinctBy 去重。
     */
    fun addGenerateImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.generate.selected
            val room = (MAX_BATCH - current.size).coerceAtLeast(0)
            if (room == 0) {
                _uiState.update { it.copy(message = "最多选择 $MAX_BATCH 张源图") }
                return@launch
            }
            val resolved = resolveImages(uris.take(room))
            if (resolved.isEmpty()) {
                _uiState.update { it.copy(message = "无法读取所选图片") }
                return@launch
            }
            _uiState.update {
                it.copy(generate = it.generate.copy(selected = it.generate.selected + resolved))
            }
        }
    }

    fun removeGenerateImage(uri: Uri) {
        _uiState.update { state ->
            val list = state.generate.selected.toMutableList()
            val index = list.indexOfFirst { it.uri == uri }
            if (index >= 0) list.removeAt(index)
            state.copy(generate = state.generate.copy(selected = list))
        }
    }

    fun clearGenerateImages() {
        _uiState.update { it.copy(generate = it.generate.copy(selected = emptyList())) }
    }

    fun setGenerateText(value: String) {
        _uiState.update { it.copy(generate = it.generate.copy(text = value)) }
    }

    fun setCopiesText(value: String) {
        val digits = value.filter { it.isDigit() }.take(2)
        _uiState.update { it.copy(generate = it.generate.copy(copiesText = digits)) }
    }

    fun setSerialWidth(width: SerialWidth) {
        _uiState.update { it.copy(generate = it.generate.copy(serialWidth = width)) }
    }

    fun setEcc(ecc: EccLevel) {
        _uiState.update { it.copy(generate = it.generate.copy(ecc = ecc)) }
    }

    fun setUseBatch(enabled: Boolean) {
        _uiState.update { state ->
            val first = state.batches.firstOrNull()?.batch?.batchId
            state.copy(
                generate = state.generate.copy(
                    useBatch = enabled,
                    selectedBatchId = if (enabled) {
                        state.generate.selectedBatchId ?: first
                    } else {
                        state.generate.selectedBatchId
                    },
                ),
            )
        }
    }

    fun selectBatchForGenerate(batchId: String) {
        val batch = _uiState.value.batches.firstOrNull { it.batch.batchId == batchId }?.batch
        _uiState.update { state ->
            state.copy(
                generate = state.generate.copy(
                    useBatch = true,
                    selectedBatchId = batchId,
                    ecc = EccLevel.fromWireName(batch?.ecc) ?: state.generate.ecc,
                ),
            )
        }
    }

    /**
     * 扁平任务列表：总任务数 = Σ 每张源图份数。
     * 编号规则：每张源图独立从 1 开始，步长固定 1。
     * 批次模式：编号直接取映射表 serial，份数由映射表条目数决定。
     */
    fun startEncode() {
        val state = _uiState.value
        val generate = state.generate
        if (generate.task?.running == true) return
        if (generate.selected.isEmpty()) {
            _uiState.update { it.copy(message = "请先选择至少一张图片") }
            return
        }
        if (!TrustMarkNative.isLibraryLoaded) {
            _uiState.update { it.copy(message = "JNI 推理库未加载，请确认 ABI 与 APK 匹配") }
            return
        }

        val useBatch = generate.useBatch
        val batchId = generate.selectedBatchId
        if (useBatch && batchId.isNullOrBlank()) {
            _uiState.update { it.copy(message = "请选择映射表批次，或关闭“使用映射表”") }
            return
        }

        val copies = if (useBatch) 0 else generate.copiesText.toIntOrNull() ?: 0
        if (!useBatch && copies !in 1..MAX_COPIES_PER_IMAGE) {
            _uiState.update { it.copy(message = "每张图片份数必须在 1–$MAX_COPIES_PER_IMAGE 之间") }
            return
        }

        val text = PayloadCodec.prepareText(generate.text)
        if (text.isEmpty()) {
            _uiState.update { it.copy(message = "请输入水印文本（A–Z、0–9、空格、- _ .）") }
            return
        }
        val width = if (generate.serialWidth == SerialWidth.NONE) SerialWidth.BITS_16 else generate.serialWidth
        val ecc = generate.ecc

        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val mapping = if (useBatch) {
                        BatchStore.mappingsFor(application, batchId!!)
                    } else {
                        emptyList()
                    }
                    if (useBatch && mapping.isEmpty()) {
                        error("所选批次还没有映射表条目，请先在“批次与映射”中补全接收人")
                    }
                    val serials = if (useBatch) {
                        mapping.map { it.serial }.sorted()
                    } else {
                        (1..copies).toList()
                    }

                    // 启动前对最小/最大编号做容量预检；正式校验在 validateBatch。
                    val firstPlan = PayloadCodec.plan(text, true, width, serials.first().toLong(), ecc)
                    if (!firstPlan.valid) error(firstPlan.error ?: "payload 容量超限")
                    val lastPlan = PayloadCodec.plan(text, true, width, serials.last().toLong(), ecc)
                    if (!lastPlan.valid) error(lastPlan.error ?: "payload 容量超限")

                    val batch = if (useBatch) {
                        BatchStore.getBatch(application, batchId!!)
                            ?: error("批次不存在：$batchId")
                    } else {
                        // 通用模式无批次时自动创建 G+时间戳批次（先生成后补表）。
                        BatchStore.createGenericBatch(application, ecc.wireName)
                    }

                    val tasks = ArrayList<GenerateTask>(generate.selected.size * serials.size)
                    generate.selected.forEach { image ->
                        serials.forEach { serial ->
                            val encoded = PayloadCodec.encode(
                                text = text,
                                autoNumber = true,
                                serialWidth = width,
                                serial = serial.toLong(),
                                ecc = ecc,
                            )
                            val extension = OutputNaming.extensionFor(image.mimeType, image.displayName)
                            val stem = OutputNaming.buildStem(image.displayName, batch.batchId, serial)
                            tasks += GenerateTask(
                                batchId = batch.batchId,
                                sourceUri = image.uri.toString(),
                                sourceDisplayName = image.displayName,
                                sourceMimeType = image.mimeType,
                                serial = serial,
                                payloadBits = encoded.bits,
                                outputStem = stem,
                                outputFileName = "$stem.$extension",
                                eccWireName = ecc.wireName,
                            )
                        }
                    }
                    PayloadCodec.validateBatch(tasks)?.let { error(it) }
                    TaskPlanStore.write(application, batch.batchId, tasks) to tasks.size
                }

                val path = prepared.first
                val total = prepared.second
                val request = OneTimeWorkRequestBuilder<EncodeWorker>()
                    .setConstraints(storageConstraint())
                    .setInputData(
                        workDataOf(
                            WorkerKeys.TASKS_PATH to path.absolutePath,
                            WorkerKeys.BATCH_ID to batchId,
                            WorkerKeys.TOTAL to total,
                            WorkerKeys.STRENGTH to DEFAULT_STRENGTH,
                        ),
                    )
                    .addTag(WorkerKeys.UNIQUE_ENCODE)
                    .build()

                _uiState.update {
                    it.copy(
                        generate = it.generate.copy(
                            task = TaskProgress(
                                state = TaskState.RUNNING,
                                phase = "排队中",
                                completed = 0,
                                total = total,
                                currentFile = "等待 WorkManager 启动",
                                indeterminate = true,
                            ),
                            lastSummary = null,
                        ),
                        message = null,
                    )
                }
                workManager.enqueueUniqueWork(
                    WorkerKeys.UNIQUE_ENCODE,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
                observeEncode(request.id)
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        generate = it.generate.copy(
                            task = TaskProgress(
                                state = TaskState.ERROR,
                                phase = "处理失败",
                                completed = 0,
                                total = 0,
                                currentFile = "",
                                summary = "任务未启动",
                                error = error.message ?: error.javaClass.simpleName,
                            ),
                        ),
                        message = error.message ?: "任务启动失败",
                    )
                }
            }
        }
    }

    fun cancelEncode() {
        workManager.cancelUniqueWork(WorkerKeys.UNIQUE_ENCODE)
        _uiState.update { state ->
            val task = state.generate.task
            state.copy(
                generate = state.generate.copy(
                    task = if (task == null) {
                        TaskProgress(TaskState.CANCELLED, "已取消", 0, 0, "")
                    } else {
                        task.copy(
                            state = TaskState.CANCELLED,
                            phase = "已取消",
                            summary = "已取消：完成 ${task.completed}/${task.total}",
                        )
                    },
                ),
                message = "正在取消生成任务…",
            )
        }
    }

    // ----------------------------------------------------------------- decode

    fun addDecodeImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.decode.selected
            val room = (MAX_BATCH - current.size).coerceAtLeast(0)
            if (room == 0) {
                _uiState.update { it.copy(message = "最多选择 $MAX_BATCH 张图片") }
                return@launch
            }
            val resolved = resolveImages(uris.take(room))
            val merged = (current + resolved).distinctBy { it.uri }
            _uiState.update { it.copy(decode = it.decode.copy(selected = merged)) }
        }
    }

    fun removeDecodeImage(uri: Uri) {
        _uiState.update { state ->
            state.copy(
                decode = state.decode.copy(
                    selected = state.decode.selected.filterNot { it.uri == uri },
                ),
            )
        }
    }

    fun clearDecodeImages() {
        _uiState.update { it.copy(decode = it.decode.copy(selected = emptyList())) }
    }

    fun startDecode() {
        val state = _uiState.value
        if (state.decode.task?.running == true) return
        if (state.decode.selected.isEmpty()) {
            _uiState.update { it.copy(message = "请先选择至少一张图片") }
            return
        }
        if (!TrustMarkNative.isLibraryLoaded) {
            _uiState.update { it.copy(message = "JNI 推理库未加载，请确认 ABI 与 APK 匹配") }
            return
        }

        val uris = state.decode.selected.map { it.uri.toString() }.toTypedArray()
        val request = OneTimeWorkRequestBuilder<DecodeWorker>()
            .setConstraints(storageConstraint())
            .setInputData(
                workDataOf(
                    WorkerKeys.URIS to uris,
                    WorkerKeys.TOTAL to uris.size,
                ),
            )
            .addTag(WorkerKeys.UNIQUE_DECODE)
            .build()

        _uiState.update {
            it.copy(
                decode = it.decode.copy(
                    task = TaskProgress(
                        state = TaskState.RUNNING,
                        phase = "排队中",
                        completed = 0,
                        total = uris.size,
                        currentFile = "等待 WorkManager 启动",
                        indeterminate = true,
                    ),
                    lastSummary = null,
                ),
                message = null,
            )
        }
        workManager.enqueueUniqueWork(WorkerKeys.UNIQUE_DECODE, ExistingWorkPolicy.KEEP, request)
        observeDecode(request.id)
    }

    fun cancelDecode() {
        workManager.cancelUniqueWork(WorkerKeys.UNIQUE_DECODE)
        _uiState.update { state ->
            val task = state.decode.task
            state.copy(
                decode = state.decode.copy(
                    task = if (task == null) {
                        TaskProgress(TaskState.CANCELLED, "已取消", 0, 0, "")
                    } else {
                        task.copy(
                            state = TaskState.CANCELLED,
                            phase = "已取消",
                            summary = "已取消：完成 ${task.completed}/${task.total}",
                        )
                    },
                ),
                message = "正在取消解密任务…",
            )
        }
    }

    fun exportDecode(uri: Uri, json: Boolean) {
        val records = _uiState.value.decode.results
        if (records.isEmpty()) {
            _uiState.update { it.copy(message = "没有可导出的解密结果") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(decode = it.decode.copy(exporting = true)) }
            try {
                withContext(Dispatchers.IO) {
                    val content = if (json) ExportFormatter.json(records) else ExportFormatter.csv(records)
                    application.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
                _uiState.update { it.copy(message = "解密结果已导出") }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = error.message ?: "导出失败") }
            } finally {
                _uiState.update { it.copy(decode = it.decode.copy(exporting = false)) }
            }
        }
    }

    fun decodeExportFileName(json: Boolean): String =
        ExportFormatter.defaultFileName(if (json) "json" else "csv")

    // ------------------------------------------------------------- batch/map

    fun refreshBatches() {
        viewModelScope.launch {
            val summaries = withContext(Dispatchers.IO) { BatchStore.listSummaries(application) }
            _uiState.update { state ->
                val ids = summaries.map { it.batch.batchId }.toSet()
                state.copy(
                    batches = summaries,
                    generate = state.generate.copy(
                        selectedBatchId = state.generate.selectedBatchId?.takeIf { it in ids },
                    ),
                )
            }
        }
    }

    /**
     * 模式一：先建表再生成。接收人按输入顺序分配编号 1..N。
     */
    fun createMappedBatch(name: String, recipients: List<String>, note: String) {
        val cleanedName = name.trim()
        val cleanedRecipients = recipients.map { it.trim() }
        when {
            cleanedName.isEmpty() -> {
                _uiState.update { it.copy(message = "请输入批次名称") }
                return
            }
            cleanedRecipients.isEmpty() -> {
                _uiState.update { it.copy(message = "请至少填写一位接收人") }
                return
            }
            cleanedRecipients.size > MAX_COPIES_PER_IMAGE -> {
                _uiState.update { it.copy(message = "单个批次最多 $MAX_COPIES_PER_IMAGE 位接收人") }
                return
            }
            cleanedRecipients.any { it.isEmpty() } -> {
                _uiState.update { it.copy(message = "接收人不能为空；请删除空行或填写姓名") }
                return
            }
        }

        val ecc = _uiState.value.generate.ecc
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val batch = BatchStore.createBatch(application, cleanedName, ecc.wireName, note)
                    cleanedRecipients.forEachIndexed { index, recipient ->
                        BatchStore.addMapping(application, batch.batchId, index + 1, recipient, "")
                    }
                    batch to BatchStore.listSummaries(application)
                }
                _uiState.update { state ->
                    state.copy(
                        batches = result.second,
                        generate = state.generate.copy(
                            useBatch = true,
                            selectedBatchId = result.first.batchId,
                        ),
                        message = "批次 ${result.first.batchId} 已创建，共 ${cleanedRecipients.size} 个编号",
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = error.message ?: "创建批次失败") }
            }
        }
    }

    /** 先生成后补表：逐行补填接收人。 */
    fun upsertMapping(batchId: String, serialText: String, recipient: String, note: String) {
        val serial = serialText.trim().toIntOrNull()
        if (serial == null || serial <= 0) {
            _uiState.update { it.copy(message = "编号必须是大于 0 的整数") }
            return
        }
        if (recipient.isBlank()) {
            _uiState.update { it.copy(message = "接收人不能为空") }
            return
        }
        viewModelScope.launch {
            try {
                val summaries = withContext(Dispatchers.IO) {
                    BatchStore.upsertMapping(application, batchId, serial, recipient, note)
                    BatchStore.listSummaries(application)
                }
                _uiState.update { it.copy(batches = summaries, message = "编号 $serial 的接收人已保存") }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = error.message ?: "保存映射失败") }
            }
        }
    }

    fun deleteMapping(batchId: String, serial: Int) {
        viewModelScope.launch {
            try {
                val summaries = withContext(Dispatchers.IO) {
                    BatchStore.deleteMapping(application, batchId, serial)
                    BatchStore.listSummaries(application)
                }
                _uiState.update { it.copy(batches = summaries, message = "已删除编号 $serial 的映射") }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = error.message ?: "删除映射失败") }
            }
        }
    }

    fun deleteBatch(batchId: String) {
        viewModelScope.launch {
            try {
                val summaries = withContext(Dispatchers.IO) {
                    BatchStore.deleteBatch(application, batchId)
                    BatchStore.listSummaries(application)
                }
                _uiState.update { state ->
                    state.copy(
                        batches = summaries,
                        generate = state.generate.copy(
                            selectedBatchId = state.generate.selectedBatchId?.takeIf { id ->
                                summaries.any { it.batch.batchId == id }
                            },
                        ),
                        message = "批次 $batchId 已删除",
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = error.message ?: "删除批次失败") }
            }
        }
    }

    fun exportBatch(uri: Uri, json: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val content = if (json) BatchStore.exportJson(application) else BatchStore.exportCsv(application)
                    application.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
                _uiState.update { it.copy(message = "映射表已导出") }
            } catch (error: Exception) {
                _uiState.update { it.copy(message = error.message ?: "导出映射表失败") }
            }
        }
    }

    fun batchExportFileName(json: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "trustmark_mapping_$stamp.${if (json) "json" else "csv"}"
    }

    // ------------------------------------------------------------------ trace

    fun addTraceImage(uri: Uri) {
        viewModelScope.launch {
            val image = resolveImages(listOf(uri)).firstOrNull()
            if (image == null) {
                _uiState.update { it.copy(message = "无法读取所选图片") }
            } else {
                _uiState.update {
                    it.copy(
                        trace = TraceUiState(
                            selected = image,
                            selectedBatchId = it.trace.selectedBatchId,
                        ),
                    )
                }
            }
        }
    }

    fun selectTraceBatch(batchId: String?) {
        _uiState.update { state ->
            state.copy(trace = state.trace.copy(selectedBatchId = batchId))
        }
    }

    /**
     * 溯源三态严格区分：
     * - 解码抛错 => DECODE_FAILED；
     * - 解码成功但没有批次/编号/映射 => BATCH_NOT_FOUND 或 NO_MATCH；
     * - 批次 + serial 命中映射 => MATCH。
     */
    fun startTrace() {
        val state = _uiState.value
        val selected = state.trace.selected
        if (selected == null) {
            _uiState.update { it.copy(message = "请先选择一张疑似泄露图片") }
            return
        }
        if (state.trace.running) return
        if (!TrustMarkNative.isLibraryLoaded) {
            _uiState.update { it.copy(message = "JNI 推理库未加载，请确认 ABI 与 APK 匹配") }
            return
        }

        _uiState.update {
            it.copy(
                trace = it.trace.copy(
                    status = TraceStatus.DECODING,
                    running = true,
                    error = null,
                    message = null,
                    payloadBits = "",
                    decodedText = null,
                    serial = null,
                    confidence = 0f,
                    bitflips = 0,
                    ecc = null,
                    batchIdFromFile = null,
                    batchId = null,
                    batchName = null,
                    recipient = null,
                    batchCreatedAt = null,
                ),
            )
        }

        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    ModelRepository.ensureModels(application) { }
                    TrustMarkEngine.ensureInitialized(application)
                    val bytes = ImageStore.readBytes(application, selected.uri)
                    val orientation = ImageOrientation.read(application, selected.uri)
                    TrustMarkEngine.decode(application, bytes, orientation)
                }
                val frame = PayloadCodec.decode(decoded.payloadBits)
                val parsed = OutputNaming.parse(selected.displayName)
                val serialLong = frame.serial ?: parsed?.serial?.toLong()
                val serial = serialLong
                    ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                    ?.toInt()
                val batchIdFromFile = parsed?.batchId
                val chosenBatchId = batchIdFromFile ?: _uiState.value.trace.selectedBatchId

                val result = withContext(Dispatchers.IO) {
                    val batch = chosenBatchId?.let { BatchStore.getBatch(application, it) }
                    val mapping = if (batch != null && serial != null) {
                        BatchStore.getMapping(application, batch.batchId, serial)
                    } else {
                        null
                    }
                    Triple(batch, mapping, chosenBatchId)
                }
                val batch = result.first
                val mapping = result.second
                val batchId = result.third

                val base = _uiState.value.trace.copy(
                    running = false,
                    payloadBits = decoded.payloadBits,
                    decodedText = frame.text,
                    serial = serial,
                    confidence = decoded.confidence,
                    bitflips = decoded.bitflips,
                    ecc = decoded.version,
                    batchIdFromFile = batchIdFromFile,
                    batchId = batchId,
                )

                val updated = when {
                    // 文件名解析出了批次短码，但本地没有该批次：单独提示。
                    batchIdFromFile != null && batch == null -> base.copy(
                        status = TraceStatus.BATCH_NOT_FOUND,
                        message = "批次短码存在但本地无此批次记录",
                    )
                    batchId == null -> base.copy(
                        status = TraceStatus.NO_MATCH,
                        message = "未找到对应批次记录",
                    )
                    serial == null -> base.copy(
                        status = TraceStatus.NO_MATCH,
                        message = "水印已解码，但 payload 中没有编号",
                    )
                    mapping == null -> base.copy(
                        status = TraceStatus.NO_MATCH,
                        message = "未找到对应批次记录",
                    )
                    else -> base.copy(
                        status = TraceStatus.MATCH,
                        batchName = batch!!.name,
                        recipient = mapping!!.recipientName.ifBlank { "（未填写接收人）" },
                        batchCreatedAt = batch.createdAt,
                        message = null,
                    )
                }
                _uiState.update { it.copy(trace = updated) }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        trace = it.trace.copy(
                            running = false,
                            status = TraceStatus.DECODE_FAILED,
                            error = error.message ?: error.javaClass.simpleName,
                            message = "水印可能已被破坏或模型变体不匹配。请检查图片是否经过严重压缩或多次转发。",
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ model

    fun downloadModels() {
        if (_uiState.value.model.downloading) return
        viewModelScope.launch {
            try {
                ModelRepository.ensureModels(application) { progress ->
                    _uiState.update {
                        it.copy(
                            model = it.model.copy(
                                checking = false,
                                downloading = !progress.fromAssets || progress.downloadedBytes < progress.totalBytes,
                                downloadedBytes = progress.downloadedBytes,
                                totalBytes = progress.totalBytes,
                                message = progress.currentFile,
                            ),
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        model = it.model.copy(
                            checking = false,
                            ready = true,
                            downloading = false,
                            downloadedBytes = it.model.totalBytes,
                            message = "模型已就绪",
                        ),
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        model = it.model.copy(
                            checking = false,
                            ready = false,
                            downloading = false,
                            message = error.message ?: "模型准备失败",
                        ),
                    )
                }
            }
        }
    }

    private fun refreshModelState() {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { ModelRepository.isReady(application) }
            _uiState.update {
                it.copy(
                    model = it.model.copy(
                        checking = false,
                        ready = ready,
                        totalBytes = ModelRepository.TOTAL_BYTES,
                        downloadedBytes = if (ready) ModelRepository.TOTAL_BYTES else 0L,
                        message = if (ready) "模型已就绪" else "首次使用需要准备 Q 模型",
                    ),
                )
            }
        }
    }

    // -------------------------------------------------------------- observers

    private fun observeEncode(id: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdLiveData(id).asFlow().collect { info ->
                if (info != null) applyEncodeInfo(info)
            }
        }
    }

    private fun observeDecode(id: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdLiveData(id).asFlow().collect { info ->
                if (info != null) applyDecodeInfo(info)
            }
        }
    }

    private fun applyEncodeInfo(info: WorkInfo) {
        val data = info.progress
        val reported = parseTaskState(data.getString(WorkerKeys.STATE))
        val finished = isFinished(info.state)
        val terminal = when {
            info.state == WorkInfo.State.CANCELLED -> TaskState.CANCELLED
            info.state == WorkInfo.State.FAILED && reported == TaskState.CANCELLED -> TaskState.CANCELLED
            info.state == WorkInfo.State.FAILED -> TaskState.ERROR
            info.state == WorkInfo.State.SUCCEEDED -> reported ?: TaskState.COMPLETED
            reported != null -> reported
            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED -> TaskState.RUNNING
            else -> null
        } ?: return

        val previous = _uiState.value.generate.task
        val total = data.getInt(WorkerKeys.TOTAL, previous?.total ?: 0)
        val completed = data.getInt(WorkerKeys.COMPLETED, previous?.completed ?: 0)
        val summary = data.getString(WorkerKeys.SUMMARY)
        val error = data.getString(WorkerKeys.ERROR)
        val phase = data.getString(WorkerKeys.PHASE) ?: when (terminal) {
            TaskState.RUNNING -> previous?.phase ?: "运行中"
            TaskState.COMPLETED -> "处理完成"
            TaskState.CANCELLED -> "已取消"
            TaskState.ERROR -> "处理失败"
        }
        val current = data.getString(WorkerKeys.CURRENT)
            ?: if (finished) "" else previous?.currentFile.orEmpty()

        val progress = TaskProgress(
            state = terminal,
            phase = phase,
            completed = completed,
            total = total,
            currentFile = current,
            indeterminate = !finished && total <= 0,
            summary = summary ?: if (finished && terminal == TaskState.COMPLETED) {
                "成功 $completed / 共 $total"
            } else {
                null
            },
            error = error,
        )

        _uiState.update { state ->
            state.copy(
                generate = state.generate.copy(
                    task = progress,
                    lastSummary = if (finished) progress.summary else state.generate.lastSummary,
                ),
                message = if (finished) {
                    when (terminal) {
                        TaskState.COMPLETED -> "生成完成：${progress.summary ?: "$completed/$total"}"
                        TaskState.CANCELLED -> "生成任务已取消"
                        TaskState.ERROR -> "生成失败：${error ?: "未知错误"}"
                        TaskState.RUNNING -> state.message
                    }
                } else {
                    state.message
                },
            )
        }
        if (finished) refreshBatches()
    }

    private fun applyDecodeInfo(info: WorkInfo) {
        val data = info.progress
        val reported = parseTaskState(data.getString(WorkerKeys.STATE))
        val finished = isFinished(info.state)
        val terminal = when {
            info.state == WorkInfo.State.CANCELLED -> TaskState.CANCELLED
            info.state == WorkInfo.State.FAILED && reported == TaskState.CANCELLED -> TaskState.CANCELLED
            info.state == WorkInfo.State.FAILED -> TaskState.ERROR
            info.state == WorkInfo.State.SUCCEEDED -> reported ?: TaskState.COMPLETED
            reported != null -> reported
            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED -> TaskState.RUNNING
            else -> null
        } ?: return

        val previous = _uiState.value.decode.task
        val total = data.getInt(WorkerKeys.TOTAL, previous?.total ?: 0)
        val completed = data.getInt(WorkerKeys.COMPLETED, previous?.completed ?: 0)
        val summary = data.getString(WorkerKeys.SUMMARY)
        val error = data.getString(WorkerKeys.ERROR)
        val phase = data.getString(WorkerKeys.PHASE) ?: when (terminal) {
            TaskState.RUNNING -> previous?.phase ?: "运行中"
            TaskState.COMPLETED -> "处理完成"
            TaskState.CANCELLED -> "已取消"
            TaskState.ERROR -> "处理失败"
        }
        val progress = TaskProgress(
            state = terminal,
            phase = phase,
            completed = completed,
            total = total,
            currentFile = data.getString(WorkerKeys.CURRENT) ?: if (finished) "" else previous?.currentFile.orEmpty(),
            indeterminate = !finished && total <= 0,
            summary = summary,
            error = error,
        )

        _uiState.update {
            it.copy(
                decode = it.decode.copy(
                    task = progress,
                    lastSummary = if (finished) summary else it.decode.lastSummary,
                ),
                message = if (finished) {
                    when (terminal) {
                        TaskState.COMPLETED -> "解密完成：${summary ?: "$completed/$total"}"
                        TaskState.CANCELLED -> "解密任务已取消"
                        TaskState.ERROR -> "解密失败：${error ?: "未知错误"}"
                        TaskState.RUNNING -> it.message
                    }
                } else {
                    it.message
                },
            )
        }

        if (finished) {
            viewModelScope.launch {
                val records = withContext(Dispatchers.IO) { DecodeStore.load(application) }
                _uiState.update { it.copy(decode = it.decode.copy(results = records)) }
            }
        }
    }

    private fun resumeRunningWork() {
        viewModelScope.launch {
            val encode = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(WorkerKeys.UNIQUE_ENCODE).get()
                    .firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }
            if (encode != null) {
                _uiState.update {
                    it.copy(
                        generate = it.generate.copy(
                            task = TaskProgress(
                                state = TaskState.RUNNING,
                                phase = "恢复任务",
                                completed = encode.progress.getInt(WorkerKeys.COMPLETED, 0),
                                total = encode.progress.getInt(WorkerKeys.TOTAL, 0),
                                currentFile = encode.progress.getString(WorkerKeys.CURRENT).orEmpty(),
                                indeterminate = true,
                            ),
                        ),
                    )
                }
                observeEncode(encode.id)
            }

            val decode = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(WorkerKeys.UNIQUE_DECODE).get()
                    .firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }
            if (decode != null) {
                _uiState.update {
                    it.copy(
                        decode = it.decode.copy(
                            task = TaskProgress(
                                state = TaskState.RUNNING,
                                phase = "恢复任务",
                                completed = decode.progress.getInt(WorkerKeys.COMPLETED, 0),
                                total = decode.progress.getInt(WorkerKeys.TOTAL, 0),
                                currentFile = decode.progress.getString(WorkerKeys.CURRENT).orEmpty(),
                                indeterminate = true,
                            ),
                        ),
                    )
                }
                observeDecode(decode.id)
            }
        }
    }

    // ------------------------------------------------------------------ utils

    private suspend fun resolveImages(uris: List<Uri>): List<SelectedImage> =
        withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    SelectedImage(
                        uri = uri,
                        displayName = ImageStore.displayName(application, uri),
                        sizeBytes = ImageStore.size(application, uri),
                        mimeType = ImageStore.mimeType(application, uri),
                    )
                }.getOrNull()
            }
        }

    private fun storageConstraint(): Constraints =
        Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

    private fun parseTaskState(value: String?): TaskState? =
        value?.let { runCatching { TaskState.valueOf(it) }.getOrNull() }

    private fun isFinished(state: WorkInfo.State): Boolean =
        state == WorkInfo.State.SUCCEEDED ||
            state == WorkInfo.State.FAILED ||
            state == WorkInfo.State.CANCELLED

    companion object {
        const val MAX_BATCH = 50
        const val MAX_COPIES_PER_IMAGE = 99
        private const val DEFAULT_STRENGTH = 0.95f
    }
}
