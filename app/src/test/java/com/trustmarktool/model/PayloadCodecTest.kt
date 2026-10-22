package com.trustmarktool.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCodecTest {

    @Test
    fun bch5SevenUppercasePlus16BitSerialFitsExactly() {
        val frame = PayloadCodec.encode(
            text = "ABCDEFG",
            autoNumber = true,
            serialWidth = SerialWidth.BITS_16,
            serial = 42L,
            ecc = EccLevel.BCH_5,
        )

        assertEquals(61, frame.plan.totalBits)
        assertEquals(0, frame.plan.remainingBits)
        assertEquals(61, frame.bits.length)

        val decoded = PayloadCodec.decode(frame.bits)
        assertEquals(PayloadCodec.FrameFormat.TMK2, decoded.format)
        assertEquals("ABCDEFG", decoded.text)
        assertEquals(42L, decoded.serial)
    }

    @Test
    fun bch5RejectsEightUppercaseWith16BitSerial() {
        val plan = PayloadCodec.plan(
            text = "ABCDEFGH",
            autoNumber = true,
            serialWidth = SerialWidth.BITS_16,
            serial = 1L,
            ecc = EccLevel.BCH_5,
        )

        assertTrue(!plan.valid)
        assertEquals(66, plan.totalBits)
        assertEquals(-5, plan.remainingBits)
        assertNotNull(plan.error)
    }

    @Test
    fun bch3AllowsTenUppercaseWith8BitSerial() {
        val frame = PayloadCodec.encode(
            text = "ABCDEFGHIJ",
            autoNumber = true,
            serialWidth = SerialWidth.BITS_8,
            serial = 7L,
            ecc = EccLevel.BCH_3,
        )

        assertEquals(68, frame.bits.length)
        assertEquals(7, frame.plan.remainingBits)
        val decoded = PayloadCodec.decode(frame.bits)
        assertEquals("ABCDEFGHIJ", decoded.text)
        assertEquals(7L, decoded.serial)
    }

    @Test
    fun bch3AllowsTwelveUppercaseWithoutSerial() {
        val plan = PayloadCodec.plan(
            text = "CONFIDENTIAL",
            autoNumber = false,
            serialWidth = SerialWidth.NONE,
            serial = null,
            ecc = EccLevel.BCH_3,
        )

        assertTrue(plan.valid)
        assertEquals(70, plan.totalBits)
        assertEquals(5, plan.remainingBits)
    }

    @Test
    fun bch5RejectsConfidentialEvenWithoutSerial() {
        val plan = PayloadCodec.plan(
            text = "CONFIDENTIAL",
            autoNumber = false,
            serialWidth = SerialWidth.NONE,
            serial = null,
            ecc = EccLevel.BCH_5,
        )

        assertTrue(!plan.valid)
        assertEquals(70, plan.totalBits)
    }

    @Test
    fun alphanumericUsesSixBitsPerCharacter() {
        val frame = PayloadCodec.encode(
            text = "A1-B",
            autoNumber = false,
            serialWidth = SerialWidth.NONE,
            serial = null,
            ecc = EccLevel.BCH_5,
        )

        assertEquals(PayloadCodec.TextMode.ALNUM, frame.plan.textMode)
        assertEquals(24, frame.plan.textBits)
        assertEquals("A1-B", PayloadCodec.decode(frame.bits).text)
    }

    @Test
    fun serialBatchRangeIsOverflowSafe() {
        assertNull(PayloadCodec.validateBatch(0, 1, SerialWidth.BITS_8, 256))
        assertNotNull(PayloadCodec.validateBatch(250, 10, SerialWidth.BITS_8, 2))
        assertNotNull(PayloadCodec.validateBatch(0, 1, SerialWidth.BITS_8, 257))
        assertNull(
            PayloadCodec.validateBatch(
                start = 4_294_967_295L,
                step = 1,
                width = SerialWidth.BITS_32,
                count = 1,
            ),
        )
    }

    @Test
    fun autoNumberRequiresStartValue() {
        val plan = PayloadCodec.plan(
            text = "ACME",
            autoNumber = true,
            serialWidth = SerialWidth.BITS_16,
            serial = null,
            ecc = EccLevel.BCH_5,
        )

        assertTrue(!plan.valid)
        assertNotNull(plan.error)
    }

    @Test
    fun rawSevenBitAsciiIsRecovered() {
        val bits = "HELLO".map { it.code.toString(2).padStart(7, '0') }.joinToString("") + "000"
        val decoded = PayloadCodec.decode(bits)

        assertEquals(PayloadCodec.FrameFormat.RAW_ASCII7, decoded.format)
        assertEquals("HELLO", decoded.text)
    }
}
