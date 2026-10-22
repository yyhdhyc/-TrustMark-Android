package com.trustmarktool.model

import java.util.Locale

/**
 * ============================================================================
 *  TEXT -> BINARY ENCODER (the layer TrustMark's Rust API does not provide)
 * ============================================================================
 *
 * TrustMark 0.4.0's Rust encoder accepts ONLY a bit string. It then:
 *   1. zero-pads the string to the selected schema's data capacity,
 *   2. appends BCH parity,
 *   3. appends four schema bits,
 *   4. feeds exactly 100 floats to encoder_Q.onnx.
 *
 * The four schema bits are automatic. `EccLevel.dataBits` is the usable budget:
 *   BCH_SUPER = 40, BCH_5 = 61, BCH_4 = 68, BCH_3 = 75.
 *
 * We therefore define our own self-describing frame inside that budget so a
 * decoded 61/68/75-bit payload can be converted back to text + serial:
 *
 *   magic         3 bits   always 101
 *   text alphabet 1 bit    0 = A-Z (5 bits/char), 1 = alnum (6 bits/char)
 *   serial width  2 bits    0 = none, 1 = 8-bit, 2 = 16-bit, 3 = 32-bit
 *   text length   4 bits    1..15
 *   text          L * (5 or 6) bits
 *   serial        W bits
 *   ------------------------------------------------------------------
 *   header        10 bits
 *
 * The alphabet is "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ ." (40 symbols).
 * Pure uppercase text automatically uses the 5-bit alphabet, which is why
 * "CONFIDENTIAL" (12 chars) fits in BCH_3 only when numbering is disabled:
 *   10 + 12*5 = 70 <= 75, while 70 + 8 = 78 > 75.
 *
 * The UI must never claim that arbitrary text "just works": [plan] computes the
 * exact budget and [validateBatch] checks the numbering range before a batch
 * starts. The native layer double-checks the capacity again.
 */
object PayloadCodec {

    const val HEADER_BITS = 10
    const val MAX_TEXT_LENGTH = 15
    const val MAGIC_BITS = "101"

    /** 26 letters + 10 digits + '-', '_', '.', ' ' = 40 values, exactly 6 bits. */
    const val ALNUM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ ."

    enum class TextMode(val label: String, val bitsPerChar: Int) {
        ALPHA("纯大写字母", 5),
        ALNUM("字母/数字/符号", 6),
    }

    enum class FrameFormat(val label: String) {
        TMK2("TMK2 帧"),
        RAW_ASCII7("原始 7-bit ASCII"),
        RAW_ASCII8("原始 8-bit ASCII"),
        RAW_BINARY("原始二进制"),
        INVALID("无效 payload"),
    }

    data class PayloadPlan(
        val valid: Boolean,
        val text: String,
        val textMode: TextMode,
        val serial: Long?,
        val serialWidth: SerialWidth,
        val autoNumber: Boolean,
        val headerBits: Int,
        val textBits: Int,
        val serialBits: Int,
        val totalBits: Int,
        val capacityBits: Int,
        val remainingBits: Int,
        val maxAlphaLength: Int,
        val maxAlnumLength: Int,
        val error: String?,
    )

    data class EncodedFrame(
        val bits: String,
        val plan: PayloadPlan,
    )

    data class DecodedFrame(
        val text: String?,
        val serial: Long?,
        val format: FrameFormat,
        val note: String? = null,
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

    fun plan(
        text: String,
        autoNumber: Boolean,
        serialWidth: SerialWidth,
        serial: Long?,
        ecc: EccLevel,
    ): PayloadPlan {
        val prepared = prepareText(text)
        val effectiveWidth = if (autoNumber) serialWidth else SerialWidth.NONE
        val mode = if (prepared.isNotEmpty() && prepared.all { it in 'A'..'Z' }) {
            TextMode.ALPHA
        } else {
            TextMode.ALNUM
        }

        val textChars = prepared.length
        val textBits = textChars * mode.bitsPerChar
        val serialBits = effectiveWidth.bits
        val total = HEADER_BITS + textBits + serialBits
        val remaining = ecc.dataBits - total

        val textError = when {
            prepared.isEmpty() -> "请输入至少 1 个字符（A–Z、0–9、空格、- _ .）"
            textChars > MAX_TEXT_LENGTH -> "文本最多 $MAX_TEXT_LENGTH 个字符，当前 $textChars 个"
            else -> null
        }

        val serialError = when {
            effectiveWidth != SerialWidth.NONE && serial == null ->
                "自动编号已开启，但起始编号为空"
            effectiveWidth != SerialWidth.NONE && serial != null && (serial < 0 || serial > effectiveWidth.maxValue) ->
                "编号 $serial 超出 ${effectiveWidth.label} 范围"
            else -> null
        }

        val capacityError = if (total > ecc.dataBits) {
            "需要 $total 位，但 ${ecc.wireName} 只有 ${ecc.dataBits} 位；请缩短文本、改用更小编号位宽或选择容量更大的纠错等级"
        } else {
            null
        }

        val error = textError ?: serialError ?: capacityError

        return PayloadPlan(
            valid = error == null,
            text = prepared,
            textMode = mode,
            serial = if (effectiveWidth == SerialWidth.NONE) null else serial,
            serialWidth = effectiveWidth,
            autoNumber = autoNumber && effectiveWidth != SerialWidth.NONE,
            headerBits = HEADER_BITS,
            textBits = textBits,
            serialBits = serialBits,
            totalBits = total,
            capacityBits = ecc.dataBits,
            remainingBits = remaining,
            maxAlphaLength = maxTextLength(ecc, effectiveWidth, TextMode.ALPHA),
            maxAlnumLength = maxTextLength(ecc, effectiveWidth, TextMode.ALNUM),
            error = error,
        )
    }

    /**
     * Build the frame bits. The result length is <= [EccLevel.dataBits]; the
     * native TrustMark library adds BCH parity and the schema bits itself.
     */
    fun encode(
        text: String,
        autoNumber: Boolean,
        serialWidth: SerialWidth,
        serial: Long?,
        ecc: EccLevel,
    ): EncodedFrame {
        val plan = plan(text, autoNumber, serialWidth, serial, ecc)
        require(plan.valid) { plan.error ?: "payload 不合法" }

        val width = plan.serialWidth
        val bits = buildString(plan.totalBits) {
            append(MAGIC_BITS)
            append(if (plan.textMode == TextMode.ALNUM) '1' else '0')
            append(width.code.toBitString(2))
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

            if (width != SerialWidth.NONE) {
                val value = requireNotNull(plan.serial) { "缺少编号" }
                require(value in 0..width.maxValue) { "编号越界" }
                append(value.toString(2).padStart(width.bits, '0').takeLast(width.bits))
            }
        }

        check(bits.length == plan.totalBits) {
            "帧长度计算错误：${bits.length} != ${plan.totalBits}"
        }
        check(bits.length <= ecc.dataBits) {
            "帧长度 ${bits.length} 超出 ${ecc.wireName} 的 ${ecc.dataBits} 位"
        }
        return EncodedFrame(bits, plan)
    }

    /**
     * Reverse a decoded TrustMark payload.
     *
     * Payloads that do not begin with our magic bits are not an error: they may
     * be produced by another TrustMark tool. In that case we make a best-effort
     * 7/8-bit ASCII interpretation and otherwise show raw bits only.
     */
    fun decode(bits: String): DecodedFrame {
        val normalized = bits.filter { it == '0' || it == '1' }
        if (normalized.length != bits.length || normalized.isEmpty()) {
            return DecodedFrame(null, null, FrameFormat.INVALID, "payload 含非 0/1 字符或为空")
        }

        if (normalized.length >= HEADER_BITS && normalized.startsWith(MAGIC_BITS)) {
            parseFrame(normalized)?.let { return it }
        }

        decodeLooseText(normalized)?.let { return it }
        return DecodedFrame(
            text = null,
            serial = null,
            format = FrameFormat.RAW_BINARY,
            note = "非 TMK2 帧，未找到可读文本；原始二进制已保留",
        )
    }

    private fun parseFrame(bits: String): DecodedFrame? {
        var cursor = MAGIC_BITS.length
        if (bits.length < HEADER_BITS) return null

        val mode = if (bits[cursor++] == '1') TextMode.ALNUM else TextMode.ALPHA
        val widthCode = bits.substring(cursor, cursor + 2).toInt(2)
        cursor += 2
        val width = SerialWidth.fromCode(widthCode) ?: return null
        val textLength = bits.substring(cursor, cursor + 4).toInt(2)
        cursor += 4

        if (textLength <= 0 || textLength > MAX_TEXT_LENGTH) return null

        val required = HEADER_BITS + textLength * mode.bitsPerChar + width.bits
        if (required > bits.length) return null

        val text = StringBuilder(textLength)
        repeat(textLength) {
            val charBits = bits.substring(cursor, cursor + mode.bitsPerChar)
            cursor += mode.bitsPerChar
            if (mode == TextMode.ALPHA) {
                val code = charBits.toInt(2)
                if (code !in 0..25) return null
                text.append('A' + code)
            } else {
                val code = charBits.toInt(2)
                if (code !in ALNUM_ALPHABET.indices) return null
                text.append(ALNUM_ALPHABET[code])
            }
        }

        val serial = if (width == SerialWidth.NONE) {
            null
        } else {
            val serialBits = bits.substring(cursor, cursor + width.bits)
            java.lang.Long.parseLong(serialBits, 2)
        }

        return DecodedFrame(
            text = text.toString(),
            serial = serial,
            format = FrameFormat.TMK2,
            note = null,
        )
    }

    private fun decodeLooseText(bits: String): DecodedFrame? {
        for (width in intArrayOf(7, 8)) {
            val usable = bits.length - (bits.length % width)
            if (usable < width * 2) continue

            val chars = ArrayList<Char>(usable / width)
            var index = 0
            while (index + width <= bits.length) {
                chars += bits.substring(index, index + width).toInt(2).toChar()
                index += width
            }

            // Upstream text mode pads with zeros; stop at the first NUL.
            val text = chars.takeWhile { it != '\u0000' }.joinToString("")
            if (text.length < 2) continue
            val printable = text.count { it.code in 32..126 }
            val alnum = text.count { it.isLetterOrDigit() }
            if (printable.toDouble() / text.length >= 0.95 && alnum >= 2) {
                return DecodedFrame(
                    text = text.trim(),
                    serial = null,
                    format = if (width == 7) FrameFormat.RAW_ASCII7 else FrameFormat.RAW_ASCII8,
                    note = "非 TMK2 帧，按原始 ASCII 尽力解读",
                )
            }
        }
        return null
    }

    /**
     * Batch numbering validation happens before WorkManager is enqueued.
     * Overflow-safe: no multiplication occurs until the span is proven to fit.
     */
    fun validateBatch(
        start: Long,
        step: Long,
        width: SerialWidth,
        count: Int,
    ): String? {
        if (width == SerialWidth.NONE || count <= 0) return null
        if (start < 0) return "起始编号不能为负数"
        if (step <= 0) return "步长必须大于 0"
        if (start > width.maxValue) {
            return "起始编号 $start 超出 ${width.label}"
        }
        if (count > 1) {
            val steps = (count - 1).toLong()
            if (step > (width.maxValue - start) / steps) {
                return "本批次编号范围超出 ${width.label}；请减小起始编号、步长或数量"
            }
        }
        return null
    }

    fun serialForIndex(start: Long, step: Long, index: Int): Long = start + step * index.toLong()

    /**
     * 生成前的最终闸门：扁平任务列表里每一个 payload 都必须与所选 BCH 容量
     * 和编号位宽一致。任何一项失败都会阻止整批，而不是只跳过该任务。
     *
     * 这里重新解析 payload，而不是相信 UI 传来的长度，避免编号位宽、文本和
     * 容量在校验后被意外改动。
     */
    fun validateBatch(tasks: List<GenerateTask>): String? {
        if (tasks.isEmpty()) return "任务列表为空，无法开始生成"
        for (task in tasks) {
            val ecc = EccLevel.fromWireName(task.eccWireName)
                ?: return "未知的 BCH 等级：${task.eccWireName}"
            if (task.serial <= 0) {
                return "编号必须从 1 开始：${task.sourceDisplayName}"
            }
            if (task.payloadBits.isEmpty()) {
                return "payload 为空：${task.outputFileName}"
            }
            if (task.payloadBits.length > ecc.dataBits) {
                return "payload 需要 ${task.payloadBits.length} 位，但 ${ecc.wireName} " +
                    "只有 ${ecc.dataBits} 位：${task.outputFileName}"
            }
            val frame = decode(task.payloadBits)
            if (frame.format != FrameFormat.TMK2) {
                return "payload 不是 TMK2 帧：${task.outputFileName}"
            }
            if (frame.serial == null || frame.serial != task.serial.toLong()) {
                return "编号与 payload 不一致：${task.outputFileName}"
            }
        }
        return null
    }

    fun maxTextLength(ecc: EccLevel, width: SerialWidth, mode: TextMode): Int {
        for (length in MAX_TEXT_LENGTH downTo 1) {
            if (HEADER_BITS + length * mode.bitsPerChar + width.bits <= ecc.dataBits) {
                return length
            }
        }
        return 0
    }

    fun binaryGroups(bits: String, groupSize: Int = 4): String =
        bits.chunked(groupSize).joinToString(" ")

    fun capacitySummary(ecc: EccLevel): String = when (ecc) {
        EccLevel.BCH_5 -> "61 位 → 约 2.3×10¹⁸ 种标识符"
        EccLevel.BCH_3 -> "75 位 → 约 3.8×10²² 种标识符"
        EccLevel.BCH_4 -> "68 位 → 约 2.95×10²⁰ 种标识符"
        EccLevel.BCH_SUPER -> "40 位 → 约 1.1×10¹² 种标识符"
    }

    private fun Int.toBitString(width: Int): String =
        toString(2).padStart(width, '0').takeLast(width)
}
