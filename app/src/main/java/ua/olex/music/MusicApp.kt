package ua.olex.music

import android.app.Application
import android.content.Context
import ua.olex.music.library.LibraryDatabase
import ua.olex.music.library.LibraryRepository
import ua.olex.music.playback.OutputRouteMonitor
import ua.olex.music.playback.PlayerConnection

/**
 * Everything that lives as long as the app, made on first use. Plain constructors instead of a
 * DI framework: there is one of each, the graph fits on a screen, and the build stays fast.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val library by lazy { LibraryRepository(app, LibraryDatabase.open(app)) }
    val player by lazy { PlayerConnection(app) }
    val output by lazy { OutputRouteMonitor(app) }
}

class MusicApp : Application() {
    val graph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as MusicApp).graph
