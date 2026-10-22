package com.trustmarktool.data

/**
 * 输出文件名标记格式（必须保留）：
 *
 *   {原文件名}_tm_b{批次短码}_n{编号}.{扩展名}
 *
 * 例：会议合影_tm_bB7K2_n3.jpg
 *
 * 溯源查询优先从该标记解析批次和编号；解析失败时才要求用户手动选择批次。
 */
object OutputNaming {

    // The optional `_2` suffix is added by MediaStore/legacy storage when two
    // source images produce the same visible name (for example IMG_001 in two
    // folders). Trace still needs to recover batch/serial from that file.
    private val MARK = Regex("_tm_b([A-Za-z0-9]+)_n(\\d+)(?:_\\d+)?(?:\\.[A-Za-z0-9]+)?$")

    data class ParsedMark(
        val batchId: String,
        val serial: Int,
    )

    fun sanitizeBase(originalName: String): String {
        val rawBase = originalName.substringBeforeLast('.', originalName)
        return rawBase
            // Keep CJK and spaces; only replace characters that MediaStore or
            // a FAT-style legacy path cannot accept.
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('_', '.', ' ')
            .ifBlank { "image" }
            .take(80)
    }

    fun buildStem(originalName: String, batchId: String, serial: Int): String =
        "${sanitizeBase(originalName)}_tm_b${batchId}_n$serial"

    fun buildFileName(
        originalName: String,
        batchId: String,
        serial: Int,
        extension: String,
    ): String = "${buildStem(originalName, batchId, serial)}.$extension"

    fun extensionFor(mimeType: String?, originalName: String): String = when {
        mimeType == "image/png" -> "png"
        originalName.lowercase().endsWith(".png") -> "png"
        else -> "jpg"
    }

    fun parse(fileName: String): ParsedMark? {
        val match = MARK.find(fileName) ?: return null
        val batchId = match.groupValues[1].uppercase()
        val serial = match.groupValues[2].toIntOrNull() ?: return null
        if (serial <= 0) return null
        return ParsedMark(batchId, serial)
    }
}
