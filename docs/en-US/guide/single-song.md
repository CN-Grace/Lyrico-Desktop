# Single Song Editing

The single-song editor is Lyrico's core screen for viewing and editing all metadata for one track.

## Opening the Editor

There are several ways to open the editor:

- In the Songs tab, artist detail, album detail, or local search results, **tap any song**.
- **Open or share** an audio file from another app (like a file manager) to Lyrico to jump directly to the editor.

::: warning Tapping does not play
Tapping a song opens the metadata editor by default, not playback. To play, choose "Play Music" from the more menu or the editor's floating action menu.
:::

## Editor Layout

The editor contains the following areas. Field order and visibility are configured in Edit Fields:

| Area | Purpose |
|------|---------|
| Top bar | Back and Save |
| Floating menus | Search metadata, lyrics, and covers; play music and access other actions |
| Cover area | View, change, search, or remove cover art |
| Tag fields | Title, artist, album, album artist, year, genre, track number, disc number, composer, lyricist, comment, etc. |
| Lyrics area | View, import, export, convert, and organize lyrics; simplify/traditionalize; adjust timing |
| ReplayGain area | Calculate track gain and peak |
| Custom tags | Manage custom tags (requires adding visible custom tag keys in settings first) |

If a field is missing, check **Settings → Metadata Processing → Edit Fields**. Hiding a field does not clear its data from the file. See [Edit Fields](./settings/metadata.md#edit-fields).

## Edit Tags and Save

**Steps:**

1. Open the editor, modify any fields you want to change. Empty fields can be filled; filled fields can be edited or cleared.
2. Tap the **Save** button in the top toolbar.
3. "Saved successfully" confirms changes were written. If saving fails, the error reason is shown.

**Important:**
- Search results, cover selections, lyrics processing, and ReplayGain calculations only update the editor's temporary content.
- Changes are written to the audio file only when you tap **Save**.
- Some Android versions or specific directories may prompt additional permission requests—follow the on-screen instructions.

## Search Lyrics and Metadata

Use plugins to search online song metadata to fill in title, artist, lyrics, and other fields.

**Steps:**

1. Expand the floating search button in the editor and choose **Main Search**. The search page uses the current title and artist as keywords, or the filename when the title is empty.
2. Adjust the keywords and search. For lyrics or covers only, choose **Search Lyrics** or **Search Cover**. These actions are also available in Lyrics Options and Cover Options. An action appears only when an enabled plugin supports it.
3. Search results are shown under "All" and per-plugin-source tabs.
   - Tap **Load more** at the bottom of the results to load the next page. The “All” tab continues loading every source that still has more results.
4. Tap a search result to view its metadata and lyrics.
   - By default, only fields containing values are shown. Enable **Show All Fields** under `Settings` → `Search Settings` to display missing fields as "(empty)".
5. Choose an action:
   - **Apply**: Apply both the selected metadata fields and lyrics to the editor.
   - **Lyrics Only**: Apply only the lyrics to the editor.
6. Back in the editor, review the content, then tap **Save**.

::: tip No search results?
Check that at least one plugin supporting song search is installed and enabled in `Settings` → `Search Settings` → `Plugin Management`, and that plugin configuration is complete. See [Using Plugins](./plugins.md).
:::

## Cover Operations

Tap the cover area or "Cover Options" in the editor to:

| Action | Description |
|--------|-------------|
| Change Cover | Pick a local image from the system picker |
| Search Cover Online | Search covers via enabled plugins |
| Use Same-Album Cover | Use the first readable cover found from songs in the same album |
| Remove Cover | Delete the current cover |
| Save Cover | Save the current cover to a specified location or next to the audio file |
| Crop Image | Crop the current cover before applying |

Online cover search requires a plugin that supports cover search. Tap **Load more** at the bottom of the results; the **All** tab continues every cover source that still has another page.

## Artist Poster Operations

Swipe the cover area to reach the artist posters. A song with several artists gets one poster per artist.

Which artist a picture belongs to is recorded in the picture's **description**. A picture whose description is empty, or names an artist that is not in the artist field, gets a page of its own (amber label) to show it has no owner.

Tap "Artist poster options" (or the poster on the current page) to:

| Action | Description |
|--------|-------------|
| Choose Artist Poster from This Device | Pick a local image from the system picker. With several artists you pick the owner first; that artist's current poster is replaced |
| Reassign “…” to an artist | Move the current poster to another artist. If that artist already has one, choose **Swap artists** (both posters stay and exchange owners) or **Replace** (the current one is deleted) |
| Remove Artist Poster | Delete the current artist poster |
| Save Artist Poster | Export the current artist poster to the system pictures directory |
| Crop artist poster | Crop the current artist poster before applying |

An artist with no embedded poster is looked up by file name in your [artist poster folders](./library.md) — one file per artist, e.g. `Artist.jpg`. Those posters are for display only and are never written into the song; only embedded or locally picked posters are saved into the file.

The current page shows the image size and where the picture comes from (embedded or poster file).

::: tip Description no longer matches?
After you edit the artist field, an old poster's description may no longer match anyone. Such posters are still listed in the editor; "Reassign … to an artist" in their menu binds them again.
:::

## Lyrics Operations

Tap "Lyrics Options" to:

| Action | Description |
|--------|-------------|
| Search Lyrics | Search candidates through enabled lyrics sources; use **Load more** at the bottom for the next page |
| Import | Read lyrics from a text file as UTF-8 |
| Export | Save current lyrics to a specified folder or next to the audio file. TTML exports as `.ttml`, others as `.lrc`; empty lyrics are not exported |
| Simplify / Traditionalize | Convert lyrics between Simplified and Traditional Chinese |
| Lyric Offset | Adjust in 100 ms steps, enter a value manually, or reset to 0 |
| Convert lyrics format | Convert lyrics to another format |
| Organize lyrics | Reorder lines, remove empty lines, or remove credits |
| View Lyrics Text | Render word-level lyrics as plain text, with options to toggle romanization and translation |

Adjust lyric offsets in 100 ms steps, or tap the value / Manual Input to enter an integer between -10,000 and 10,000 ms. Positive values delay lyrics; negative values advance them. Reset restores 0.

### Convert Lyrics Format

Open **Lyrics Options → Convert lyrics format**, select the target format, and confirm. Then tap **Save** in the top bar.

Conversion cannot generate word-level timing when the source lyrics have none.

### Organize Lyrics

Open **Lyrics Options → Organize lyrics** and choose an operation:

- **Sort by lyric line**: Reorder lines sharing a timestamp or remove a line. Set the order separately for two-line and three-line groups.
- **Remove empty lines**: Delete blank or placeholder-only lines.
- **Remove non-lyric content**: Remove credits using [filtering rules](./settings/metadata.md#non-lyric-content-filtering).

For example, if line 1 contains the original text and line 2 contains the translation, set input line 2 to output line 1 to put the translation first. Line numbers refer to the current order; original text, translation, and romanization are not identified automatically.

Sorting supports LRC and preserves word-level timing. TTML and single-line timestamps are left unchanged. Tap **Save** after processing to write the changes to the file.

## ReplayGain

ReplayGain normalizes playback volume across different tracks.

**Steps:**

1. Find the ReplayGain section in the editor.
2. Tap **Calculate ReplayGain**. Lyrico analyzes the current audio.
3. Once complete, track gain, track peak, and reference loudness fields are populated in the editor.
4. Tap **Save** to write these values to the audio file.

You can cancel the calculation at any time. Existing ReplayGain values can be recalculated to override.

The target loudness and peak measurement come from `Settings` → `Scan Settings`. Reference loudness defaults to -18 LUFS. Peak measurement defaults to sample peak and can be changed to true peak. Recalculate the track after changing either setting to update the tag values.

## More Menu

The editor's more menu includes:

- **Song Info**: View detailed file info (duration, bitrate, sample rate, channels, path, size, etc.), copy content.
- **Share Song**: Send the audio file via the system share sheet.
- **Rename File**: Rename the file while keeping the extension (does not modify the title tag).
- **Delete Song**: Delete the local audio file. Cannot be undone.
