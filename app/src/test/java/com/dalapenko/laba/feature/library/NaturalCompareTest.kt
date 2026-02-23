package com.dalapenko.laba.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalCompareTest {

    // ── Basic numeric ordering ────────────────────────────────────────────────

    @Test
    fun givenNumericSuffix_whenSecondIsLarger_thenFirstComesFirst() {
        assertTrue(naturalCompare("track2", "track10") < 0)
    }

    @Test
    fun givenNumericSuffix_whenFirstIsLarger_thenSecondComesFirst() {
        assertTrue(naturalCompare("track10", "track2") > 0)
    }

    @Test
    fun givenEqualStrings_whenCompared_thenReturnsZero() {
        assertEquals(0, naturalCompare("abc", "abc"))
    }

    // ── Case insensitivity ────────────────────────────────────────────────────

    @Test
    fun givenDifferentCase_whenCompared_thenTreatedAsEqual() {
        assertEquals(0, naturalCompare("Track01", "track01"))
    }

    @Test
    fun givenDifferentCase_whenCompared_thenOrderIsCorrect() {
        assertTrue(naturalCompare("Track1", "track2") < 0)
    }

    // ── Leading zeros ─────────────────────────────────────────────────────────

    @Test
    fun givenLeadingZeros_whenCompared_thenNumericallyEqual() {
        assertEquals(0, naturalCompare("track002", "track2"))
    }

    @Test
    fun givenLeadingZeros_whenDifferentValues_thenOrderIsNumeric() {
        assertTrue(naturalCompare("track02", "track10") < 0)
    }

    // ── Pure text ─────────────────────────────────────────────────────────────

    @Test
    fun givenPureTextStrings_whenCompared_thenLexicographicOrder() {
        assertTrue(naturalCompare("alpha", "beta") < 0)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun givenEmptyStrings_whenCompared_thenReturnsZero() {
        assertEquals(0, naturalCompare("", ""))
    }

    @Test
    fun givenOneEmptyString_whenCompared_thenEmptyComesFirst() {
        assertTrue(naturalCompare("", "a") < 0)
        assertTrue(naturalCompare("a", "") > 0)
    }

    // ── Realistic file name scenarios ─────────────────────────────────────────

    @Test
    fun givenRealisticAudioFiles_whenSorted_thenNaturalOrder() {
        val files = listOf(
            "Chapter 10.mp3",
            "Chapter 1.mp3",
            "Chapter 2.mp3",
            "Chapter 20.mp3",
        )
        val sorted = files.sortedWith(Comparator { a, b -> naturalCompare(a, b) })
        assertEquals(
            listOf("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3", "Chapter 20.mp3"),
            sorted
        )
    }

    @Test
    fun givenPaddedNumbers_whenSorted_thenNaturalOrder() {
        val files = listOf("03 - Track.mp3", "1 - Track.mp3", "2 - Track.mp3", "10 - Track.mp3")
        val sorted = files.sortedWith(Comparator { a, b -> naturalCompare(a, b) })
        assertEquals(
            listOf("1 - Track.mp3", "2 - Track.mp3", "03 - Track.mp3", "10 - Track.mp3"),
            sorted
        )
    }

    @Test
    fun givenStringWithNoNumericPart_whenComparedToStringWithNumber_thenTextualOrderApplied() {
        // "abc" vs "abc1" — same text prefix, "abc" has fewer chunks so comes first
        assertTrue(naturalCompare("abc", "abc1") < 0)
    }

    @Test
    fun givenIdenticalNumbersDifferentPrefix_whenCompared_thenPrefixDeterminesOrder() {
        assertTrue(naturalCompare("a1", "b1") < 0)
    }
}
