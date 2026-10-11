package com.lonx.lyrico.utils.coil

import com.lonx.lyrico.data.model.CharacterMappingDefaults
import kotlin.test.*
import kotlin.test.Test

class ArtistPosterMatcherTest {
    @Test fun supportsAllExtensionsAndCaseInsensitiveNames() {
        for (ext in listOf("jpg", "jpeg", "png", "webp", "mp4")) {
            assertEquals(0, ArtistPosterMatcher.rank("Artist.${ext.uppercase()}", "artist"))
            assertEquals(2, ArtistPosterMatcher.rank("Artist_live.$ext", "artist"))
        }
    }

    @Test fun acceptsReplacementOfEveryInvalidCharacter() {
        // Whatever the app treats as invalid when renaming must stay findable as a poster.
        for ((invalid, replacement) in CharacterMappingDefaults.DEFAULT_INVALID_CHARS.charMappings) {
            assertEquals(1, ArtistPosterMatcher.rank("AC_DC.jpg", "AC${invalid}DC"))
            assertEquals(1, ArtistPosterMatcher.rank("ACDC.jpg", "AC${invalid}DC"))
            if (replacement != null) {
                // The app renames the invalid character to this full-width form.
                assertEquals(0, ArtistPosterMatcher.rank("AC${replacement}DC.jpg", "AC${invalid}DC"))
            }
        }
    }

    @Test fun matchesLiteralArtistNameWithoutConfusingPrefixes() {
        assertEquals(0, ArtistPosterMatcher.rank("周杰伦.jpg", "周杰伦"))
        assertEquals(2, ArtistPosterMatcher.rank("AC_DC_live.png", "AC_DC"))
        assertNull(ArtistPosterMatcher.rank("ArtistExtra.jpg", "Artist"))
        assertNull(ArtistPosterMatcher.rank("Artist.gif", "Artist"))
        assertNull(ArtistPosterMatcher.rank("Artist", "Artist"))
        assertNull(ArtistPosterMatcher.rank("_live.jpg", ""))
        assertNull(ArtistPosterMatcher.rank("Other.jpg", "Artist"))
        assertNull(ArtistPosterMatcher.rank("塞壬唱片MSR.jpg", "塞壬唱片"))
        assertNull(ArtistPosterMatcher.rank("伍佰 & China Blue.jpg", "伍佰"))
    }

    @Test fun acceptsReplacementOfCharactersFileNamesCannotHold() {
        // `\ / : * ? " < > |` cannot appear in a file name, so a separator - or nothing at all -
        // may stand in for them.
        assertEquals(1, ArtistPosterMatcher.rank("AC_DC.jpg", "AC/DC"))
        assertEquals(1, ArtistPosterMatcher.rank("AC-DC.jpg", "AC/DC"))
        assertEquals(1, ArtistPosterMatcher.rank("AC DC.jpg", "AC/DC"))
        assertEquals(1, ArtistPosterMatcher.rank("ACDC.jpg", "AC/DC"))
        assertEquals(1, ArtistPosterMatcher.rank("AC - DC.jpg", "AC/DC"))
        assertEquals(2, ArtistPosterMatcher.rank("AC_DC_live.jpg", "AC/DC"))
        assertEquals(2, ArtistPosterMatcher.rank("ACDC_live.jpg", "AC/DC"))
        assertEquals(1, ArtistPosterMatcher.rank("Re_Zero.jpg", "Re:Zero"))
        assertEquals(1, ArtistPosterMatcher.rank("ReZero.jpg", "Re:Zero"))
    }

    @Test fun acceptsTheFullWidthCharactersTheAppRenamesTo() {
        // CharacterMappingDefaults replaces `/` with `／`, `:` with `：`, and so on.
        assertEquals(0, ArtistPosterMatcher.rank("AC／DC.jpg", "AC/DC"))
        assertEquals(0, ArtistPosterMatcher.rank("AC／DC.jpg", "AC／DC"))
        assertEquals(0, ArtistPosterMatcher.rank("Re：Zero.jpg", "Re:Zero"))
        assertEquals(0, ArtistPosterMatcher.rank("Re？Zero.jpg", "Re?Zero"))
        assertEquals(2, ArtistPosterMatcher.rank("AC／DC_live.jpg", "AC/DC"))
    }

    @Test fun keepsMatchingLegalPunctuationExactly() {
        // Only characters a file name cannot hold are interchangeable; `G.E.M. 邓紫棋` keeps its dots.
        assertEquals(0, ArtistPosterMatcher.rank("G.E.M. 邓紫棋.jpg", "G.E.M. 邓紫棋"))
        assertNull(ArtistPosterMatcher.rank("GEM邓紫棋.jpg", "G.E.M. 邓紫棋"))
        assertNull(ArtistPosterMatcher.rank("MyGO!!!!.jpg", "MyGO!!!!!"))
        // A hyphen is legal in a file name, so it is not interchangeable.
        assertNull(ArtistPosterMatcher.rank("AC_DC.jpg", "AC-DC"))
    }

    @Test fun prefersExactNameOverTolerantMatches() {
        assertEquals(0, ArtistPosterMatcher.rank("AC_DC.jpg", "AC_DC"))
        assertEquals(1, ArtistPosterMatcher.rank("AC_DC.jpg", "AC/DC"))
        assertTrue(
            ArtistPosterMatcher.rank("AC_DC.jpg", "AC_DC")!! <
                ArtistPosterMatcher.rank("AC_DC.jpg", "AC/DC")!!
        )
    }

    @Test fun ignoresSurroundingWhitespaceAndUnicodeComposition() {
        // Tag values and file names are typed independently, so neither stray spaces
        // nor NFC/NFD differences may break the lookup.
        assertEquals(0, ArtistPosterMatcher.rank("  周杰伦 .jpg", "周杰伦"))
        assertEquals(0, ArtistPosterMatcher.rank("Cafe\u0301.jpg", "Caf\u00e9"))
        assertEquals(2, ArtistPosterMatcher.rank("Cafe\u0301_live.jpg", "Caf\u00e9"))
    }

    @Test fun countsFilesMatchingAnyArtistOfTheLibrary() {
        val files = listOf("AC_DC.jpg", "AC-DC_live.png", "Nobody.jpg", "notes.txt")
        val artists = listOf("AC/DC", "周杰伦")
        assertEquals(2, ArtistPosterMatcher.countMatchedFiles(files, artists))
        assertEquals(0, ArtistPosterMatcher.countMatchedFiles(files, emptyList()))
        assertEquals(0, ArtistPosterMatcher.countMatchedFiles(emptyList(), artists))
    }

    @Test fun recognisesPosterFileExtensions() {
        assertTrue(ArtistPosterMatcher.isPosterFile("Artist.JPG"))
        assertTrue(ArtistPosterMatcher.isPosterFile("Artist.mp4"))
        assertFalse(ArtistPosterMatcher.isPosterFile("Artist.gif"))
        assertFalse(ArtistPosterMatcher.isPosterFile("Artist"))
        assertFalse(ArtistPosterMatcher.isPosterFile(null))
    }
}
