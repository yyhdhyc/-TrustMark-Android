package com.trustmarktool.model

import java.util.Locale

/**
 * ============================================================================
 *  TEXT -> BINARY ENCODER (the layer TrustMark's Rust API does not provide)
 * ============================================================================
 *
 * TrustMark only accepts a 0/1 bit string. It zero-pads to the selected BCH
 * capacity, appends BCH parity and four schema bits, and feeds 100 floats to
 * encoder_Q.onnx. The usable data capacities are:
 *
 *   BCH_SUPER = 40, BCH_5 = 61, BCH_4 = 68, BCH_3 = 75 bits.
 *
 * v1.2 defines two self-describing frames. The first bit is the frame type.
 *
 * Universal (bit 0 = 0):
 *   type            1 bit   0
 *   text alphabet   1 bit   0 = A-Z (5 bits/char), 1 = alnum (6 bits/char)
 *   text length     4 bits  1..15
 *   text            L * (5 or 6) bits
 *   header total    6 bits
 *
 * Batch (bit 0 = 1):
 *   type            1 bit   1
 *   batch code      20 bits 4 letters, each A-Z = 0..25, 5 bits
 *   serial          12 bits 1..4095
 *   CRC-7           7 bits  CRC-7/MMC over the first 33 bits
 *   total           40 bits  always BCH_SUPER
 *
 * The batch code + 12-bit serial combination is unique. A single batch can
 * therefore hold at most 4095 images. There is deliberately no serial field in
 * the universal frame: a fixed text is self-contained and needs no numbering.
 *
 * The UI must never claim arbitrary text fits; [planUniversal] computes the
 * exact budget. [validateBatch] is the final gate before WorkManager starts.
 */
object PayloadCodec {

    const val MAX_TEXT_LENGTH = 15
    const val UNIVERSAL_HEADER_BITS = 6
    const val BATCH_HEADER_BITS = 1
    const val BATCH_CODE_BITS = 20
    const val BATCH_CODE_LENGTH = 4
    const val BATCH_SERIAL_BITS = 12
    const val BATCH_CRC_BITS = 7
    const val BATCH_TOTAL_BITS = 40
    const val MAX_BATCH_SERIAL = 4095

    /** 26 letters + 10 digits + '-', '_', '.', ' ' = 40 values, exactly 6 bits. */
    const val ALNUM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ ."

    private val BATCH_CODE_REGEX = Regex("[A-Z]{4}")

    enum class TextMode(val label: String, val bitsPerChar: Int) {
        ALPHA("纯大写字母", 5),
        ALNUM("字母/数字/符号", 6),
    }

    enum class FrameType(val label: String) {
        UNIVERSAL("通用"),
        BATCH("批次"),
    }

    data class PayloadPlan(
        val valid: Boolean,
        val frameType: FrameType,
        val text: String = "",
        val textMode: TextMode = TextMode.ALPHA,
        val batchId: String? = null,
        val serial: Int? = null,
        val headerBits: Int,
        val textBits: Int = 0,
        val serialBits: Int = 0,
        val crcBits: Int = 0,
        val totalBits: Int,
        val capacityBits: Int,
        val remainingBits: Int,
        val maxAlphaLength: Int = 0,
        val maxAlnumLength: Int = 0,
        val crc7: Int? = null,
        val error: String? = null,
    )

    data class EncodedFrame(
        val bits: String,
        val plan: PayloadPlan,
    )

    data class DecodedFrame(
        val valid: Boolean,
        val frameType: FrameType?,
        val text: String? = null,
        val batchId: String? = null,
        val serial: Int? = null,
        val crc7: Int? = null,
        val crcValid: Boolean = false,
        val note: String? = null,
        val error: String? = null,
    )

    /**
     * Keep only characters representable by the frame, uppercase ASCII, and
     * collapse runs of spaces. No silent capacity truncation happens here.
     */
    fun sanitizeInput(raw: String): String {
        val upper = raw.uppercase(Locale.US)
        val filtered = buildString(upper.length) {
            for (ch in upper) {
                if (ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == ' ') {
                    append(ch)
                }
            }
        }
        return filtered.replace(Regex(" {2,}"), " ")
    }

    fun prepareText(raw: String): String = sanitizeInput(raw).trim()

    // --------------------------------------------------------------- universal

    fun planUniversal(text: String, ecc: EccLevel): PayloadPlan {
        val prepared = prepareText(text)
        val mode = if (prepared.isNotEmpty() && prepared.all { it in 'A'..'Z' }) {
            TextMode.ALPHA
        } else {
            TextMode.ALNUM
        }
        val textBits = prepared.length * mode.bitsPerChar
        val total = UNIVERSAL_HEADER_BITS + textBits
        val remaining = ecc.dataBits - total

        val error = when {
            prepared.isEmpty() ->
                "请输入至少 1 个字符（A–Z、0–9、空格、- _ .）"
            prepared.length > MAX_TEXT_LENGTH ->
                "文本最多 $MAX_TEXT_LENGTH 个字符，当前 ${prepared.length} 个"
            total > ecc.dataBits ->
                "需要 $total 位，但 ${ecc.wireName} 只有 ${ecc.dataBits} 位；请缩短文本或选择容量更大的纠错等级"
            else -> null
        }

        return PayloadPlan(
            valid = error == null,
            frameType = FrameType.UNIVERSAL,
            text = prepared,
            textMode = mode,
            headerBits = UNIVERSAL_HEADER_BITS,
            textBits = textBits,
            totalBits = total,
            capacityBits = ecc.dataBits,
            remainingBits = remaining,
            maxAlphaLength = maxTextLength(ecc, TextMode.ALPHA),
            maxAlnumLength = maxTextLength(ecc, TextMode.ALNUM),
            error = error,
        )
    }

    fun encodeUniversal(text: String, ecc: EccLevel): EncodedFrame {
        val plan = planUniversal(text, ecc)
        require(plan.valid) { plan.error ?: "payload 不合法" }

        val bits = buildString(plan.totalBits) {
            append('0')
            append(if (plan.textMode == TextMode.ALNUM) '1' else '0')
            append(plan.text.length.toBitString(4))
            for (ch in plan.text) {
                if (plan.textMode == TextMode.ALPHA) {
                    append((ch - 'A').toBitString(5))
                } else {
                    val index = ALNUM_ALPHABET.indexOf(ch)
                    require(index >= 0) { "字符 '$ch' 不在 6-bit 字母表中" }
                    append(index.toBitString(6))
                }
            }
        }
        check(bits.length == plan.totalBits)
        check(bits.length <= ecc.dataBits)
        return EncodedFrame(bits, plan)
    }

    // ------------------------------------------------------------------- batch

    fun normalizeBatchCode(raw: String): String = raw.trim().uppercase(Locale.US)

    fun isValidBatchCode(raw: String): Boolean = BATCH_CODE_REGEX.matches(normalizeBatchCode(raw))

    fun planBatch(batchId: String, serial: Int, ecc: EccLevel): PayloadPlan {
        val code = normalizeBatchCode(batchId)
        val crc = crc7(batchHeaderAndBody(code, serial))

        val error = when {
            !isValidBatchCode(code) -> "批次短码必须是 4 个字母 A–Z"
            serial !in 1..MAX_BATCH_SERIAL -> "编号必须在 1–$MAX_BATCH_SERIAL 之间"
            ecc != EccLevel.BCH_SUPER ->
                "批次帧固定 40 位，必须使用 BCH Super（当前 ${ecc.wireName}）"
            else -> null
        }

        return PayloadPlan(
            valid = error == null,
            frameType = FrameType.BATCH,
            batchId = code,
            serial = serial,
            headerBits = BATCH_HEADER_BITS,
            serialBits = BATCH_SERIAL_BITS,
            crcBits = BATCH_CRC_BITS,
            totalBits = BATCH_TOTAL_BITS,
            capacityBits = ecc.dataBits,
            remainingBits = ecc.dataBits - BATCH_TOTAL_BITS,
            crc7 = crc,
            error = error,
        )
    }

    fun encodeBatch(batchId: String, serial: Int, ecc: EccLevel = EccLevel.BCH_SUPER): EncodedFrame {
        val plan = planBatch(batchId, serial, ecc)
        require(plan.valid) { plan.error ?: "批次 payload 不合法" }
        val code = requireNotNull(plan.batchId)
        val value = requireNotNull(plan.serial)
        val body = batchHeaderAndBody(code, value)
        val bits = body + requireNotNull(plan.crc7).toBitString(BATCH_CRC_BITS)
        check(bits.length == BATCH_TOTAL_BITS)
        return EncodedFrame(bits, plan)
    }

    private fun batchHeaderAndBody(batchId: String, serial: Int): String = buildString(33) {
        append('1')
        for (ch in batchId) append((ch - 'A').toBitString(5))
        append(serial.toBitString(BATCH_SERIAL_BITS))
    }

    /**
     * CRC-7/MMC (SD/eMMC command CRC): polynomial x^7 + x^3 + 1, init 0x00,
     * no reflection, no final XOR. This is the standard 8-bit-register form:
     * the seven-bit register is shifted through an implicit x^7 term (0x89),
     * with seven zero bits appended at the end; the low seven bits are the
     * remainder. The check value for "123456789" is 0x75.
     */
    fun crc7(bits: String): Int {
        var crc = 0
        for (bit in bits + "0000000") {
            require(bit == '0' || bit == '1') { "CRC 输入必须是 0/1" }
            crc = (crc shl 1) or (bit - '0')
            if (crc and 0x80 != 0) crc = crc xor 0x89
            crc = crc and 0xFF
        }
        return crc and 0x7F
    }

    // ------------------------------------------------------------------ decode

    /**
     * Auto-dispatch on bit 0. There is no loose ASCII fallback: the decoder only
     * accepts the two v1.2 frames, and a batch frame with a bad CRC is an error.
     * v1.1 TMK2 frames (magic 101) begin with 1 and therefore fail CRC, which is
     * intentional: old and new payloads are not silently mixed.
     */
    fun decode(bits: String): DecodedFrame {
        val normalized = bits.filter { it == '0' || it == '1' }
        if (normalized.length != bits.length || normalized.isEmpty()) {
            return DecodedFrame(
                valid = false,
                frameType = null,
                error = "payload 含非 0/1 字符或为空",
            )
        }
        return if (normalized[0] == '0') {
            decodeUniversal(normalized)
        } else {
            decodeBatch(normalized)
        }
    }

    private fun decodeUniversal(bits: String): DecodedFrame {
        if (bits.length < UNIVERSAL_HEADER_BITS) {
            return DecodedFrame(false, FrameType.UNIVERSAL, error = "通用帧头不足 6 位")
        }
        val mode = if (bits[1] == '1') TextMode.ALNUM else TextMode.ALPHA
        val length = bits.substring(2, 6).toInt(2)
        if (length !in 1..MAX_TEXT_LENGTH) {
            return DecodedFrame(false, FrameType.UNIVERSAL, error = "通用帧文本长度非法：$length")
        }
        val required = UNIVERSAL_HEADER_BITS + length * mode.bitsPerChar
        if (required > bits.length) {
            return DecodedFrame(false, FrameType.UNIVERSAL, error = "通用帧被截断")
        }

        val text = StringBuilder(length)
        var cursor = UNIVERSAL_HEADER_BITS
        repeat(length) {
            val code = bits.substring(cursor, cursor + mode.bitsPerChar).toInt(2)
            cursor += mode.bitsPerChar
            if (mode == TextMode.ALPHA) {
                if (code !in 0..25) {
                    return DecodedFrame(false, FrameType.UNIVERSAL, error = "通用帧字母编码非法")
                }
                text.append('A' + code)
            } else {
                if (code !in ALNUM_ALPHABET.indices) {
                    return DecodedFrame(false, FrameType.UNIVERSAL, error = "通用帧字符编码非法")
                }
                text.append(ALNUM_ALPHABET[code])
            }
        }
        return DecodedFrame(
            valid = true,
            frameType = FrameType.UNIVERSAL,
            text = text.toString(),
            note = "通用模式：${mode.label}",
        )
    }

    private fun decodeBatch(bits: String): DecodedFrame {
        if (bits.length < BATCH_TOTAL_BITS) {
            return DecodedFrame(
                valid = false,
                frameType = FrameType.BATCH,
                error = "批次帧不足 40 位（实际 ${bits.length} 位）",
            )
        }
        val body = bits.substring(0, 33)
        val actual = bits.substring(33, 40).toInt(2)
        val expected = crc7(body)
        if (actual != expected) {
            return DecodedFrame(
                valid = false,
                frameType = FrameType.BATCH,
                crc7 = actual,
                crcValid = false,
                error = "CRC-7 校验失败（期望 ${expected.toString(2).padStart(7, '0')}，实际 ${actual.toString(2).padStart(7, '0')}）",
            )
        }

        val code = buildString(BATCH_CODE_LENGTH) {
            var cursor = 1
            repeat(BATCH_CODE_LENGTH) {
                val value = bits.substring(cursor, cursor + 5).toInt(2)
                cursor += 5
                if (value !in 0..25) return DecodedFrame(false, FrameType.BATCH, error = "批次短码编码非法")
                append('A' + value)
            }
        }
        val serial = bits.substring(21, 33).toInt(2)
        if (serial !in 1..MAX_BATCH_SERIAL) {
            return DecodedFrame(
                valid = false,
                frameType = FrameType.BATCH,
                batchId = code,
                crc7 = actual,
                crcValid = true,
                error = "批次编号非法：$serial",
            )
        }
        return DecodedFrame(
            valid = true,
            frameType = FrameType.BATCH,
            batchId = code,
            serial = serial,
            crc7 = actual,
            crcValid = true,
        )
    }

    // --------------------------------------------------------------- validation

    /**
     * Final gate before WorkManager starts. Re-parses every payload instead of
     * trusting the UI, so an accidental frame/capacity edit cannot slip through.
     */
    fun validateBatch(tasks: List<GenerateTask>): String? {
        if (tasks.isEmpty()) return "任务列表为空，无法开始生成"
        for (task in tasks) {
            val ecc = EccLevel.fromWireName(task.eccWireName)
                ?: return "未知的 BCH 等级：${task.eccWireName}"
            if (task.strength !in 0.5f..2.0f) {
                return "强度必须在 0.50–2.00 之间：${task.outputFileName}"
            }
            if (task.payloadBits.isEmpty()) {
                return "payload 为空：${task.outputFileName}"
            }
            if (task.payloadBits.length > ecc.dataBits) {
                return "payload 需要 ${task.payloadBits.length} 位，但 ${ecc.wireName} " +
                    "只有 ${ecc.dataBits} 位：${task.outputFileName}"
            }
            val frame = decode(task.payloadBits)
            if (!frame.valid) {
                return "payload 校验失败：${frame.error.orEmpty()}（${task.outputFileName}）"
            }
            when (task.frameType) {
                FrameType.UNIVERSAL -> {
                    if (frame.frameType != FrameType.UNIVERSAL) {
                        return "帧类型与任务不一致：${task.outputFileName}"
                    }
                    if (task.serial != 0 || task.batchId.isNotBlank()) {
                        return "通用任务不应带批次或编号：${task.outputFileName}"
                    }
                }
                FrameType.BATCH -> {
                    if (frame.frameType != FrameType.BATCH) {
                        return "帧类型与任务不一致：${task.outputFileName}"
                    }
                    if (!frame.crcValid) {
                        return "批次 CRC-7 无效：${task.outputFileName}"
                    }
                    if (frame.batchId != task.batchId) {
                        return "批次短码与 payload 不一致：${task.outputFileName}"
                    }
                    if (frame.serial != task.serial) {
                        return "编号与 payload 不一致：${task.outputFileName}"
                    }
                    if (ecc != EccLevel.BCH_SUPER) {
                        return "批次帧必须使用 BCH Super：${task.outputFileName}"
                    }
                }
            }
        }
        return null
    }

    fun maxTextLength(ecc: EccLevel, mode: TextMode): Int {
        for (length in MAX_TEXT_LENGTH downTo 1) {
            if (UNIVERSAL_HEADER_BITS + length * mode.bitsPerChar <= ecc.dataBits) {
                return length
            }
        }
        return 0
    }

    fun binaryGroups(bits: String, groupSize: Int = 4): String =
        bits.chunked(groupSize).joinToString(" ")

    fun capacitySummary(ecc: EccLevel): String =
        "帧上限 ${ecc.dataBits} 位，批次帧固定 40 位（BCH Super）"

    private fun Int.toBitString(width: Int): String =
        toString(2).padStart(width, '0').takeLast(width)
}
