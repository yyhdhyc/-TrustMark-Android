package com.trustmarktool.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trustmarktool.data.BatchStore
import com.trustmarktool.data.ImageOrientation
import com.trustmarktool.data.ImageStore
import com.trustmarktool.data.ModelRepository
import com.trustmarktool.data.TaskPlanStore
import com.trustmarktool.model.GenerateTask
import com.trustmarktool.model.GenerateTaskStatus
import com.trustmarktool.model.OutputRecord
import com.trustmarktool.model.TaskState
import com.trustmarktool.nativebridge.TrustMarkEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes a precomputed flat List<GenerateTask>. There is deliberately no
 * nested source×copy loop here: all numbering, payloads and file names were
 * decided by MainViewModel before WorkManager was enqueued.
 *
 * Per-copy source rule: every task calls ImageStore.readBytes(originalUri)
 * again, and the Rust side calls image::load_from_memory on those fresh bytes.
 * No Bitmap/output bytes from the previous copy are reused, so the encoder is
 * never applied to an already-watermarked image.
 */
class EncodeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val totalAtStart = inputData.getInt(WorkerKeys.TOTAL, 0)
        TaskNotifications.ensureChannel(context)

        var terminalState = TaskState.ERROR
        var terminalSummary = "任务失败"
        var terminalError: String? = null
        var completedSoFar = 0
        var totalTasks = totalAtStart
        var result: Result = Result.failure(
            workDataOf(WorkerKeys.ERROR to "任务失败"),
        )

        try {
            setForeground(
                TaskNotifications.foregroundInfo(
                    context,
                    TaskNotifications.ENCODE_NOTIFICATION_ID,
                    "TrustMark 批量生成",
                    "准备任务…",
                ),
            )

            val tasksPath = inputData.getString(WorkerKeys.TASKS_PATH)
                ?: throw IllegalStateException("缺少任务计划文件")
            val tasks = TaskPlanStore.read(context, tasksPath).toMutableList()
            if (tasks.isEmpty()) throw IllegalStateException("任务列表为空")

            val total = tasks.size
            totalTasks = total
            val strength = inputData.getFloat(WorkerKeys.STRENGTH, 0.95f).coerceIn(0.5f, 1.0f)

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

            var succeeded = 0
            var completed = 0
            var cancelled = false
            val failures = mutableListOf<String>()

            for (index in tasks.indices) {
                if (isStopped) {
                    cancelled = true
                    break
                }

                val task = tasks[index]
                val currentName = task.sourceDisplayName
                report("生成水印", completed, total, currentName)

                // 每个副本都从原始 URI 重新读取；绝不使用上一份副本的输出。
                try {
                    tasks[index] = task.copy(status = GenerateTaskStatus.RUNNING)
                    val uri = Uri.parse(task.sourceUri)
                    val inputBytes = ImageStore.readBytes(context, uri)
                    val orientation = ImageOrientation.read(context, uri)
                    val outputBytes = TrustMarkEngine.encode(
                        context = context,
                        imageBytes = inputBytes,
                        payloadBits = task.payloadBits,
                        eccWireName = task.eccWireName,
                        strength = strength,
                        orientation = orientation,
                    )
                    val saved = ImageStore.saveWatermarked(
                        context = context,
                        outputStem = task.outputStem,
                        bytes = outputBytes,
                        fallbackMime = task.sourceMimeType,
                    )
                    BatchStore.addOutput(
                        context,
                        OutputRecord(
                            batchId = task.batchId,
                            sourceImage = task.sourceDisplayName,
                            serial = task.serial,
                            outputFileName = saved.displayName,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    tasks[index] = task.copy(
                        status = GenerateTaskStatus.COMPLETED,
                        error = null,
                    )
                    succeeded += 1
                } catch (cancelledByWork: CancellationException) {
                    tasks[index] = task.copy(status = GenerateTaskStatus.FAILED, error = "已取消")
                    throw cancelledByWork
                } catch (error: Exception) {
                    val reason = error.message ?: error.javaClass.simpleName
                    tasks[index] = task.copy(
                        status = GenerateTaskStatus.FAILED,
                        error = reason,
                    )
                    failures += "${task.outputFileName}: $reason"
                } finally {
                    // Exactly one increment per task, including failures.
                    completed += 1
                    completedSoFar = completed
                    if (!isStopped) {
                        if (tasks.getOrNull(index)?.status == GenerateTaskStatus.FAILED) {
                            report("继续处理", completed, total, currentName)
                        } else {
                            report("生成水印", completed, total, currentName)
                        }
                    }
                }

                if (isStopped) {
                    cancelled = true
                    break
                }
            }

            val failureFile = File(context.filesDir, "last_encode_failures.txt")
            if (failures.isEmpty()) {
                failureFile.delete()
            } else {
                failureFile.writeText(failures.joinToString("\n"))
            }

            val summary =
                "成功 $succeeded 个副本，失败 ${failures.size} 个，总计 $total 个" +
                    "（每张源图独立编号）"
            if (cancelled || isStopped) {
                terminalState = TaskState.CANCELLED
                terminalSummary = "已取消：完成 $completed/$total，成功 $succeeded 个"
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
            } else if (succeeded == 0) {
                terminalState = TaskState.ERROR
                terminalSummary = summary
                terminalError = "全部副本处理失败：${failures.firstOrNull().orEmpty()}"
                result = Result.failure(
                    workDataOf(
                        WorkerKeys.ERROR to terminalError,
                        WorkerKeys.STATE to terminalState.name,
                        WorkerKeys.SUMMARY to terminalSummary,
                        WorkerKeys.FAILED_COUNT to failures.size,
                        WorkerKeys.TOTAL to total,
                    ),
                )
            } else {
                terminalState = TaskState.COMPLETED
                terminalSummary = summary
                result = Result.success(
                    workDataOf(
                        WorkerKeys.RESULT_COUNT to succeeded,
                        WorkerKeys.FAILED_COUNT to failures.size,
                        WorkerKeys.SUMMARY to summary,
                        WorkerKeys.STATE to terminalState.name,
                        WorkerKeys.COMPLETED to completed,
                        WorkerKeys.TOTAL to total,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            terminalState = TaskState.CANCELLED
            terminalSummary = "已取消：完成 $completedSoFar/$totalTasks"
            terminalError = "任务已取消"
            throw cancelled
        } catch (fatal: Exception) {
            terminalState = TaskState.ERROR
            terminalSummary = "处理失败"
            terminalError = fatal.message ?: fatal.javaClass.simpleName
            result = Result.failure(
                workDataOf(
                    WorkerKeys.ERROR to terminalError,
                    WorkerKeys.STATE to terminalState.name,
                    WorkerKeys.SUMMARY to terminalSummary,
                ),
            )
        } finally {
            // Top-level terminal reset: always cancels the foreground notice and
            // writes an explicit terminal state, not only on the success path.
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
                            WorkerKeys.COMPLETED to completedSoFar,
                            WorkerKeys.TOTAL to totalTasks,
                            WorkerKeys.SUMMARY to terminalSummary,
                            WorkerKeys.ERROR to terminalError.orEmpty(),
                        ),
                    )
                }
                TaskNotifications.cancel(context, TaskNotifications.ENCODE_NOTIFICATION_ID)
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
            TaskNotifications.ENCODE_NOTIFICATION_ID,
            "TrustMark 批量生成 · $phase",
            text,
            completed,
            total,
            indeterminate,
        )
    }
}
