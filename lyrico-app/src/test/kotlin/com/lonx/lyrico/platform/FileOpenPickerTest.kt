package com.lonx.lyrico.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extension matching behind the native open dialog.
 *
 * The dialog itself cannot be driven in a test (it is a modal AWT window), so the matching rule was
 * split out of it. What is worth testing is the *narrowing*: the plugin importer must still show only
 * `.zip` files, and the new callers must not accidentally accept a file whose name merely mentions the
 * extension ("track.lrc.bak" is not a lyrics file).
 */
class FileOpenPickerTest {

    @Test
    fun `the plugin importer's default still matches zips`() {
        assertTrue(matchesExtensions("plugin.zip", listOf("zip")))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(matchesExtensions("PLUGIN.ZIP", listOf("zip")))
        assertTrue(matchesExtensions("Track.LRC", listOf("lrc")))
    }

    @Test
    fun `every requested extension is accepted`() {
        val lyrics = listOf("lrc", "ttml")

        assertTrue(matchesExtensions("song.lrc", lyrics))
        assertTrue(matchesExtensions("song.ttml", lyrics))
        assertFalse(matchesExtensions("song.txt", lyrics))
    }

    @Test
    fun `a name that only contains the extension is not a match`() {
        assertFalse(matchesExtensions("zip.txt", listOf("zip")))
        assertFalse(matchesExtensions("song.lrc.bak", listOf("lrc")))
    }
}
