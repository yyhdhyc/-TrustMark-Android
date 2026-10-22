package com.trustmarktool.model

/** Worker/UI 的显式终态；MainUiState.task == null 表示 Idle。 */
enum class TaskState {
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR,
}

data class SelectedImage(
    val uri: android.net.Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
)

data class TaskProgress(
    val state: TaskState,
    val phase: String,
    val completed: Int,
    val total: Int,
    val currentFile: String,
    val indeterminate: Boolean = false,
    val summary: String? = null,
    val error: String? = null,
) {
    val running: Boolean get() = state == TaskState.RUNNING

    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

data class DecodeRecord(
    val fileName: String,
    val decodedText: String?,
    val serial: Long?,
    val payloadBits: String,
    val format: String,
    val ecc: String?,
    val confidence: Float,
    val bitflips: Int,
    val error: String?,
) {
    val succeeded: Boolean get() = error == null && payloadBits.isNotEmpty()
}

data class ModelUiState(
    val checking: Boolean = true,
    val ready: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = ModelRepositoryTotals.MODEL_BYTES,
    val message: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Kept outside ModelRepository so the UI state has no Android dependency. */
object ModelRepositoryTotals {
    const val MODEL_BYTES = 64_713_430L
}

data class GenerateUiState(
    val selected: List<SelectedImage> = emptyList(),
    val text: String = "ACME",
    /** 通用模式：每张源图独立生成 1..N 份。 */
    val copiesText: String = "1",
    /** true 时份数和编号来自映射表。 */
    val useBatch: Boolean = false,
    val selectedBatchId: String? = null,
    val serialWidth: SerialWidth = SerialWidth.BITS_16,
    val ecc: EccLevel = EccLevel.BCH_5,
    val task: TaskProgress? = null,
    val lastSummary: String? = null,
)

data class DecodeUiState(
    val selected: List<SelectedImage> = emptyList(),
    val task: TaskProgress? = null,
    val results: List<DecodeRecord> = emptyList(),
    val lastSummary: String? = null,
    val exporting: Boolean = false,
)

enum class TraceStatus {
    IDLE,
    DECODING,
    DECODE_FAILED,
    BATCH_NOT_FOUND,
    NO_MATCH,
    MATCH,
}

/**
 * 三种溯源结论明确分开：
 * 1) DECODE_FAILED：payload 无法恢复；
 * 2) NO_MATCH / BATCH_NOT_FOUND：payload 恢复但本地无对应记录；
 * 3) MATCH：命中批次与接收人。
 */
data class TraceUiState(
    val selected: SelectedImage? = null,
    val selectedBatchId: String? = null,
    val status: TraceStatus = TraceStatus.IDLE,
    val running: Boolean = false,
    val payloadBits: String = "",
    val decodedText: String? = null,
    val serial: Int? = null,
    val confidence: Float = 0f,
    val bitflips: Int = 0,
    val ecc: String? = null,
    val batchIdFromFile: String? = null,
    val batchId: String? = null,
    val batchName: String? = null,
    val recipient: String? = null,
    val batchCreatedAt: Long? = null,
    val error: String? = null,
    val message: String? = null,
)

data class MainUiState(
    val tab: Int = 0,
    val generate: GenerateUiState = GenerateUiState(),
    val decode: DecodeUiState = DecodeUiState(),
    val trace: TraceUiState = TraceUiState(),
    val batches: List<BatchSummary> = emptyList(),
    val model: ModelUiState = ModelUiState(),
    val message: String? = null,
)
