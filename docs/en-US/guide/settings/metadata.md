# Metadata Processing

Controls text processing and display-related settings for metadata.

## Chinese Text Conversion

"Chinese Text Conversion" applies to searched text metadata and lyrics. IDs, dates, numbers, URLs, and covers are not affected.

Options:
- None
- Simplified → Traditional
- Traditional → Simplified

## Remove Empty Lines

When enabled, empty lines are automatically removed from lyrics and other multi-line text fields on save.

## Non-Lyric Content Filtering

Used by **Organize lyrics** → **Remove non-lyric content** to remove composer, lyricist, source, and similar lines.

Filtering rules are managed at `Settings` → `Metadata Processing` → `Non-Lyric Content Filtering Rules`. Matching is case-insensitive. A line is removed if it contains any configured keyword; word-timed lyrics are matched as complete lines.

Common filter examples: `Composer:`, `Lyricist:`, `Arranger:`, `Source: QQ Music`, etc.

## Artist Splitting

**Artist Split Rules** affect how the Artists view groups songs. When enabled, Lyrico lists songs with multiple artists under each artist separately.

In the split settings you can configure:
- Additional separators (e.g., `feat.`, `&`, `,`).
- **No-Split Whitelist**: Artist names that should never be split (e.g., names that naturally contain separator characters).

After modifying separators or the whitelist, you usually need to tap **Rebuild Artist Index** for changes to fully take effect.

## Edit Fields

Open **Settings → Metadata Processing → Edit Fields** to manage built-in fields and custom tags in one list.

- Long-press and drag a field to reorder it. Use its switch to control visibility.
- For ReplayGain, choose **Manage Fields** from its menu to reorder or hide individual fields. Group and member switches are saved separately.
- Choose **View Songs** to see songs where the field is filled or empty.
- Use the reset button in the top bar to restore defaults. This does not modify song data.

The configuration applies to single-song editing, batch editing, and batch matching. Hiding a field does not clear its existing tag data.

### Custom Tags

Tap **+** in the top bar to choose a tag found in the library or enter a key such as `SOURCE`. Keys are trimmed and converted to uppercase; duplicates, line breaks, and keys longer than 64 characters are not allowed.

Delete a custom tag through its menu to remove it from the configuration. Its data remains in the audio files.

Cover queries read the audio files. Files that cannot be read are not counted as empty. Each query tab shows up to 500 songs.

## Character Mapping

Used during batch rename to replace specific characters in filenames.

In the character mapping page, tap a character to configure its replacement rule. Each character can map to a different replacement value. Common usage: replacing `/` with `-`, or `:` with `_` for characters unsuitable in filenames.
