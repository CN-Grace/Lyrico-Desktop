package com.lonx.lyrico.screens.library

import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.song.tag.FieldMutation
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Places a TagLib fixture in the library and gives it the tags the test is about.
 *
 * The fixtures are the app's own audio samples, and their tags are TagLib's test data — `bladeenc.mp3`
 * ships with no artist and no album at all, and several of the others share one album tag. That is fine
 * for testing that tags can be read, and useless for testing what a page renders, because a song with
 * no album never becomes an album row: it would silently turn "one card per album" into "no cards at
 * all" and leave the assertion to fail for a reason that has nothing to do with the page.
 *
 * So the tags are written, through the same `AudioTagRepository` the metadata editor uses. Three things
 * fall out of that: the album and artist names are whatever the test says they are, the scan under test
 * reads tags that were really written to a real file, and the write path gets exercised on the way in.
 */
internal fun placeTaggedFixture(
    musicDir: Path,
    fixture: String,
    relativeDir: String,
    title: String,
    artist: String,
    album: String,
    tags: AudioTagRepository,
    fileName: String = fixture,
): Path {
    val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), fixture)
    assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
    val targetDir = musicDir.resolve(relativeDir)
    Files.createDirectories(targetDir)
    val target = targetDir.resolve(fileName)
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

    val result = runBlocking {
        tags.overwrite(
            target.toString(),
            AudioTagMutation(
                mode = AudioTagMutationMode.Overwrite,
                fields = mapOf(
                    AudioTagFieldKey.Title to FieldMutation.Set(title),
                    AudioTagFieldKey.Artist to FieldMutation.Set(artist),
                    AudioTagFieldKey.Album to FieldMutation.Set(album),
                    AudioTagFieldKey.AlbumArtist to FieldMutation.Set(artist),
                ),
            ),
        )
    }
    assertIs<AudioTagWriteResult.Success>(
        result,
        "the fixture has to be taggable, otherwise the page under test has nothing to show",
    )
    return target
}
