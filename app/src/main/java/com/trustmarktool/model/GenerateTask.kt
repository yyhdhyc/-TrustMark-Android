package com.trustmarktool.model

enum class GenerateTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

/**
 * 扁平任务模型。编号按源图独立：同一 sourceUri 的副本 serial 从 1 到 N。
 * outputFileName 已包含 tm_b{batch}_n{serial} 标记，Worker 不再计算文件名。
 */
data class GenerateTask(
    val batchId: String,
    val sourceUri: String,
    val sourceDisplayName: String,
    val sourceMimeType: String?,
    val serial: Int,
    val payloadBits: String,
    val outputStem: String,
    val outputFileName: String,
    val eccWireName: String,
    val status: GenerateTaskStatus = GenerateTaskStatus.PENDING,
    val error: String? = null,
)
