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
import com.trustmarktool.data.ImageStore
import com.trustmarktool.data.ModelRepository
import com.trustmarktool.data.OutputNaming
import com.trustmarktool.data.TaskPlanStore
import com.trustmarktool.model.DecodeRecord
import com.trustmarktool.model.DecodeUiState
import com.trustmarktool.model.EccLevel
import com.trustmarktool.model.GenerateMode
import com.trustmarktool.model.GenerateTask
import com.trustmarktool.model.GenerateUiState
import com.trustmarktool.model.MainUiState
import com.trustmarktool.model.ModelUiState
import com.trustmarktool.model.OutputRecord
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.SelectedImage
import com.trustmarktool.model.TaskProgress
import com.trustmarktool.model.TaskState
import com.trustmarktool.nativebridge.TrustMarkEngine
import com.trustmarktool.nativebridge.TrustMarkNative
import com.trustmarktool.worker.DecodeWorker
import com.trustmarktool.worker.EncodeWorker
import com.trustmarktool.worker.WorkerKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
        _uiState.update { it.copy(tab = index.coerceIn(0, 1)) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    // --------------------------------------------------------------- generate

    /**
     * 同一 URI 允许重复选择：一张源图可以生成多份。去重会破坏按源图独立
     * 编号/多副本的语义，因此这里只按源图数量限制，不做 distinctBy。
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

    fun setGenerateMode(mode: GenerateMode) {
        _uiState.update { state ->
            val firstBatch = state.batches.firstOrNull()?.batch?.batchId
            state.copy(
                generate = state.generate.copy(
                    mode = mode,
                    selectedBatchId = if (mode == GenerateMode.BATCH) {
                        state.generate.selectedBatchId ?: firstBatch
                    } else {
                        state.generate.selectedBatchId
                    },
                    ecc = if (mode == GenerateMode.BATCH) EccLevel.BCH_SUPER else state.generate.ecc,
                ),
                tab = 0,
            )
        }
    }

    fun setGenerateText(value: String) {
        _uiState.update { it.copy(generate = it.generate.copy(text = value)) }
    }

    fun setEcc(ecc: EccLevel) {
        _uiState.update { state ->
            if (state.generate.mode == GenerateMode.BATCH) {
                state
            } else {
                state.copy(generate = state.generate.copy(ecc = ecc))
            }
        }
    }

    /** Batch-only strength: 0.50–2.00, snapped to 0.05. */
    fun setStrength(value: Float) {
        val snapped = ((value.coerceIn(MIN_STRENGTH, MAX_STRENGTH) * 20f).roundToInt() / 20f)
        _uiState.update { it.copy(generate = it.generate.copy(strength = snapped)) }
    }

    fun selectBatchForGenerate(batchId: String) {
        val batch = _uiState.value.batches.firstOrNull { it.batch.batchId == batchId }?.batch
        _uiState.update { state ->
            state.copy(
                generate = state.generate.copy(
                    mode = GenerateMode.BATCH,
                    selectedBatchId = batchId,
                    ecc = EccLevel.fromWireName(batch?.ecc) ?: EccLevel.BCH_SUPER,
                ),
            )
        }
    }

    /**
     * 扁平任务列表：
     * - 通用模式：每张源图一条，payload = 0 + alphabet + length + text；
     * - 批次模式：每张源图 × 映射表 serial 一条，payload = 1 + 4×5-bit code
     *   + 12-bit serial + 7-bit CRC，固定 BCH Super。
     * 每个任务在 Worker 中重新读取原始图片，绝不复用已编码的 Bitmap。
     */
    fun startEncode() {
        val generate = _uiState.value.generate
        if (generate.task?.running == true) return
        if (generate.selected.isEmpty()) {
            _uiState.update { it.copy(message = "请先选择至少一张图片") }
            return
        }
        if (!TrustMarkNative.isLibraryLoaded) {
            _uiState.update { it.copy(message = "JNI 推理库未加载，请确认 ABI 与 APK 匹配") }
            return
        }

        val mode = generate.mode
        val batchId = generate.selectedBatchId
        if (mode == GenerateMode.BATCH && batchId.isNullOrBlank()) {
            _uiState.update { it.copy(message = "请选择一个批次，或先创建批次") }
            return
        }

        val textPlan = if (mode == GenerateMode.UNIVERSAL) {
            PayloadCodec.planUniversal(generate.text, generate.ecc)
        } else {
            null
        }
        if (textPlan != null && !textPlan.valid) {
            _uiState.update { it.copy(message = textPlan.error ?: "文本无法编码") }
            return
        }
        if (mode == GenerateMode.BATCH && generate.strength !in MIN_STRENGTH..MAX_STRENGTH) {
            _uiState.update { it.copy(message = "强度必须在 0.50–2.00 之间") }
            return
        }

        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val tasks = ArrayList<GenerateTask>()
                    var planKey = "universal"

                    if (mode == GenerateMode.UNIVERSAL) {
                        val encoded = PayloadCodec.encodeUniversal(generate.text, generate.ecc)
                        generate.selected.forEach { image ->
                            val extension = OutputNaming.extensionFor(image.mimeType, image.displayName)
                            val stem = OutputNaming.buildStem(image.displayName)
                            tasks += GenerateTask(
                                batchId = "",
                                sourceUri = image.uri.toString(),
                                sourceDisplayName = image.displayName,
                                sourceMimeType = image.mimeType,
                                frameType = PayloadCodec.FrameType.UNIVERSAL,
                                serial = 0,
                                payloadBits = encoded.bits,
                                outputStem = stem,
                                outputFileName = "$stem.$extension",
                                eccWireName = generate.ecc.wireName,
                                strength = 1.0f,
                            )
                        }
                    } else {
                        val batch = BatchStore.getBatch(application, batchId!!)
                            ?: error("批次不存在：$batchId")
                        val mappings = BatchStore.mappingsFor(application, batch.batchId)
                        if (mappings.isEmpty()) {
                            error("所选批次还没有映射表条目，请先在“批次与映射”中补全接收人")
                        }
                        if (mappings.size > PayloadCodec.MAX_BATCH_SERIAL) {
                            error("单个批次最多 ${PayloadCodec.MAX_BATCH_SERIAL} 个编号")
                        }
                        planKey = batch.batchId
                        mappings.sortedBy { it.serial }.forEach { mapping ->
                            val encoded = PayloadCodec.encodeBatch(
                                batchId = batch.batchId,
                                serial = mapping.serial,
                                ecc = EccLevel.BCH_SUPER,
                            )
                            generate.selected.forEach { image ->
                                val extension = OutputNaming.extensionFor(image.mimeType, image.displayName)
                                val stem = OutputNaming.buildStem(image.displayName)
                                tasks += GenerateTask(
                                    batchId = batch.batchId,
                                    sourceUri = image.uri.toString(),
                                    sourceDisplayName = image.displayName,
                                    sourceMimeType = image.mimeType,
                                    frameType = PayloadCodec.FrameType.BATCH,
                                    serial = mapping.serial,
                                    payloadBits = encoded.bits,
                                    outputStem = stem,
                                    outputFileName = "$stem.$extension",
                                    eccWireName = EccLevel.BCH_SUPER.wireName,
                                    strength = generate.strength,
                                )
                            }
                        }
                    }

                    PayloadCodec.validateBatch(tasks)?.let { error(it) }
                    TaskPlanStore.write(application, planKey, tasks) to tasks.size
                }

                val path = prepared.first
                val total = prepared.second
                val request = OneTimeWorkRequestBuilder<EncodeWorker>()
                    .setConstraints(storageConstraint())
                    .setInputData(
                        workDataOf(
                            WorkerKeys.TASKS_PATH to path.absolutePath,
                            WorkerKeys.TOTAL to total,
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
                val keep = state.generate.selectedBatchId?.takeIf { it in ids }
                val fallback = summaries.firstOrNull()?.batch?.batchId
                state.copy(
                    batches = summaries,
                    generate = state.generate.copy(
                        selectedBatchId = if (state.generate.mode == GenerateMode.BATCH) {
                            keep ?: fallback
                        } else {
                            keep
                        },
                    ),
                )
            }
        }
    }

    /**
     * 先建表再生成。接收人按输入顺序分配 1..N；批次短码为 4 个大写字母。
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
            cleanedRecipients.size > PayloadCodec.MAX_BATCH_SERIAL -> {
                _uiState.update {
                    it.copy(message = "单个批次最多 ${PayloadCodec.MAX_BATCH_SERIAL} 位接收人")
                }
                return
            }
            cleanedRecipients.any { it.isEmpty() } -> {
                _uiState.update { it.copy(message = "接收人不能为空；请删除空行或填写姓名") }
                return
            }
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val batch = BatchStore.createBatch(
                        application,
                        cleanedName,
                        EccLevel.BCH_SUPER.wireName,
                        note,
                    )
                    cleanedRecipients.forEachIndexed { index, recipient ->
                        BatchStore.addMapping(application, batch.batchId, index + 1, recipient, "")
                    }
                    batch to BatchStore.listSummaries(application)
                }
                _uiState.update { state ->
                    state.copy(
                        batches = result.second,
                        generate = state.generate.copy(
                            mode = GenerateMode.BATCH,
                            selectedBatchId = result.first.batchId,
                            ecc = EccLevel.BCH_SUPER,
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
        if (serial == null || serial !in 1..PayloadCodec.MAX_BATCH_SERIAL) {
            _uiState.update {
                it.copy(message = "编号必须在 1–${PayloadCodec.MAX_BATCH_SERIAL} 之间")
            }
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
        const val MIN_STRENGTH = 0.5f
        const val MAX_STRENGTH = 2.0f
        const val DEFAULT_STRENGTH = 1.3f
    }
}
