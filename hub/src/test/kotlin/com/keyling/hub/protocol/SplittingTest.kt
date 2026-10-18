package com.keyling.hub.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplittingTest {
    private fun utf8Size(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun emptyTextHasNoPieces() {
        assertEquals(emptyList<String>(), splitText(""))
    }

    @Test
    fun shortTextIsOnePiece() {
        assertEquals(listOf("hello"), splitText("hello"))
    }

    @Test
    fun asciiSplitsAtLimit() {
        val pieces = splitText("a".repeat(2500))
        assertEquals(listOf(1023, 1023, 454), pieces.map { it.length })
    }

    @Test
    fun multiByteCharactersAreNotSplit() {
        val text = "ñ".repeat(10)
        val pieces = splitText(text, maxBytes = 5)
        assertEquals(listOf(4, 4, 4, 4, 4), pieces.map(::utf8Size))
        assertEquals(text, pieces.joinToString(""))
    }

    @Test
    fun surrogatePairsStayTogether() {
        val text = "a🔑🔑b"
        val pieces = splitText(text, maxBytes = 5)
        assertEquals(listOf("a🔑", "🔑b"), pieces)
        assertTrue(pieces.all { utf8Size(it) <= 5 })
    }

    @Test
    fun strokesSplitAtWholeEntries() {
        val strokes = ByteArray(3 * 700) { it.toByte() }
        val pieces = splitStrokes(strokes)
        assertEquals(listOf(1020, 1020, 60), pieces.map { it.size })
        assertArrayEquals(strokes, pieces.reduce(ByteArray::plus))
    }

    @Test
    fun strokesLimitRoundsDownToWholeEntry() {
        val pieces = splitStrokes(ByteArray(12), maxBytes = 7)
        assertEquals(listOf(6, 6), pieces.map { it.size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialStrokeEntry() {
        splitStrokes(ByteArray(4))
    }
}
