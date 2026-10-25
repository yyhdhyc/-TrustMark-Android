package com.trustmarktool.data

/**
 * Output naming for v1.2:
 *
 *   {original}_tm.{ext}
 *   {original}_tm_2.{ext}   (storage collision, added by MediaStore/legacy)
 *
 * Batch code and serial are no longer part of the filename: they live inside the
 * batch payload and are recovered by the decoder, not by parsing the path.
 */
object OutputNaming {

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

    fun buildStem(originalName: String): String =
        "${sanitizeBase(originalName)}_tm"

    fun buildFileName(originalName: String, extension: String): String =
        "${buildStem(originalName)}.$extension"

    fun extensionFor(mimeType: String?, originalName: String): String = when {
        mimeType == "image/png" -> "png"
        originalName.lowercase().endsWith(".png") -> "png"
        else -> "jpg"
    }
}
