package ua.olex.music

import android.app.Application
import android.content.Context
import ua.olex.music.library.LibraryDatabase
import ua.olex.music.library.LibraryRepository
import ua.olex.music.playback.OutputRouteMonitor
import ua.olex.music.playback.PlayerConnection
import ua.olex.music.playback.PlaybackHistoryOwner
import ua.olex.music.model.Track
import ua.olex.music.user.UserDatabase
import ua.olex.music.user.UserRepository

/**
 * Everything that lives as long as the app, made on first use. Plain constructors instead of a
 * DI framework: there is one of each, the graph fits on a screen, and the build stays fast.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val musicFiles by lazy { ua.olex.music.library.LocalMusicFiles(app) }
    val library by lazy { LibraryRepository(app, LibraryDatabase.open(app), musicFiles) }
    val artwork by lazy { ua.olex.music.library.LocalArtworkCache(app, musicFiles) }
    val player by lazy { PlayerConnection(app) }
    val output by lazy { OutputRouteMonitor(app) }
    val userLibrary by lazy { UserRepository(UserDatabase.open(app)) }
}

class MusicApp : Application(), PlaybackHistoryOwner, ua.olex.music.library.LocalArtworkOwner {
    override val localArtwork get() = graph.artwork
    val graph by lazy { AppGraph(this) }
    override suspend fun recordPlayback(track: Track) = graph.userLibrary.recordPlay(track)
}

val Context.graph: AppGraph get() = (applicationContext as MusicApp).graph
