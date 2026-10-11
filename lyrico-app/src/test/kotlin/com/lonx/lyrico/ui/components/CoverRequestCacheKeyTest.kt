package com.lonx.lyrico.ui.components

import com.lonx.audiotag.model.AudioPictureType
import org.junit.Assert.assertNotEquals
import kotlin.test.Test

class CoverRequestCacheKeyTest {

    private fun key(skipEmbeddedPictures: Boolean) = CoverRequest.getCacheKey(
        path = "/music/song.flac",
        timestamp = 1L,
        pictureType = AudioPictureType.Artist,
        fallbackPictureTypes = listOf(AudioPictureType.LeadArtist, AudioPictureType.Band),
        fallbackToAny = false,
        candidates = emptyList(),
        skipEmbeddedPictures = skipEmbeddedPictures
    )

    /**
     * 编辑页只用 folder-only 请求查外置海报文件，而艺术家列表/详情页仍会走「先内嵌再外置」的链路。
     * 同一个艺术家的这两种请求必须落在不同的缓存键上，否则先渲染的那个会把图缓存给另一个，
     * 出现「编辑页显示内嵌图 / 列表页显示文件夹图」互相串。
     */
    @Test
    fun folderOnlyRequestsDoNotShareACacheKeyWithEmbeddedLookups() {
        assertNotEquals(key(skipEmbeddedPictures = true), key(skipEmbeddedPictures = false))
    }
}
