package ua.olex.music.playback

import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.Track

/** The system media controls use the same recording favourites as the app. */
interface PlaybackFavouriteOwner {
    val favouriteChanges: Flow<Unit>
    suspend fun isPlaybackFavourite(track: Track): Boolean
    suspend fun togglePlaybackFavourite(track: Track)
}
