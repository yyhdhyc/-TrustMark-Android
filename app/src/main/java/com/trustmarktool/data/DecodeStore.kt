package com.trustmarktool.data

import android.content.Context
import com.trustmarktool.model.DecodeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DecodeStore {

    private const val FILE_NAME = "decode_results.json"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun save(context: Context, records: List<DecodeRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("fileName", record.fileName)
                    put("decodedText", record.decodedText ?: JSONObject.NULL)
                    put("serial", record.serial ?: JSONObject.NULL)
                    put("payloadBits", record.payloadBits)
                    put("format", record.format)
                    put("ecc", record.ecc ?: JSONObject.NULL)
                    put("confidence", record.confidence.toDouble())
                    put("bitflips", record.bitflips)
                    put("error", record.error ?: JSONObject.NULL)
                },
            )
        }

        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.writeText(array.toString(2))
        val target = file(context)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun load(context: Context): List<DecodeRecord> {
        val target = file(context)
        if (!target.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(target.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        DecodeRecord(
                            fileName = item.optString("fileName"),
                            decodedText = item.optStringOrNull("decodedText"),
                            serial = item.optLongOrNull("serial"),
                            payloadBits = item.optString("payloadBits"),
                            format = item.optString("format"),
                            ecc = item.optStringOrNull("ecc"),
                            confidence = item.optDouble("confidence", 0.0).toFloat(),
                            bitflips = item.optInt("bitflips"),
                            error = item.optStringOrNull("error"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key, "")
        return value.ifBlank { null }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (isNull(key)) return null
        return if (has(key)) optLong(key) else null
    }
}
