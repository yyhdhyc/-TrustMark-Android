package com.trustmarktool.data

import android.content.Context
import com.trustmarktool.model.GenerateTask
import com.trustmarktool.model.GenerateTaskStatus
import com.trustmarktool.model.PayloadCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Worker 输入上限只有 10 KB，无法直接携带 50×99 个任务。View 层先生成
 * List<GenerateTask>，序列化到应用私有目录，再只把文件路径交给 Worker。
 * Worker 严格按这个扁平列表逐个执行，不做嵌套循环。
 */
object TaskPlanStore {

    private const val DIR = "task_plans"

    fun write(context: Context, batchId: String, tasks: List<GenerateTask>): File {
        require(tasks.isNotEmpty()) { "任务列表为空" }
        val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${batchId}_${System.currentTimeMillis()}_${UUID.randomUUID()}.json")
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject().apply {
                    put("batchId", task.batchId)
                    put("sourceUri", task.sourceUri)
                    put("sourceDisplayName", task.sourceDisplayName)
                    put("sourceMimeType", task.sourceMimeType ?: JSONObject.NULL)
                    put("frameType", task.frameType.name)
                    put("serial", task.serial)
                    put("payloadBits", task.payloadBits)
                    put("outputStem", task.outputStem)
                    put("outputFileName", task.outputFileName)
                    put("eccWireName", task.eccWireName)
                    put("strength", task.strength.toDouble())
                    put("status", task.status.name)
                    put("error", task.error ?: JSONObject.NULL)
                },
            )
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return file
    }

    fun read(context: Context, path: String): List<GenerateTask> {
        val file = File(path)
        require(file.isFile) { "任务计划文件不存在：$path" }
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                add(
                    GenerateTask(
                        batchId = obj.getString("batchId"),
                        sourceUri = obj.getString("sourceUri"),
                        sourceDisplayName = obj.optString("sourceDisplayName", "image.jpg"),
                        sourceMimeType = obj.optString("sourceMimeType").takeIf { it.isNotBlank() },
                        frameType = runCatching {
                            PayloadCodec.FrameType.valueOf(obj.optString("frameType", "UNIVERSAL"))
                        }.getOrDefault(PayloadCodec.FrameType.UNIVERSAL),
                        serial = obj.getInt("serial"),
                        payloadBits = obj.getString("payloadBits"),
                        outputStem = obj.getString("outputStem"),
                        outputFileName = obj.getString("outputFileName"),
                        eccWireName = obj.optString("eccWireName", "BCH_5"),
                        strength = obj.optDouble("strength", 0.95).toFloat(),
                        status = runCatching {
                            GenerateTaskStatus.valueOf(obj.optString("status", "PENDING"))
                        }.getOrDefault(GenerateTaskStatus.PENDING),
                        error = obj.optString("error").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
