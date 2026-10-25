package com.trustmarktool.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNamingTest {

    @Test
    fun keepsChineseSourceNameAndAddsMarker() {
        assertEquals("会议 合影_tm", OutputNaming.buildStem("会议 合影.jpg"))
        assertEquals("会议 合影_tm.jpg", OutputNaming.buildFileName("会议 合影.jpg", "jpg"))
    }

    @Test
    fun replacesCharactersThatBreakMediaStoreNames() {
        assertEquals("a_b_c_d", OutputNaming.sanitizeBase("a/b:c*d?.jpg"))
    }

    @Test
    fun collisionSuffixIsAddedOutsideThisHelper() {
        // MediaStore/legacy storage appends _2 itself; the helper must not
        // create it and must not embed batch/serial in the filename.
        assertEquals("photo_tm", OutputNaming.buildStem("photo.png"))
        assertEquals("photo_tm", OutputNaming.buildStem("photo"))
    }

    @Test
    fun extensionFollowsMimeTypeThenFileName() {
        assertEquals("png", OutputNaming.extensionFor("image/png", "x.jpg"))
        assertEquals("png", OutputNaming.extensionFor(null, "x.PNG"))
        assertEquals("jpg", OutputNaming.extensionFor("image/jpeg", "x.jpg"))
        assertEquals("jpg", OutputNaming.extensionFor(null, "x"))
    }
}
