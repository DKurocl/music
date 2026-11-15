package ua.olex.music.playback

import ua.olex.music.model.BrowseTree

/**
 * Application bridge for browsing: Android Auto, Assistant and the system media controls ask the
 * service for the library, and the app answers with a fresh [BrowseTree]. The playback module
 * never depends on Room or the UI.
 */
interface MediaCatalogOwner {
    suspend fun browseTree(): BrowseTree
}
