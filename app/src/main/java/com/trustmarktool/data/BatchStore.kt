package com.trustmarktool.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.trustmarktool.model.Batch
import com.trustmarktool.model.BatchExportRow
import com.trustmarktool.model.BatchSummary
import com.trustmarktool.model.MappingEntry
import com.trustmarktool.model.OutputRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 批次、映射条目、输出记录的持久化。
 *
 * 安全约束：数据只写入应用私有目录 `filesDir/batch_store.enc`，并使用
 * Android Keystore 中的 AES-256-GCM 密钥加密；Keystore 私钥不可导出。
 * 不写外部存储，不写 SharedPreferences 明文。
 *
 * 同一个文件内保存所有批次，但每条记录都带 batchId；所有查询/导出都按
 * batchId 隔离，一个批次对应一次会议或一次分发。
 */
object BatchStore {

    private const val FILE_NAME = "batch_store.enc"
    private const val KEY_ALIAS = "trustmark_batch_store_key_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val MAGIC = "TMB1"
    private const val VERSION = 1
    private const val GCM_TAG_BITS = 128

    // v1.2 batch code: exactly four letters A-Z (20 bits in the batch frame).
    private const val CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val lock = Any()

    data class Snapshot(
        val batches: List<Batch> = emptyList(),
        val mappings: List<MappingEntry> = emptyList(),
        val outputs: List<OutputRecord> = emptyList(),
    )

    // ------------------------------------------------------------------ batch

    fun listBatches(context: Context): List<Batch> = synchronized(lock) {
        read(context).batches.sortedByDescending { it.createdAt }
    }

    fun listSummaries(context: Context): List<BatchSummary> = synchronized(lock) {
        val snapshot = read(context)
        snapshot.batches
            .filter { com.trustmarktool.model.PayloadCodec.isValidBatchCode(it.batchId) }
            .sortedByDescending { it.createdAt }
            .map { batch ->
                BatchSummary(
                    batch = batch,
                    mappingSerials = snapshot.mappings
                        .asSequence()
                        .filter { it.batchId == batch.batchId }
                        .map { it.serial }
                        .distinct()
                        .sorted()
                        .toList(),
                )
            }
    }

    fun getBatch(context: Context, batchId: String): Batch? = synchronized(lock) {
        read(context).batches.firstOrNull { it.batchId == batchId }
    }

    /**
     * “先建表再生成”的入口。短码自动生成，形如 BQKZ（4 个字母 A–Z），
     * 并在存储内唯一。旧版 B+3 位、G+时间戳短码不再生成。
     */
    fun createBatch(
        context: Context,
        name: String,
        ecc: String = "BCH_SUPER",
        note: String = "",
    ): Batch = synchronized(lock) {
        val snapshot = read(context)
        val existing = snapshot.batches.map { it.batchId }.toSet()
        val batch = Batch(
            batchId = newBatchCode(existing),
            name = name.trim().ifBlank { "未命名批次" },
            createdAt = System.currentTimeMillis(),
            modelVariant = "Q",
            ecc = ecc,
            note = note.trim(),
        )
        write(context, snapshot.copy(batches = snapshot.batches + batch))
        batch
    }

    fun updateBatch(context: Context, batch: Batch): Boolean = synchronized(lock) {
        val snapshot = read(context)
        if (snapshot.batches.none { it.batchId == batch.batchId }) return@synchronized false
        val updated = snapshot.batches.map { if (it.batchId == batch.batchId) batch else it }
        write(context, snapshot.copy(batches = updated))
        true
    }

    fun deleteBatch(context: Context, batchId: String): Boolean = synchronized(lock) {
        val snapshot = read(context)
        if (snapshot.batches.none { it.batchId == batchId }) return@synchronized false
        write(
            context,
            snapshot.copy(
                batches = snapshot.batches.filterNot { it.batchId == batchId },
                mappings = snapshot.mappings.filterNot { it.batchId == batchId },
                outputs = snapshot.outputs.filterNot { it.batchId == batchId },
            ),
        )
        true
    }

    // ---------------------------------------------------------------- mapping

    fun mappingsFor(context: Context, batchId: String): List<MappingEntry> = synchronized(lock) {
        read(context).mappings
            .filter { it.batchId == batchId }
            .sortedBy { it.serial }
    }

    fun getMapping(context: Context, batchId: String, serial: Int): MappingEntry? =
        synchronized(lock) {
            read(context).mappings.firstOrNull {
                it.batchId == batchId && it.serial == serial
            }
        }

    /** 新增映射；同一批次 serial 重复时抛出异常，由 UI 显示原因。 */
    fun addMapping(
        context: Context,
        batchId: String,
        serial: Int,
        recipientName: String,
        note: String = "",
    ): MappingEntry = synchronized(lock) {
        require(serial in 1..com.trustmarktool.model.PayloadCodec.MAX_BATCH_SERIAL) {
            "编号必须在 1–${com.trustmarktool.model.PayloadCodec.MAX_BATCH_SERIAL} 之间"
        }
        val snapshot = read(context)
        require(snapshot.batches.any { it.batchId == batchId }) { "批次不存在：$batchId" }
        require(snapshot.mappings.none { it.batchId == batchId && it.serial == serial }) {
            "批次 $batchId 内编号 $serial 已存在"
        }
        val entry = MappingEntry(batchId, serial, recipientName.trim(), note.trim())
        write(context, snapshot.copy(mappings = snapshot.mappings + entry))
        entry
    }

    /** 有则更新、无则新增；用于“生成后补表”逐行保存。 */
    fun upsertMapping(
        context: Context,
        batchId: String,
        serial: Int,
        recipientName: String,
        note: String = "",
    ): MappingEntry = synchronized(lock) {
        require(serial in 1..com.trustmarktool.model.PayloadCodec.MAX_BATCH_SERIAL) {
            "编号必须在 1–${com.trustmarktool.model.PayloadCodec.MAX_BATCH_SERIAL} 之间"
        }
        val snapshot = read(context)
        require(snapshot.batches.any { it.batchId == batchId }) { "批次不存在：$batchId" }
        val entry = MappingEntry(batchId, serial, recipientName.trim(), note.trim())
        val index = snapshot.mappings.indexOfFirst {
            it.batchId == batchId && it.serial == serial
        }
        val mappings = if (index >= 0) {
            snapshot.mappings.toMutableList().also { it[index] = entry }
        } else {
            snapshot.mappings + entry
        }
        write(context, snapshot.copy(mappings = mappings))
        entry
    }

    fun deleteMapping(context: Context, batchId: String, serial: Int): Boolean =
        synchronized(lock) {
            val snapshot = read(context)
            val remaining = snapshot.mappings.filterNot {
                it.batchId == batchId && it.serial == serial
            }
            if (remaining.size == snapshot.mappings.size) return@synchronized false
            write(context, snapshot.copy(mappings = remaining))
            true
        }

    // ----------------------------------------------------------------- output

    fun outputsFor(context: Context, batchId: String): List<OutputRecord> = synchronized(lock) {
        read(context).outputs
            .filter { it.batchId == batchId }
            .sortedWith(compareBy({ it.serial }, { it.createdAt }))
    }

    fun addOutput(context: Context, record: OutputRecord): OutputRecord = synchronized(lock) {
        val snapshot = read(context)
        write(context, snapshot.copy(outputs = snapshot.outputs + record))
        record
    }

    fun deleteOutput(context: Context, batchId: String, outputFileName: String): Boolean =
        synchronized(lock) {
            val snapshot = read(context)
            val remaining = snapshot.outputs.filterNot {
                it.batchId == batchId && it.outputFileName == outputFileName
            }
            if (remaining.size == snapshot.outputs.size) return@synchronized false
            write(context, snapshot.copy(outputs = remaining))
            true
        }

    // ------------------------------------------------------------------ export

    fun exportRows(context: Context): List<BatchExportRow> = synchronized(lock) {
        val snapshot = read(context)
        val batches = snapshot.batches.associateBy { it.batchId }
        val mappings = snapshot.mappings.groupBy { it.batchId to it.serial }
        val outputs = snapshot.outputs.groupBy { it.batchId to it.serial }
        val keys = (mappings.keys + outputs.keys).distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))

        keys.flatMap { key ->
            val batch = batches[key.first] ?: return@flatMap emptyList()
            val mapping = mappings[key].orEmpty().firstOrNull()
            val records = outputs[key].orEmpty()
            if (records.isEmpty()) {
                listOf(
                    BatchExportRow(
                        batchId = batch.batchId,
                        batchName = batch.name,
                        serial = key.second,
                        recipient = mapping?.recipientName.orEmpty(),
                        note = mapping?.note.orEmpty(),
                        outputFileName = "",
                        createdAt = batch.createdAt,
                    ),
                )
            } else {
                records.map { record ->
                    BatchExportRow(
                        batchId = batch.batchId,
                        batchName = batch.name,
                        serial = key.second,
                        recipient = mapping?.recipientName.orEmpty(),
                        note = mapping?.note.orEmpty(),
                        outputFileName = record.outputFileName,
                        createdAt = record.createdAt,
                    )
                }
            }
        }
    }

    fun exportCsv(context: Context): String {
        val header = "batch_id,batch_name,serial,recipient,note,output_file,created_at"
        val rows = exportRows(context).map { row ->
            listOf(
                row.batchId,
                row.batchName,
                row.serial.toString(),
                row.recipient,
                row.note,
                row.outputFileName,
                formatTime(row.createdAt),
            ).joinToString(",") { csvCell(it) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    fun exportJson(context: Context): String {
        val array = JSONArray()
        exportRows(context).forEach { row ->
            array.put(
                JSONObject().apply {
                    put("batch_id", row.batchId)
                    put("batch_name", row.batchName)
                    put("serial", row.serial)
                    put("recipient", row.recipient)
                    put("note", row.note)
                    put("output_file", row.outputFileName)
                    put("created_at", formatTime(row.createdAt))
                },
            )
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("exported_at", formatTime(System.currentTimeMillis()))
            put("rows", array)
        }.toString(2)
    }

    fun formatTime(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(epochMillis))
    }

    // --------------------------------------------------------------- internals

    private fun read(context: Context): Snapshot {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.isFile || file.length() == 0L) return Snapshot()
        val plain = decrypt(file.readBytes())
        val root = JSONObject(String(plain, Charsets.UTF_8))
        return Snapshot(
            batches = root.optJSONArray("batches").toObjects { obj ->
                Batch(
                    batchId = obj.getString("batchId"),
                    name = obj.optString("name", "未命名批次"),
                    createdAt = obj.optLong("createdAt", 0L),
                    modelVariant = obj.optString("modelVariant", "Q"),
                    ecc = obj.optString("ecc", "BCH_5"),
                    note = obj.optString("note", ""),
                )
            },
            mappings = root.optJSONArray("mappings").toObjects { obj ->
                MappingEntry(
                    batchId = obj.getString("batchId"),
                    serial = obj.getInt("serial"),
                    recipientName = obj.optString("recipientName", ""),
                    note = obj.optString("note", ""),
                )
            },
            outputs = root.optJSONArray("outputs").toObjects { obj ->
                OutputRecord(
                    batchId = obj.getString("batchId"),
                    sourceImage = obj.optString("sourceImage", ""),
                    serial = obj.getInt("serial"),
                    outputFileName = obj.optString("outputFileName", ""),
                    createdAt = obj.optLong("createdAt", 0L),
                )
            },
        )
    }

    private fun write(context: Context, snapshot: Snapshot) {
        val root = JSONObject().apply {
            put("version", VERSION)
            put("batches", JSONArray().apply {
                snapshot.batches.forEach { batch ->
                    put(
                        JSONObject().apply {
                            put("batchId", batch.batchId)
                            put("name", batch.name)
                            put("createdAt", batch.createdAt)
                            put("modelVariant", batch.modelVariant)
                            put("ecc", batch.ecc)
                            put("note", batch.note)
                        },
                    )
                }
            })
            put("mappings", JSONArray().apply {
                snapshot.mappings.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("batchId", entry.batchId)
                            put("serial", entry.serial)
                            put("recipientName", entry.recipientName)
                            put("note", entry.note)
                        },
                    )
                }
            })
            put("outputs", JSONArray().apply {
                snapshot.outputs.forEach { record ->
                    put(
                        JSONObject().apply {
                            put("batchId", record.batchId)
                            put("sourceImage", record.sourceImage)
                            put("serial", record.serial)
                            put("outputFileName", record.outputFileName)
                            put("createdAt", record.createdAt)
                        },
                    )
                }
            })
        }

        val target = File(context.applicationContext.filesDir, FILE_NAME)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeBytes(encrypt(root.toString().toByteArray(Charsets.UTF_8)))
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IllegalStateException("无法替换映射表文件")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(iv.size)
            write(iv)
            write(ciphertext)
        }.toByteArray()
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > MAGIC.length + 1 + 12) { "映射表文件损坏" }
        val magic = String(payload, 0, MAGIC.length, Charsets.US_ASCII)
        require(magic == MAGIC) { "映射表文件格式无效" }
        val ivLength = payload[MAGIC.length].toInt() and 0xFF
        require(ivLength in 12..16) { "映射表 IV 长度无效" }
        val ivStart = MAGIC.length + 1
        val iv = payload.copyOfRange(ivStart, ivStart + ivLength)
        val ciphertext = payload.copyOfRange(ivStart + ivLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun newBatchCode(existing: Set<String>): String {
        repeat(1000) {
            val code = buildString {
                repeat(4) { append(CODE_ALPHABET.random()) }
            }
            if (code !in existing) return code
        }
        throw IllegalStateException("无法生成唯一批次短码")
    }

    private fun <T> JSONArray?.toObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) add(transform(getJSONObject(index)))
        }
    }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }
}
