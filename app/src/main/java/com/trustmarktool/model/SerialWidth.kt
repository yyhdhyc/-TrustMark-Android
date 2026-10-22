package com.trustmarktool.model

/**
 * Auto-number width. The width is encoded in the custom payload frame, so the
 * decoder does not need to guess it.
 *
 * Validation rule: `start + step * (count - 1)` must stay inside
 * `0 .. (2^width - 1)`. A 32-bit value is stored in a Kotlin Long because
 * Kotlin has no unsigned integer type.
 */
enum class SerialWidth(
    val bits: Int,
    val code: Int,
    val label: String,
) {
    NONE(0, 0, "不编号"),
    BITS_8(8, 1, "8 位（0–255）"),
    BITS_16(16, 2, "16 位（0–65,535）"),
    BITS_32(32, 3, "32 位（0–4,294,967,295）"),
    ;

    val maxValue: Long
        get() = when (this) {
            NONE -> 0L
            BITS_8 -> 0xFFL
            BITS_16 -> 0xFFFFL
            BITS_32 -> 0xFFFF_FFFFL
        }

    companion object {
        fun fromCode(code: Int): SerialWidth? = entries.firstOrNull { it.code == code }

        fun numberedOptions(): List<SerialWidth> = listOf(BITS_8, BITS_16, BITS_32)
    }
}
