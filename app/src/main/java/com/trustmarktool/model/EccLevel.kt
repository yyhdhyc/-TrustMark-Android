package com.trustmarktool.model

/**
 * TrustMark 0.4.0 BCH schemas.
 *
 * The Rust implementation always receives a *binary* payload. These numbers are
 * the raw data capacity *before* BCH parity and the 4 schema bits are appended
 * by the native encoder. They are NOT 100.
 */
enum class EccLevel(
    val label: String,
    val wireName: String,
    val dataBits: Int,
    val allowedBitFlips: Int,
) {
    BCH_5("BCH 5（推荐）", "BCH_5", 61, 5),
    BCH_3("BCH 3（容量最大）", "BCH_3", 75, 3),
    BCH_4("BCH 4（均衡）", "BCH_4", 68, 4),
    BCH_SUPER("BCH Super（最稳健）", "BCH_SUPER", 40, 8),
    ;

    /** Two-line menu title: the short technical name, not the marketing label. */
    val menuTitle: String
        get() = when (this) {
            BCH_3 -> "BCH 3"
            BCH_4 -> "BCH 4"
            BCH_5 -> "BCH 5"
            BCH_SUPER -> "BCH Super"
        }

    val description: String
        get() = "$dataBits 位数据 · 最多纠正 $allowedBitFlips 位"

    companion object {
        /** Low correction capability to high: 3, 4, 5, Super. */
        val orderedForMenu: List<EccLevel> = listOf(BCH_3, BCH_4, BCH_5, BCH_SUPER)

        fun fromWireName(value: String?): EccLevel? =
            entries.firstOrNull { it.wireName == value || it.name == value }
    }
}
