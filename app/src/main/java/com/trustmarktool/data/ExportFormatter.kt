package com.trustmarktool.data

import com.trustmarktool.model.DecodeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object ExportFormatter {

    fun csv(records: List<DecodeRecord>): String {
        val builder = StringBuilder("\uFEFF")
        builder.append(
            "file_name,frame_type,decoded_text,batch_id,serial,batch_name,recipient," +
                "ecc,confidence,bitflips,payload_bits,error\r\n",
        )
        records.forEach { record ->
            val fields = listOf(
                record.fileName,
                record.frameType.orEmpty(),
                record.decodedText.orEmpty(),
                record.batchId.orEmpty(),
                record.serial?.toString().orEmpty(),
                record.batchName.orEmpty(),
                record.recipient.orEmpty(),
                record.ecc.orEmpty(),
                String.format(Locale.US, "%.4f", record.confidence),
                record.bitflips.toString(),
                record.payloadBits,
                record.error.orEmpty(),
            )
            builder.append(fields.joinToString(",") { escapeCsv(it) }).append("\r\n")
        }
        return builder.toString()
    }

    fun json(records: List<DecodeRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("file_name", record.fileName)
                    put("frame_type", record.frameType ?: JSONObject.NULL)
                    put("decoded_text", record.decodedText ?: JSONObject.NULL)
                    put("batch_id", record.batchId ?: JSONObject.NULL)
                    put("serial", record.serial ?: JSONObject.NULL)
                    put("batch_name", record.batchName ?: JSONObject.NULL)
                    put("recipient", record.recipient ?: JSONObject.NULL)
                    put("ecc", record.ecc ?: JSONObject.NULL)
                    put("confidence", record.confidence.toDouble())
                    put("bitflips", record.bitflips)
                    put("payload_bits", record.payloadBits)
                    put("error", record.error ?: JSONObject.NULL)
                },
            )
        }
        return array.toString(2)
    }

    fun defaultFileName(extension: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        return "trustmark_decoded_$stamp.$extension"
    }

    private fun escapeCsv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
