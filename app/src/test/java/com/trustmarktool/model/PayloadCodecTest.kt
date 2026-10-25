package com.trustmarktool.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCodecTest {

    @Test
    fun universalAlphaRoundTripHasNoSerial() {
        val frame = PayloadCodec.encodeUniversal("ACME", EccLevel.BCH_5)
        assertEquals(26, frame.bits.length)
        assertEquals(PayloadCodec.TextMode.ALPHA, frame.plan.textMode)

        val decoded = PayloadCodec.decode(frame.bits)
        assertTrue(decoded.valid)
        assertEquals(PayloadCodec.FrameType.UNIVERSAL, decoded.frameType)
        assertEquals("ACME", decoded.text)
        assertNull(decoded.serial)
        assertNull(decoded.batchId)
    }

    @Test
    fun universalAlnumUsesSixBitsPerCharacter() {
        val frame = PayloadCodec.encodeUniversal("A1-B", EccLevel.BCH_5)
        assertEquals(PayloadCodec.TextMode.ALNUM, frame.plan.textMode)
        assertEquals(30, frame.bits.length)
        assertEquals("A1-B", PayloadCodec.decode(frame.bits).text)
    }

    @Test
    fun universalCapacityIsCheckedAtPlanTime() {
        // 6 + 12 * 5 = 66 bits: fits BCH_4 (68), not BCH_5 (61).
        assertTrue(PayloadCodec.planUniversal("CONFIDENTIAL", EccLevel.BCH_5).error != null)
        val frame = PayloadCodec.encodeUniversal("CONFIDENTIAL", EccLevel.BCH_4)
        assertEquals("CONFIDENTIAL", PayloadCodec.decode(frame.bits).text)
    }

    @Test
    fun universalRejectsEmptyAndOverlongText() {
        assertFalse(PayloadCodec.planUniversal("", EccLevel.BCH_5).valid)
        assertFalse(PayloadCodec.planUniversal("A".repeat(16), EccLevel.BCH_3).valid)
        assertEquals(11, PayloadCodec.maxTextLength(EccLevel.BCH_5, PayloadCodec.TextMode.ALPHA))
        assertEquals(9, PayloadCodec.maxTextLength(EccLevel.BCH_5, PayloadCodec.TextMode.ALNUM))
    }

    @Test
    fun sanitizeInputUppercasesAndFiltersUnsupportedCharacters() {
        assertEquals("AB-1_2.3 4", PayloadCodec.sanitizeInput("ab-1_2.3  $%4"))
        assertEquals("A B", PayloadCodec.prepareText("  a   b  "))
    }

    @Test
    fun crc7MatchesTheMmcCheckValue() {
        // CRC-7/MMC check value for the ASCII string "123456789" is 0x75.
        val bits = "123456789".map { it.code.toString(2).padStart(8, '0') }.joinToString("")
        assertEquals(0x75, PayloadCodec.crc7(bits))

        // Lock the exact 33-bit batch body vector: ABCD + serial 42 -> 0x46.
        val abcd42 = "1" + "ABCD".map { (it - 'A').toString(2).padStart(5, '0') }.joinToString("") +
            "42".toInt().toString(2).padStart(12, '0')
        assertEquals(0x46, PayloadCodec.crc7(abcd42))
    }

    @Test
    fun batchRoundTripCarriesFourLetterCodeAndTwelveBitSerial() {
        val frame = PayloadCodec.encodeBatch("ABCD", 4095)
        assertEquals(PayloadCodec.BATCH_TOTAL_BITS, frame.bits.length)

        val decoded = PayloadCodec.decode(frame.bits)
        assertTrue(decoded.valid)
        assertEquals(PayloadCodec.FrameType.BATCH, decoded.frameType)
        assertEquals("ABCD", decoded.batchId)
        assertEquals(4095, decoded.serial)
        assertTrue(decoded.crcValid)
    }

    @Test
    fun batchCrcDetectsCorruption() {
        val frame = PayloadCodec.encodeBatch("WXYZ", 7)
        val corrupted = frame.bits.dropLast(1) + if (frame.bits.last() == '0') '1' else '0'
        val decoded = PayloadCodec.decode(corrupted)
        assertFalse(decoded.valid)
        assertEquals(PayloadCodec.FrameType.BATCH, decoded.frameType)
        assertFalse(decoded.crcValid)
        assertNotNull(decoded.error)
    }

    @Test
    fun batchRequiresValidCodeSerialAndBchSuper() {
        assertFalse(PayloadCodec.planBatch("ABC", 1, EccLevel.BCH_SUPER).valid)
        assertFalse(PayloadCodec.planBatch("ABCD", 0, EccLevel.BCH_SUPER).valid)
        assertFalse(PayloadCodec.planBatch("ABCD", 4096, EccLevel.BCH_SUPER).valid)
        assertFalse(PayloadCodec.planBatch("ABCD", 1, EccLevel.BCH_5).valid)
    }

    @Test
    fun oldTmk2FrameIsNotSilentlyAccepted() {
        val old = "101" + "0".repeat(97)
        val decoded = PayloadCodec.decode(old)
        assertFalse(decoded.valid)
        assertEquals(PayloadCodec.FrameType.BATCH, decoded.frameType)
    }

    @Test
    fun malformedAndTruncatedFramesAreRejected() {
        assertFalse(PayloadCodec.decode("0102").valid)
        assertFalse(PayloadCodec.decode("").valid)
        assertFalse(PayloadCodec.decode("000").valid)
        assertFalse(PayloadCodec.decode("1" + "0".repeat(20)).valid)
    }
}
