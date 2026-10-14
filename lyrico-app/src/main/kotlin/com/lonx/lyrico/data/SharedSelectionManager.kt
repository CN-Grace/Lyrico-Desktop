package com.lonx.lyrico.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedSelectionManager {

    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _swipeAnchorUri = MutableStateFlow<String?>(null)
    val swipeAnchorUri: StateFlow<String?> = _swipeAnchorUri.asStateFlow()

    private val _visibleUris = MutableStateFlow<List<String>>(emptyList())

    /**
     * The songs the list currently on screen is showing, in display order.
     *
     * This exists for one caller: the window-level `Ctrl+A` shortcut, which has to select "everything
     * the user can see" but sits above every screen and therefore has no list of its own. It is
     * published by the screens rather than derived here because only they know what survived their
     * filter and sort -- `Ctrl+A` in a search result must select the matches, not the whole library.
     *
     * Empty until a list has published, which makes `Ctrl+A` a no-op on the screens that show no songs
     * rather than a way to select rows that are not on screen.
     */
    val visibleUris: StateFlow<List<String>> = _visibleUris.asStateFlow()

    private var swipeAnchorUriValue: String?
        get() = _swipeAnchorUri.value
        set(value) {
            _swipeAnchorUri.value = value
        }

    /**
     * Records what the calling list is displaying. Purely informational: it changes no selection state,
     * so a screen may publish on every list change without affecting what the user has selected.
     */
    fun publishVisibleUris(uris: List<String>) {
        _visibleUris.value = uris
    }

    fun setUris(uris: Set<String>) {
        _selectedUris.value = uris
        if (uris.isNotEmpty()) {
            _isSelectionMode.value = true
        }
        if (swipeAnchorUriValue !in uris) {
            swipeAnchorUriValue = null
        }
    }

    /**
     * Adds or removes one song -- the ctrl+click, the tap inside selection mode, and the long press
     * that starts it.
     *
     * The anchor moves to [uri] whether the click selected it or deselected it. Explorer does the same
     * -- the shift anchor follows the last row clicked, not the last row selected -- and it is what
     * makes the ordinary desktop gesture work: ctrl+click one row, shift+click another, and get the run
     * between them. It is also the anchor Android's swipe selection expects, because the long press that
     * enters selection mode goes through here too; without it a drag could only ever add single rows.
     */
    fun toggle(uri: String) {
        _isSelectionMode.value = true
        _selectedUris.value = if (_selectedUris.value.contains(uri)) {
            _selectedUris.value - uri
        } else {
            _selectedUris.value + uri
        }
        swipeAnchorUriValue = uri
    }

    fun selectAll(uris: Set<String>) {
        setUris(uris)
    }

    fun deselectAll() {
        _selectedUris.value = emptySet()
        swipeAnchorUriValue = null
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedUris.value = emptySet()
        swipeAnchorUriValue = null
    }

    fun selectSwipeRange(uri: String, visibleUris: List<String>) {
        val selectedUris = _selectedUris.value

        if (!_isSelectionMode.value) {
            _isSelectionMode.value = true
            _selectedUris.value = selectedUris + uri
            swipeAnchorUriValue = uri
            return
        }

        if (uri in selectedUris) {
            _selectedUris.value = selectedUris + uri
            swipeAnchorUriValue = uri
            return
        }

        val anchorUri = swipeAnchorUriValue
        if (anchorUri == null || anchorUri !in selectedUris) {
            _selectedUris.value = selectedUris + uri
            swipeAnchorUriValue = uri
            return
        }

        val anchorIndex = visibleUris.indexOf(anchorUri)
        val uriIndex = visibleUris.indexOf(uri)
        if (anchorIndex == -1 || uriIndex == -1) {
            _selectedUris.value = selectedUris + uri
            swipeAnchorUriValue = uri
            return
        }

        val start = minOf(anchorIndex, uriIndex)
        val end = maxOf(anchorIndex, uriIndex)
        _selectedUris.value = selectedUris + visibleUris.subList(start, end + 1)
        _isSelectionMode.value = true
        swipeAnchorUriValue = null
    }

    /**
     * Replaces the selection with the run of rows between the anchor and [uri] -- the desktop
     * shift+click, which has no Android original.
     *
     * Two deliberate differences from [selectSwipeRange], which does the same thing for a drag:
     *
     * - **The anchor is kept, not cleared.** A drag is a one-shot gesture that ends when the finger
     *   lifts, so the next drag has to start a new range. A user holding shift clicks one row after
     *   another, and every one of those clicks must measure from the same anchor -- clearing it would
     *   restart the range at each click and make the second shift+click shrink the selection back to
     *   two rows.
     * - **It replaces the selection instead of adding to it.** This is what Explorer does, and it is
     *   what makes shift+click usable as "select up to here": the range is the answer, not the union
     *   of every range ever clicked.
     *
     * A click with no usable anchor (nothing selected yet, or the anchor is no longer in
     * [visibleUris] because the list was re-sorted) selects [uri] alone and anchors there, which is
     * also how the first shift+click of a session behaves.
     */
    fun extendRange(uri: String, visibleUris: List<String>) {
        val anchorUri = swipeAnchorUriValue
        val anchorIndex = anchorUri?.let { visibleUris.indexOf(it) } ?: -1
        val uriIndex = visibleUris.indexOf(uri)

        if (anchorIndex == -1 || uriIndex == -1) {
            _isSelectionMode.value = true
            _selectedUris.value = setOf(uri)
            swipeAnchorUriValue = uri
            return
        }

        val start = minOf(anchorIndex, uriIndex)
        val end = maxOf(anchorIndex, uriIndex)
        _isSelectionMode.value = true
        _selectedUris.value = visibleUris.subList(start, end + 1).toSet()
        swipeAnchorUriValue = anchorUri
    }

    fun replaceUris(uriMapping: Map<String, String>) {
        if (uriMapping.isEmpty()) return
        _selectedUris.value = _selectedUris.value.map { uri ->
            uriMapping[uri] ?: uri
        }.toSet()
        swipeAnchorUriValue = swipeAnchorUriValue?.let { uriMapping[it] ?: it }
    }

    fun clearAll() {
        exitSelectionMode()
    }
}
