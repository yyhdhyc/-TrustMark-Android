package com.trustmarktool.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trustmarktool.data.DecodeStore
import com.trustmarktool.data.ImageOrientation
import com.trustmarktool.data.ImageStore
import com.trustmarktool.data.ModelRepository
import com.trustmarktool.model.DecodeRecord
import com.trustmarktool.model.PayloadCodec
import com.trustmarktool.model.TaskState
import com.trustmarktool.nativebridge.TrustMarkEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DecodeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        TaskNotifications.ensureChannel(context)

        var total = inputData.getInt(WorkerKeys.TOTAL, 0)
        var completed = 0
        var terminalState = TaskState.ERROR
        var terminalSummary = "任务失败"
        var terminalError: String? = null
        var result: Result = Result.failure(workDataOf(WorkerKeys.ERROR to "任务失败"))
        val records = ArrayList<DecodeRecord>()

        try {
            setForeground(
                TaskNotifications.foregroundInfo(
                    context,
                    TaskNotifications.DECODE_NOTIFICATION_ID,
                    "TrustMark 批量解密",
                    "准备任务…",
                ),
            )

            val uris = inputData.getStringArray(WorkerKeys.URIS).orEmpty().map(Uri::parse)
            if (uris.isEmpty()) throw IllegalStateException("没有选择图片")
            total = uris.size

            report("准备模型", 0, total, "检查 Q 模型完整性", indeterminate = true)
            ModelRepository.ensureModels(context) { progress ->
                val percent = if (progress.totalBytes > 0) {
                    (progress.downloadedBytes * 100 / progress.totalBytes).toInt()
                } else {
                    0
                }
                report(
                    phase = if (progress.fromAssets) "解压模型" else "下载模型",
                    completed = 0,
                    total = total,
                    current = "${progress.currentFile} · $percent%",
                    indeterminate = progress.downloadedBytes <= 0,
                )
            }

            report("加载模型", 0, total, "初始化 ONNX Runtime 会话", indeterminate = true)
            TrustMarkEngine.ensureInitialized(context)

            for (uri in uris) {
                if (isStopped) break

                val fileName = ImageStore.displayName(context, uri)
                report("检测水印", completed, total, fileName)
                try {
                    val bytes = ImageStore.readBytes(context, uri)
                    val orientation = ImageOrientation.read(context, uri)
                    val decoded = TrustMarkEngine.decode(context, bytes, orientation)
                    val frame = PayloadCodec.decode(decoded.payloadBits)
                    records += DecodeRecord(
                        fileName = fileName,
                        decodedText = frame.text,
                        serial = frame.serial,
                        payloadBits = decoded.payloadBits,
                        format = frame.format.label,
                        ecc = decoded.version,
                        confidence = decoded.confidence,
                        bitflips = decoded.bitflips,
                        error = null,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    records += DecodeRecord(
                        fileName = fileName,
                        decodedText = null,
                        serial = null,
                        payloadBits = "",
                        format = "—",
                        ecc = null,
                        confidence = 0f,
                        bitflips = 0,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                } finally {
                    // Exactly one increment per image, including a failed decode.
                    completed += 1
                    if (!isStopped) report("检测水印", completed, total, fileName)
                }
            }

            // Persist partial results in every terminal path.
            DecodeStore.save(context, records)
            val failed = records.count { !it.succeeded }
            val summary = "成功解码 ${records.size - failed} 张，未检测/失败 $failed 张"

            if (isStopped) {
                terminalState = TaskState.CANCELLED
                terminalSummary = "已取消：完成 $completed/$total"
                terminalError = "任务已取消"
                result = Result.failure(
                    workDataOf(
                        WorkerKeys.ERROR to terminalError,
                        WorkerKeys.STATE to terminalState.name,
                        WorkerKeys.SUMMARY to terminalSummary,
                        WorkerKeys.COMPLETED to completed,
                        WorkerKeys.TOTAL to total,
                    ),
                )
            } else {
                terminalState = TaskState.COMPLETED
                terminalSummary = summary
                result = Result.success(
                    workDataOf(
                        WorkerKeys.RESULT_COUNT to (records.size - failed),
                        WorkerKeys.FAILED_COUNT to failed,
                        WorkerKeys.SUMMARY to summary,
                        WorkerKeys.STATE to terminalState.name,
                        WorkerKeys.COMPLETED to completed,
                        WorkerKeys.TOTAL to total,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            runCatching { DecodeStore.save(context, records) }
            terminalState = TaskState.CANCELLED
            terminalSummary = "已取消：完成 $completed/$total"
            terminalError = "任务已取消"
            throw cancelled
        } catch (fatal: Exception) {
            runCatching { DecodeStore.save(context, records) }
            terminalState = TaskState.ERROR
            terminalSummary = "处理失败"
            terminalError = fatal.message ?: fatal.javaClass.simpleName
            result = Result.failure(
                workDataOf(
                    WorkerKeys.ERROR to terminalError,
                    WorkerKeys.STATE to terminalState.name,
                    WorkerKeys.SUMMARY to terminalSummary,
                    WorkerKeys.COMPLETED to completed,
                    WorkerKeys.TOTAL to total,
                ),
            )
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    setProgress(
                        workDataOf(
                            WorkerKeys.STATE to terminalState.name,
                            WorkerKeys.PHASE to when (terminalState) {
                                TaskState.RUNNING -> "运行中"
                                TaskState.COMPLETED -> "处理完成"
                                TaskState.CANCELLED -> "已取消"
                                TaskState.ERROR -> "处理失败"
                            },
                            WorkerKeys.COMPLETED to completed,
                            WorkerKeys.TOTAL to total,
                            WorkerKeys.SUMMARY to terminalSummary,
                            WorkerKeys.ERROR to terminalError.orEmpty(),
                        ),
                    )
                }
                TaskNotifications.cancel(context, TaskNotifications.DECODE_NOTIFICATION_ID)
            }
        }
        return result
    }

    private suspend fun report(
        phase: String,
        completed: Int,
        total: Int,
        current: String,
        indeterminate: Boolean = false,
    ) {
        setProgress(
            workDataOf(
                WorkerKeys.STATE to TaskState.RUNNING.name,
                WorkerKeys.PHASE to phase,
                WorkerKeys.COMPLETED to completed,
                WorkerKeys.TOTAL to total,
                WorkerKeys.CURRENT to current,
            ),
        )
        val text = if (total > 0) "$completed/$total · $current" else current
        TaskNotifications.update(
            applicationContext,
            TaskNotifications.DECODE_NOTIFICATION_ID,
            "TrustMark 批量解密 · $phase",
            text,
            completed,
            total,
            indeterminate,
        )
    }
}
