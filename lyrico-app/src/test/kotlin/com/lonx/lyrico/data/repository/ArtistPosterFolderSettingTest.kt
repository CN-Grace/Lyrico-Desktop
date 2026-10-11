package com.lonx.lyrico.data.repository

import kotlin.test.assertEquals
import kotlin.test.Test

class ArtistPosterFolderSettingTest {

    @Test
    fun legacyMultipleFoldersKeepOnlyTheFirstFolder() {
        assertEquals(decodeArtistPosterFolder(
                """["content://posters/first","content://posters/second"]"""
            ), "content://posters/first")
    }

    @Test
    fun selectingAFolderStoresOnlyThatFolder() {
        assertEquals(decodeArtistPosterFolder(encodeArtistPosterFolder("content://posters/new")), "content://posters/new")
    }

    @Test
    fun clearingTheFolderStoresAnEmptySelection() {
        assertEquals(
            null,
            decodeArtistPosterFolder(encodeArtistPosterFolder(null))
        )
    }
}
