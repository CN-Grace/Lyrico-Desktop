package com.lonx.lyrico.utils.lyrics

import com.lonx.lyrico.data.model.lyrics.LyricsColumnMapping
import org.junit.Assert.*
import org.junit.Test

class LyricsColumnSorterTest {
    @Test fun movingToOccupiedPositionInsertsAndShifts() {
        assertEquals(listOf(1, 0, 2), LyricsColumnMapping.identity(3).place(1, 0).order)
    }
    @Test fun removalCompactsAndCanBeRestored() {
        val removed = LyricsColumnMapping.identity(3).place(1, null)
        assertEquals(listOf(0, 2), removed.order)
        assertEquals(listOf(0, 1, 2), removed.place(1, 1).order)
    }
    @Test fun lastRemainingColumnCannotBeRemoved() {
        val mapping = LyricsColumnMapping(3, listOf(1))
        assertEquals(mapping, mapping.place(1, null))
    }
    @Test fun duplicateAndEmptyMappingsAreInvalid() {
        assertFalse(LyricsColumnMapping(2, listOf(0, 0)).isValid)
        assertFalse(LyricsColumnMapping(2, emptyList()).isValid)
        assertFalse(LyricsColumnMapping(2, listOf(2)).isValid)
    }
    @Test fun originalTranslationAndRemovalAreChosenByPosition() {
        val raw = "[00:01.00]翻译\n[00:01.00]<00:01.00>Original<00:02.00>\n[00:01.00]音译"
        assertEquals("[00:01.00]<00:01.00>Original<00:02.00>\n[00:01.00]翻译\n",
            LyricsColumnSorter.apply(raw, LyricsColumnMapping(3, listOf(1, 0))))
    }
    @Test fun separateSongsWithEqualCountsCanUseDifferentMappings() {
        val first = "[00:01]Translation\n[00:01]Original"
        val second = "[00:01]Original\n[00:01]Translation"
        assertEquals("[00:01]Original\n[00:01]Translation",
            LyricsColumnSorter.apply(first, LyricsColumnMapping(2, listOf(1, 0))))
        assertEquals(second, LyricsColumnSorter.apply(second, LyricsColumnMapping.identity(2)))
    }
    @Test fun missingColumnsRemainByteForByteUnchanged() {
        val raw = "[00:01.00]T\r\n[00:01.00]O\r\n[00:01.00]R\r\n[00:02.00]O2\r\n[00:02.00]T2\r\n"
        val analysis = LyricsColumnSorter.analyze(raw)
        assertEquals(3, analysis.sourceCount)
        assertEquals(1, analysis.incomplete.size)
        assertEquals("[00:01.00]O\r\n[00:01.00]T\r\n[00:02.00]O2\r\n[00:02.00]T2\r\n",
            LyricsColumnSorter.apply(raw, LyricsColumnMapping(3, listOf(1, 0))))
    }
    @Test fun metadataAndUntimedTextRemainInPlace() {
        val raw = "[ar:Artist]\n[00:01]T\ncomment\n[00:01]O\n"
        assertEquals("[ar:Artist]\n[00:01]O\ncomment\n[00:01]T\n",
            LyricsColumnSorter.apply(raw, LyricsColumnMapping(2, listOf(1, 0))))
    }
    @Test fun groupingDoesNotUseWordTimingAsRoleSignal() {
        val raw = "[00:01.0]<00:01.0>T<00:02.0>\n[00:01.000]O"
        val analysis = LyricsColumnSorter.analyze(raw)
        assertEquals(2, analysis.sourceCount)
        assertEquals(listOf("T", "O"), analysis.samples.single().text)
    }
    @Test fun nonAdjacentGroupsPreserveAllRawWordTimestamps() {
        val raw = "[00:01.00]T1\n[00:02.00]T2\n[00:01.00]<00:01.00>O1\n[00:02.00]<00:02.00>O2"
        assertEquals("[00:01.00]<00:01.00>O1\n[00:02.00]<00:02.00>O2\n[00:01.00]T1\n[00:02.00]T2",
            LyricsColumnSorter.apply(raw, LyricsColumnMapping(2, listOf(1, 0))))
    }
    @Test fun ttmlCannotBeTreatedAsLrc() {
        assertEquals(0, LyricsColumnSorter.analyze("<?xml version=\"1.0\"?><tt><body>[00:01.00]Test</body></tt>").sourceCount)
    }
    @Test fun plainTextHasNoColumns() {
        assertEquals(0, LyricsColumnSorter.analyze("Lyrics\nTranslation").sourceCount)
    }
    @Test fun identityPreservesBomWhitespaceAndLineEndings() {
        val raw = "\uFEFF[ar:A]\r\n[00:01] O  \r\n[00:01]T"
        assertEquals(raw, LyricsColumnSorter.apply(raw, LyricsColumnMapping.identity(2)))
    }
    @Test(expected = IllegalArgumentException::class) fun mismatchingCountsCannotBeApplied() {
        LyricsColumnSorter.apply("[00:01]O\n[00:01]T", LyricsColumnMapping(3, listOf(1, 0)))
    }
    @Test fun previewFingerprintChangesWithAnyRawEdit() {
        assertNotEquals(LyricsColumnSorter.fingerprint("[00:01]A"), LyricsColumnSorter.fingerprint("[00:01]B"))
        assertEquals(64, LyricsColumnSorter.fingerprint("[00:01]A").length)
    }
    @Test fun bomRemainsAtFileStartAfterFirstColumnRemoved() {
        assertEquals("\uFEFF[00:01]O\n", LyricsColumnSorter.apply("\uFEFF[00:01]T\n[00:01]O",
            LyricsColumnMapping(2, listOf(1))))
    }
    @Test fun tagCleanupDoesNotAlsoRemoveBlankLines() {
        assertEquals("\r\n[00:01.00]Text\r\n\r\n", LyricsTextCleanup.process(
            "\r\n[by:Person]\r\n[00:01.00]Text\r\n\r\n", false, listOf("[by:")))
    }
    @Test fun emptyCleanupDoesNotRemoveCredits() {
        assertEquals("[by:Person]\r\n[00:01.00]Text", LyricsTextCleanup.process(
            "\r\n[by:Person]\r\n[00:00.00]\r\n[00:01.00]Text", true, emptyList()))
    }
}
