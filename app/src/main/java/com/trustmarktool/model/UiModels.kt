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

/**
 * Decode result. Batch fields are filled only after a CRC-valid batch frame is
 * decoded and looked up in BatchStore. `batchName`/`recipient` stay null on a
 * local miss (yellow state), while CRC failure is an error (red state).
 */
data class DecodeRecord(
    val fileName: String,
    val frameType: String?,
    val decodedText: String?,
    val batchId: String?,
    val serial: Int?,
    val batchName: String?,
    val recipient: String?,
    val payloadBits: String,
    val ecc: String?,
    val confidence: Float,
    val bitflips: Int,
    val error: String?,
) {
    val succeeded: Boolean get() = error == null && payloadBits.isNotEmpty()

    val batchMatched: Boolean get() = recipient != null
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

enum class GenerateMode {
    UNIVERSAL,
    BATCH,
}

data class GenerateUiState(
    val selected: List<SelectedImage> = emptyList(),
    val mode: GenerateMode = GenerateMode.UNIVERSAL,
    val text: String = "ACME",
    val ecc: EccLevel = EccLevel.BCH_5,
    /** Batch-only indestructible strength; range 0.50–2.00, default 1.30. */
    val strength: Float = 1.3f,
    val selectedBatchId: String? = null,
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

data class MainUiState(
    val tab: Int = 0,
    val generate: GenerateUiState = GenerateUiState(),
    val decode: DecodeUiState = DecodeUiState(),
    val batches: List<BatchSummary> = emptyList(),
    val model: ModelUiState = ModelUiState(),
    val message: String? = null,
)
