package com.trustmarktool.model

enum class GenerateTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

/**
 * Flat task model. A universal task has batchId="" and serial=0; a batch task
 * carries its 4-letter batch code and 1..4095 serial. The output name is
 * decided before WorkManager starts, so the worker never re-derives numbering.
 *
 * strength is stored per task because batch mode exposes an adjustable value
 * (0.50–2.00); universal mode keeps the historical 0.95 default.
 */
data class GenerateTask(
    val batchId: String,
    val sourceUri: String,
    val sourceDisplayName: String,
    val sourceMimeType: String?,
    val frameType: PayloadCodec.FrameType,
    val serial: Int,
    val payloadBits: String,
    val outputStem: String,
    val outputFileName: String,
    val eccWireName: String,
    val strength: Float,
    val status: GenerateTaskStatus = GenerateTaskStatus.PENDING,
    val error: String? = null,
)
