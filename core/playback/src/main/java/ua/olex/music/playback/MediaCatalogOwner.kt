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

    /** An installed local occurrence has a confirmed video reserve, even if it never becomes audible. */
    suspend fun rememberPlaybackLink(link: ua.olex.music.model.SavedSong) {}

    /**
     * Resolve one bulk request off main, preserving occurrence order. Only an explicit playback
     * selection may spend part of its latency budget checking provider metadata.
     */
    suspend fun playbackSources(
        tracks: List<ua.olex.music.model.Track>,
        rejected: Set<String> = emptySet(),
        allowDirect: Boolean = false,
        enrichSelection: Boolean = false,
    ): List<ua.olex.music.model.PlaybackSourceChoice> = tracks.map { ua.olex.music.model.PlaybackSourceChoice(it) }
}
