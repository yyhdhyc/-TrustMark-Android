package com.trustmarktool.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EccLevelTest {

    @Test
    fun menuIsOrderedFromLowestToHighestCorrection() {
        assertEquals(
            listOf(EccLevel.BCH_3, EccLevel.BCH_4, EccLevel.BCH_5, EccLevel.BCH_SUPER),
            EccLevel.orderedForMenu,
        )
    }

    @Test
    fun menuUsesTwoLineNamesAndDescriptions() {
        assertEquals(
            listOf("BCH 3", "BCH 4", "BCH 5", "BCH Super"),
            EccLevel.orderedForMenu.map { it.menuTitle },
        )
        assertEquals("75 位数据 · 最多纠正 3 位", EccLevel.BCH_3.description)
        assertEquals("68 位数据 · 最多纠正 4 位", EccLevel.BCH_4.description)
        assertEquals("61 位数据 · 最多纠正 5 位", EccLevel.BCH_5.description)
        assertEquals("40 位数据 · 最多纠正 8 位", EccLevel.BCH_SUPER.description)
    }
}
