package com.trustmarktool.model

/**
 * 批次是溯源的最小隔离单位：一次会议/一次分发一个批次。
 * batchId 是短码，例如 B7K2 或 G2510221430，必须出现在输出文件名中。
 */
data class Batch(
    val batchId: String,
    val name: String,
    val createdAt: Long,
    val modelVariant: String = "Q",
    val ecc: String,
    val note: String = "",
)

/**
 * 批次列表的 UI/调度摘要。mappingSerials 是已建表条目编号，按升序保存。
 */
data class BatchSummary(
    val batch: Batch,
    val mappingSerials: List<Int>,
) {
    val mappingCount: Int get() = mappingSerials.size
}

/**
 * 批次导出的一行：映射和输出记录按 batchId + serial 左连接，
 * 因而“有表无图”和“有图无表”都能被导出。
 */
data class BatchExportRow(
    val batchId: String,
    val batchName: String,
    val serial: Int,
    val recipient: String,
    val note: String,
    val outputFileName: String,
    val createdAt: Long,
)
