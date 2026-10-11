package com.lonx.lyrico.domain.poster

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ArtistPosterFileNamingTest {

    // ------------------------------------------------------------ stemFor

    @Test
    fun aPlainArtistNameBecomesTheFileName() {
        assertEquals(ArtistPosterFileNaming.stemFor("周杰伦"), "周杰伦")
        assertEquals(ArtistPosterFileNaming.stemFor("AC_DC"), "AC_DC")
    }

    @Test
    fun charactersAFileNameCannotHoldBecomeTheirFullWidthForm() {
        // 与「重命名文件」用的是同一套规则，写下去的文件名才认得出是同一位艺术家
        assertEquals(ArtistPosterFileNaming.stemFor("AC/DC"), "AC／DC")
        assertEquals(ArtistPosterFileNaming.stemFor("Re:Zero"), "Re：Zero")
        assertEquals(ArtistPosterFileNaming.stemFor("A|B"), "A｜B")
    }

    @Test
    fun surroundingWhitespaceIsDropped() {
        assertEquals(ArtistPosterFileNaming.stemFor("  Artist  "), "Artist")
    }

    @Test
    fun aNameThatCleansUpToNothingHasNoFileName() {
        assertNull(ArtistPosterFileNaming.stemFor("   "))
        assertNull(ArtistPosterFileNaming.stemFor(""))
    }

    // ------------------------------------------------------------ extensionFor

    @Test
    fun theMimeTypeDecidesTheExtension() {
        assertEquals(ArtistPosterFileNaming.extensionFor("image/jpeg"), "jpg")
        assertEquals(ArtistPosterFileNaming.extensionFor("image/jpg"), "jpg")
        assertEquals(ArtistPosterFileNaming.extensionFor("image/png"), "png")
        assertEquals(ArtistPosterFileNaming.extensionFor("image/webp"), "webp")
        assertEquals(ArtistPosterFileNaming.extensionFor("video/mp4"), "mp4")
    }

    @Test
    fun aMimeTypeWithParametersStillResolves() {
        assertEquals(ArtistPosterFileNaming.extensionFor("image/png;charset=utf-8"), "png")
        assertEquals(ArtistPosterFileNaming.extensionFor("IMAGE/JPEG"), "jpg")
    }

    @Test
    fun anUnknownMimeTypeFallsBackToThePickedFileNameThenToJpeg() {
        assertEquals(ArtistPosterFileNaming.extensionFor(null, "poster.PNG"), "png")
        assertEquals(ArtistPosterFileNaming.extensionFor(null, null), "jpg")
        assertEquals(ArtistPosterFileNaming.extensionFor("image/*", null), "jpg")
        assertEquals(ArtistPosterFileNaming.extensionFor("application/octet-stream", "noext"), "jpg")
    }

    // ------------------------------------------------------------ matchesArtist

    @Test
    fun onlyFilesOfThatArtistAreMatched() {
        val files = listOf("A.jpg", "B.jpg", "AC_DC.png", "Artist.gif", "A_cover.jpg")

        assertEquals(listOf("A.jpg", "A_cover.jpg"), ArtistPosterFileNaming.matchesArtist(files, "A"))
    }

    @Test
    fun theClosestMatchComesFirst() {
        val files = listOf("A_live.jpg", "A.JPG", "A_alt.png")

        // 0 级（名字完全一致）在前，然后是后缀变体；读取海报时也是这个顺序
        assertEquals(listOf("A.JPG", "A_alt.png", "A_live.jpg"), ArtistPosterFileNaming.matchesArtist(files, "A"))
    }

    @Test
    fun anArtistWithNoPosterMatchesNothing() {
        assertEquals(emptyList<String>(), ArtistPosterFileNaming.matchesArtist(listOf("B.jpg"), "A"))
        assertEquals(emptyList<String>(), ArtistPosterFileNaming.matchesArtist(emptyList(), "A"))
    }

    @Test
    fun theFullWidthFormOfAnInvalidCharacterMatchesTheArtistName() {
        val files = listOf("AC／DC.jpg")

        assertEquals(listOf("AC／DC.jpg"), ArtistPosterFileNaming.matchesArtist(files, "AC/DC"))
    }
}
