package com.trustmarktool.model

/** 一次成功生成所对应的输出文件记录，用于“先生成后补表”。 */
data class OutputRecord(
    val batchId: String,
    val sourceImage: String,
    val serial: Int,
    val outputFileName: String,
    val createdAt: Long,
)
