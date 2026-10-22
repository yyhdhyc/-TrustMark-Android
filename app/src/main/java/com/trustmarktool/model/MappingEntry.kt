package com.trustmarktool.model

/** 一个批次内的接收人映射；同一批次 serial 唯一。 */
data class MappingEntry(
    val batchId: String,
    val serial: Int,
    val recipientName: String,
    val note: String = "",
)
