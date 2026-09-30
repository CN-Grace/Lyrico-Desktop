# Search Settings

Controls online search and batch matching behavior.

## Plugin Management

Go to: `Settings` → `Search Settings` → `Plugin Management`.

Manage installed search source plugins: import, enable/disable, reorder, configure, and uninstall. See [Using Plugins](../plugins.md) for full details.

## Search Source Tab Style

Sets how plugin source tabs appear on the search results page:

- **Icon only**: Shows just the plugin icon, taking the least width.
- **Text only**: Shows just the plugin name.
- **Icon and text**: Shows both icon and name.

With many plugins or on a narrow screen, "Icon only" fits more tabs.

## Results per Load

Sets the number of search results loaded from each plugin source at a time. Use the **Load more** button at the bottom of the results to continue. Higher values load more candidates at once but may consume more bandwidth and increase image cache size.

## Show All Fields

When enabled, the search result detail page shows every field the source returned; empty fields are shown as "(empty)".

When disabled, only fields the source actually returned are shown—useful when you only care about populated fields.

## Prioritize Filename Matching

Affects batch matching behavior:

- **Disabled** (default): Uses the audio file's built-in title and artist tags as search keywords.
- **Enabled**: Ignores built-in tags and uses the filename as the search keyword. Useful for libraries with messy tags but well-structured filenames.

When enabled, it's recommended to set the title and artist field write rules to **Overwrite** mode so match results correctly overwrite the old tags.

## Artist Separator

Joins multiple artist names into text. The default is `/`; available separators are the ideographic comma, slash, comma, and semicolon. It applies when reading multi-value tags and applying search results, and is configured separately from [Artist Split Rules](./metadata.md#artist-splitting).
