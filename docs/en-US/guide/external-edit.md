# External Editing Integration

Players can use an Intent to open Lyrico's single-song editor for audio tags, lyrics, and cover art.

## 1. Prepare the file

Obtain a `content://` URI for the original audio file from MediaStore, SAF, or FileProvider. It must support reading and opening a seekable file descriptor in `rw` mode. A custom provider must support URI grants and reading and writing the original file.

## 2. Open the editor

Call from an Activity, with `audioUri` set to the URI obtained above:

```kotlin
val intent = Intent("com.lonx.lyrico.action.EDIT_TAG").apply {
    setPackage("com.lonx.lyrico")
    setDataAndType(audioUri, "audio/*")
    clipData = ClipData.newRawUri("audio", audioUri)
    addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
}
startActivity(intent)
```

Catch `ActivityNotFoundException` and prompt the user to install Lyrico if it is unavailable. For Debug builds, use the package name `com.lonx.lyrico.debug`.

## 3. Refresh on return

When the user taps **Save**, Lyrico writes the changes and closes the editor. When the player returns to the foreground, reload the edited song's tags, lyrics, and cover art, and update its cache.

To receive a save result, register a callback in the Activity and replace `startActivity(intent)` above with `editLauncher.launch(intent)`:

```kotlin
private val editLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
            // Reload the song at uri and refresh its cache
        }
    }
}
```

A successful save returns `RESULT_OK` with the original audio URI in `Intent.data`. Exiting without saving returns `RESULT_CANCELED`. A failed save keeps the editor open for retry. Players using `startActivity()` need no changes and can continue refreshing when returning to the foreground.

Verify opening, saving, cancelling, and refreshing on return. To join the [supported players list](https://github.com/Replica0110/Lyrico#已适配-lyrico-外部编辑功能的播放器), submit a PR with the player name, link, and minimum supported version.
