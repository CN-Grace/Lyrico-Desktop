# Library & Folders

Manage music folders that Lyrico scans, and maintain your library.

## Folder Management

Go to: `Settings` → `Scan Settings` → `Folder Management`.

The folder management page has **Folders** and **Songs** tabs for viewing the folder tree and songs within a given scope.

### Add a Folder

Tap the add button, select a music folder in the system file picker, and grant access. Lyrico starts scanning automatically after adding.

If `Settings` → `Scan Settings` → `Skip audio under 60 seconds` is enabled, audio files 60 seconds or shorter will be skipped—useful for filtering out notification sounds, short clips, and samples.

### Refresh a Folder

"Refresh Folder" re-scans the corresponding folder. Useful when:
- You've added new audio files via a file manager
- You've deleted some audio files
- You've modified file tags with another tool

### Hide and Show Folders

**Hide Folder**: Songs in this folder and its subfolders are hidden from song lists, artist lists, album lists, and local search results.

**Show Folder**: Restore visibility of a previously hidden folder.

Hiding does not delete files or remove the folder record. It's useful for temporarily excluding folders like ringtones or podcasts.

### Remove a Folder

Removes the folder and its song records from Lyrico's library. Local audio files are **not deleted**. You can re-add the folder later.

### Operation Comparison

| Action | Local Files | Folder Record | Songs Visible |
|--------|-------------|---------------|---------------|
| Hide Folder | Kept | Kept | Hidden, can be shown again |
| Remove Folder | Kept | Removed | Hidden, needs re-adding |

## Artist Poster Folders

The Artists views (list and detail page) and the single-song editor prefer **embedded artwork** that belongs to that artist. Ownership comes from the picture's **description**: the artist named there is the one it belongs to. Old pictures with no description are treated as belonging to the song's only artist. When one artist has no usable embedded picture, Lyrico looks up a poster by **file name** in your artist poster folders for that artist. Go to: `Settings` → `Scan Settings` → `Artist Poster Folders`.

**Add a folder:** Tap the add button in the top bar, select the folder holding your posters in the system file picker, and grant access. Lyrico reads the posters in it immediately.

**Poster naming rules:**

| File name | Artist it matches |
|-----------|-------------------|
| `Artist.jpg` | The artist whose name matches exactly |
| `Artist_live.jpg` | The same artist, with the name followed by `_` and a suffix |
| `AC_DC.jpg`, `AC-DC.jpg`, `AC／DC.jpg`, or `ACDC.jpg` | An artist whose name contains characters a file name cannot hold (`\ / : * ? " < > \|`), such as `AC/DC` |

JPG, JPEG, PNG, WebP, and MP4 (first video frame) are supported. Only files directly inside the selected folder are considered; subfolders are not searched.

**Multiple folders:** They are searched in the order they were added, and the first hit wins. Inside one folder, an exact full-name match beats a suffixed variant.

**Match status:** Each folder row shows the total number of posters it contains and how many of them are named after an artist in your library. A count of 0 usually means the file names do not line up with the artist names—open the folder to check them one by one.

**Remove a folder:** This only stops using the posters in that folder; local files are not deleted. Re-add it whenever you need it again.

## Scan Settings

`Settings` → `Scan Settings` includes:

- **Folder Management**: Manage added music folders (same as above).
- **Artist Poster Folders**: Manage artist poster folders (same as above).
- **Skip audio under 60 seconds**: When enabled, Lyrico skips audio files ≤ 60 seconds long.
- **Lyric Index**: When enabled, a lyric index is built during scanning and used for local lyric search. Enabling it makes scanning slower, and after changing the switch existing songs must be rescanned for it to take effect.
- **ReplayGain reference loudness**: The target loudness used when calculating ReplayGain, **-18 LUFS** by default. Choose from three presets — **-23 LUFS (EBU R128)**, **-18 LUFS (Default)**, **-14 LUFS (Streaming)** — or enter a custom value between **-60 and 0 LUFS**. This setting affects single-song, album, and batch ReplayGain calculations. See [Single Song Editing ReplayGain](./single-song.md#replaygain).
- **ReplayGain peak type**: Selects the peak measurement written to ReplayGain peak tags. The default **sample peak** matches traditional ReplayGain peak semantics. **True peak** estimates the reconstructed signal through oversampling. Both use 1.0 for digital full scale, but decoded compressed audio can produce a sample peak above 1.0. This setting applies to single-song, album, and batch calculations.

## Folder Permission Issues

Lyrico automatically cleans up library records and refreshes indexes when:
- Folder permissions are lost (e.g., system revoked storage access)
- A folder is deleted or moved
- Songs in the database no longer exist on the device

If you see "some files cannot be accessed":
1. Go to `Folder Management` and remove the affected folder.
2. Re-add the folder and grant access again.
