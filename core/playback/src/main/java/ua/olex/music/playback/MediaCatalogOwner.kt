package ua.olex.music.playback

import ua.olex.music.model.BrowseTree

/**
 * Application bridge for browsing: Android Auto, Assistant and the system media controls ask the
 * service for the library, and the app answers with a fresh [BrowseTree]. The playback module
 * never depends on Room or the UI.
 */
interface MediaCatalogOwner {
    suspend fun browseTree(): BrowseTree
    /** Source/availability changes are observed by the service without a bound app screen. */
    val playbackSourceChanges: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()

    /** Resolve one bulk request off main; keep every requested occurrence in its original position. */
    suspend fun playbackSources(tracks: List<ua.olex.music.model.Track>, rejected: Set<String> = emptySet(), allowDirect: Boolean = false): List<ua.olex.music.model.PlaybackSourceChoice> =
        tracks.map { ua.olex.music.model.PlaybackSourceChoice(it) }
}
