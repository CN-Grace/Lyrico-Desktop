# Lyrics Settings

Controls lyric search, format, and write behavior.

## Lyric Mode

Choose the preferred lyric format when searching:

- **Line-Timed Lyrics**: Traditional line-by-line LRC format.
- **Word-Timed Lyrics**: Lyrics with per-word timing.
- **Enhanced Word-Timed Lyrics**: Lyrics with per-word timing and additional metadata.
- **TTML**: TTML format (Timed Text Markup Language).

## Translation and Romanization

- **Romanization**: When enabled, requests romanized lyrics during search (useful for Japanese songs).
- **Translation**: When enabled, requests translated lyrics during search.
- **Download Translation Only**: Requires Translation to be enabled. Uses translated text where available and keeps the original text for lines without a translation.

To reorder existing lyrics, open **Lyrics Options → Organize lyrics → Sort by lyric line** in the editor. See [Organize Lyrics](../single-song.md#organize-lyrics).

## TTML information preservation

TTML output preserves supported performer references, paragraph timing, language tags, head metadata, word-timed romanization and multi-syllable Ruby annotations when present in the source. Ruby timing follows lyric offsets; missing boundaries are filled from neighboring syllables and word timing. Body duration does not change with the offset.

LRC cannot preserve TTML-only structures such as performers, paragraphs and Ruby. Word-timed LRC can retain romanization timestamps. Arbitrary XML structures are not guaranteed to survive parsing and rewriting; API5 plugins can return the supported extended fields.

## Notes

Changing the lyric mode, romanization, or translation settings does **not auto-refresh** existing search results. Re-search to apply the new settings.

These settings also do not affect lyrics already saved in audio files.
