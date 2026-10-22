package com.trustmarktool.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Model delivery policy:
 *  - Prefer files already extracted under filesDir/models.
 *  - Otherwise copy optional assets under assets/models from a custom build.
 *  - Otherwise download from Adobe's official CDN over HTTPS.
 *
 * Every path is verified against a pinned SHA-256 before it is made visible to
 * the native library. Partial downloads use a .part file and are never loaded.
 */
object ModelRepository {

    private const val TAG = "ModelRepository"
    private const val MODEL_BASE_URL =
        "https://cai-watermark.adobe.net/watermarking/trustmark-models"

    data class ModelFile(
        val name: String,
        val size: Long,
        val sha256: String,
    )

    // Q is Adobe's default variant and the one this app is tuned for.
    val FILES = listOf(
        ModelFile(
            name = "encoder_Q.onnx",
            size = 17_312_208L,
            sha256 = "19b3d1b25836130ffd78775a8f61539f993375d1823ef0e59ba5b8dffb4f892d",
        ),
        ModelFile(
            name = "decoder_Q.onnx",
            size = 47_401_222L,
            sha256 = "ee3268f057c9dabef680e169302f5973d0589feea86189ed229a896cc3aa88df",
        ),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.size }

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFile: String,
        val fromAssets: Boolean,
    )

    fun modelsDir(context: Context): File = File(context.filesDir, "models")

    fun isReady(context: Context): Boolean = FILES.all { file ->
        val target = File(modelsDir(context), file.name)
        target.isFile && target.length() == file.size
    }

    fun hasBundledAssets(context: Context): Boolean = FILES.all { assetExists(context, it.name) }

    fun describeStatus(context: Context): String = when {
        isReady(context) -> "Q 模型已就绪（本地）"
        hasBundledAssets(context) -> "Q 模型随 APK 打包，首次任务时解压"
        else -> "首次任务将下载 Q 模型（约 61.7 MB）"
    }

    private val modelMutex = Mutex()

    suspend fun ensureModels(
        context: Context,
        onProgress: suspend (Progress) -> Unit = {},
    ): File = modelMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val dir = modelsDir(appContext)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("无法创建模型目录 ${dir.absolutePath}")
            }

            // Re-verify hashes. The files are large but this is the only point
            // where we can guarantee the native decoder never sees corruption.
            val valid = FILES.associateWith { verifyIfReady(dir, it) }
            if (valid.values.all { it }) {
                onProgress(Progress(TOTAL_BYTES, TOTAL_BYTES, "已就绪", false))
                return@withContext dir
            }

            val required = FILES.filterNot { valid.getValue(it) }.sumOf { it.size }
            val usable = dir.usableSpace
            if (usable in 1 until required) {
                throw IOException(
                    "存储空间不足：需要 ${required / 1024 / 1024} MB，可用 ${usable / 1024 / 1024} MB",
                )
            }

            var completedBytes = 0L

            for (file in FILES) {
                val target = File(dir, file.name)
                if (valid.getValue(file)) {
                    completedBytes += file.size
                    onProgress(Progress(completedBytes, TOTAL_BYTES, file.name, false))
                    continue
                }

                // Remove invalid leftovers before any fallback attempts.
                if (target.exists()) target.delete()

                val copied = copyFromAssets(appContext, file, target) { copiedBytes ->
                    onProgress(
                        Progress(
                            completedBytes + copiedBytes,
                            TOTAL_BYTES,
                            file.name,
                            fromAssets = true,
                        ),
                    )
                }

                if (!copied) {
                    download(appContext, file, target) { downloaded ->
                        onProgress(
                            Progress(
                                completedBytes + downloaded,
                                TOTAL_BYTES,
                                file.name,
                                fromAssets = false,
                            ),
                        )
                    }
                }

                verifyOrThrow(target, file)
                completedBytes += file.size
                onProgress(Progress(completedBytes, TOTAL_BYTES, file.name, false))
            }

            dir
        }
    }

    private fun verifyIfReady(dir: File, file: ModelFile): Boolean {
        val target = File(dir, file.name)
        if (!target.isFile || target.length() != file.size) return false
        return runCatching { sha256(target) == file.sha256 }.getOrDefault(false)
    }

    private fun assetExists(context: Context, name: String): Boolean = runCatching {
        context.assets.open("models/$name").use { }
        true
    }.getOrDefault(false)

    private suspend fun copyFromAssets(
        context: Context,
        file: ModelFile,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ): Boolean {
        val input = try {
            context.assets.open("models/${file.name}")
        } catch (_: FileNotFoundException) {
            return false
        }

        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        try {
            input.use { source ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                }
            }
            verifyOrThrow(part, file)
            replace(part, target)
            return true
        } catch (error: Exception) {
            part.delete()
            Log.w(TAG, "Bundled asset copy failed for ${file.name}", error)
            throw error
        } finally {
            runCatching { input.close() }
        }
    }

    private suspend fun download(
        context: Context,
        file: ModelFile,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        val connection = (URL("$MODEL_BASE_URL/${file.name}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "TrustMarkTool-Android/1.0")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("模型下载失败：HTTP $status（${file.name}）")
            }

            val expectedLength = connection.contentLengthLong
            if (expectedLength > 0 && expectedLength != file.size) {
                throw IOException("模型大小异常：CDN 返回 $expectedLength，预期 ${file.size}")
            }

            connection.inputStream.use { source ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded)
                    }
                    if (downloaded != file.size) {
                        throw IOException("模型下载不完整：$downloaded / ${file.size}")
                    }
                }
            }

            val digest = sha256(part)
            if (digest != file.sha256) {
                throw IOException("SHA-256 校验失败：$digest，已拒绝加载 ${file.name}")
            }
            replace(part, target)
        } catch (error: Exception) {
            part.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyOrThrow(file: File, expected: ModelFile) {
        if (!file.isFile || file.length() != expected.size) {
            throw IOException("模型 ${expected.name} 大小不正确")
        }
        val digest = sha256(file)
        if (digest != expected.sha256) {
            throw IOException("模型 ${expected.name} SHA-256 不匹配：$digest")
        }
    }

    private fun replace(part: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("无法替换 ${target.absolutePath}")
        }
        if (!part.renameTo(target)) {
            // Fallback for filesystems that refuse rename across handles.
            part.copyTo(target, overwrite = true)
            if (!part.delete()) Log.w(TAG, "Could not delete ${part.absolutePath}")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
