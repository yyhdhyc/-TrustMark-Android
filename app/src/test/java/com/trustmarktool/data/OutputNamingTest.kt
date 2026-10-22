package com.trustmarktool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputNamingTest {

    @Test
    fun keepsChineseSourceNameInOutput() {
        assertEquals(
            "会议 合影_tm_bB7K2_n3",
            OutputNaming.buildStem("会议 合影.jpg", "B7K2", 3),
        )
    }

    @Test
    fun replacesCharactersThatBreakMediaStoreNames() {
        assertEquals(
            "a_b_c_d",
            OutputNaming.sanitizeBase("a/b:c*d?.jpg"),
        )
    }

    @Test
    fun parsesBatchAndSerialFromCollisionSuffixedName() {
        val parsed = OutputNaming.parse("会议 合影_tm_bB7K2_n3_2.jpg")

        assertEquals("B7K2", parsed?.batchId)
        assertEquals(3, parsed?.serial)
    }

    @Test
    fun lowerCaseBatchIsUppercasedAndZeroSerialRejected() {
        assertEquals("B7K2", OutputNaming.parse("photo_tm_bb7k2_n12.png")?.batchId)
        assertNull(OutputNaming.parse("photo_tm_bB7K2_n0.jpg"))
        assertNull(OutputNaming.parse("photo.jpg"))
    }
}
