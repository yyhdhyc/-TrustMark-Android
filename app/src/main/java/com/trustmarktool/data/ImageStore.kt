package com.trustmarktool.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

object ImageStore {

    private const val MAX_INPUT_BYTES = 120L * 1024L * 1024L
    private const val ALBUM = "TrustMarkTool"
    private val RELATIVE_DIR = "${Environment.DIRECTORY_PICTURES}/$ALBUM/"

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return fallback ?: "image_${System.currentTimeMillis()}.jpg"
    }

    fun size(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                }
            }
        }
        return 0L
    }

    fun mimeType(context: Context, uri: Uri): String? =
        context.contentResolver.getType(uri)

    fun readBytes(context: Context, uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_INPUT_BYTES) {
                    throw IOException("图片超过 ${MAX_INPUT_BYTES / 1024 / 1024} MB 限制")
                }
                output.write(buffer, 0, read)
            }
        } ?: throw IOException("无法打开图片：$uri")
        if (output.size() == 0) throw IOException("图片为空：$uri")
        return output.toByteArray()
    }

    data class SavedImage(
        val uri: Uri,
        val displayName: String,
    )

    /**
     * Saves to Pictures/TrustMarkTool. The caller supplies the stem
     * `{base}_tm`; the actual extension is chosen from the encoded output bytes
     * (PNG stays PNG, everything else becomes JPEG).
     *
     * MediaStore (API 29+) or a collision-checked file (API 26–28) prevents
     * overwriting the source. If a duplicate stem already exists, `_2` is
     * appended by the platform (`{base}_tm_2.ext`) and the returned displayName
     * is the actual name that must be written to OutputRecord.
     */
    fun saveWatermarked(
        context: Context,
        outputStem: String,
        bytes: ByteArray,
        fallbackMime: String?,
    ): SavedImage {
        val mime = sniffMime(bytes) ?: fallbackMime ?: "image/jpeg"
        val extension = if (mime == "image/png") "png" else "jpg"
        val outputName = "$outputStem.$extension"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, outputName, mime, bytes)
        } else {
            saveLegacy(context, outputName, mime, bytes)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        desiredName: String,
        mime: String,
        bytes: ByteArray,
    ): SavedImage {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = uniqueMediaStoreName(context, collection, desiredName)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore 无法创建输出文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("无法写入 MediaStore 输出")
            val publish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)
            return SavedImage(uri, name)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun uniqueMediaStoreName(
        context: Context,
        collection: Uri,
        desiredName: String,
    ): String {
        val base = desiredName.substringBeforeLast('.')
        val extension = desiredName.substringAfterLast('.', "jpg")
        for (index in 1..9999) {
            val candidate = if (index == 1) desiredName else "${base}_$index.$extension"
            val selection =
                "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?"
            val args = arrayOf(candidate, RELATIVE_DIR)
            val exists = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                selection,
                args,
                null,
            )?.use { it.count > 0 } ?: false
            if (!exists) return candidate
        }
        return "${base}_${System.currentTimeMillis()}.$extension"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        desiredName: String,
        mime: String,
        bytes: ByteArray,
    ): SavedImage {
        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM,
        )
        if (!album.exists() && !album.mkdirs()) {
            throw IOException("无法创建 ${album.absolutePath}")
        }

        val base = desiredName.substringBeforeLast('.')
        val extension = desiredName.substringAfterLast('.', "jpg")
        var file = File(album, desiredName)
        var index = 1
        while (file.exists()) {
            index += 1
            file = File(album, "${base}_$index.$extension")
        }

        file.outputStream().use { it.write(bytes) }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mime),
            null,
        )
        return SavedImage(Uri.fromFile(file), file.name)
    }

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        else -> null
    }
}
