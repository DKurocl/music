package ua.olex.music

import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.AppFunctionStringValueConstraint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ua.olex.music.model.BrowseTree

/**
 * What Gemini can do with the music player ("Play Radiohead on Music", "What's playing?"),
 * through Android's App Functions. The KDoc below is what the assistant reads.
 */
@AppFunctionServiceEntryPoint(serviceName = "MusicFunctionService", appFunctionXmlFileName = "music_functions")
abstract class MusicFunctions : AppFunctionService() {

    /**
     * Plays music from the user's own library on their phone: songs, artists or albums matching
     * the words, in the library's order (or shuffled).
     *
     * @param query Words from a song title, artist or album, for example "Karma Police" or "Radiohead".
     * @param shuffle Whether to shuffle the matching songs.
     * @return What is playing now, as a short sentence for the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun playMusic(query: String, shuffle: Boolean): String {
        if (query.isBlank()) throw AppFunctionInvalidArgumentException("query must not be empty")
        val graph = (application as MusicApp).graph
        val songs = withContext(Dispatchers.Default) { BrowseTree(graph.library.index.first()).search(query, limit = 500) }
        if (songs.isEmpty()) return "Nothing in your library matches \"$query\"."
        val queue = if (shuffle) songs.shuffled() else songs
        ua.olex.music.playback.QuickControl.play(this, queue)
        return "Playing ${queue.first().title} by ${queue.first().artist}" + if (queue.size > 1) " and ${queue.size - 1} more." else "."
    }

    /**
     * Controls the music that is playing.
     *
     * @param action "pause", "resume", "next" or "previous".
     * @return The result as a short sentence for the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun controlPlayback(
        @AppFunctionStringValueConstraint(enumValues = ["pause", "resume", "next", "previous"]) action: String,
    ): String {
        if (action !in setOf("pause", "resume", "next", "previous")) throw AppFunctionInvalidArgumentException("unknown action $action")
        val done = ua.olex.music.playback.QuickControl.control(this, action)
        return when (action) { "pause" -> "Paused."; "resume" -> if (done) "Playing." else "Nothing to resume."; "next" -> "Next song."; else -> "Previous song." }
    }

    /**
     * Tells what song is playing on the user's music player, with its artist, album and quality.
     *
     * @return The song now playing, or empty fields when nothing is.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getNowPlaying(): NowPlayingSong =
        ua.olex.music.playback.QuickControl.now(this).let { NowPlayingSong(it.title, it.artist, it.album, it.playing) }
}

/** The song now playing. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class NowPlayingSong(
    /** Song title. */
    val title: String,
    /** Artist. */
    val artist: String,
    /** Album. */
    val album: String,
    /** Whether it is playing (false when paused). */
    val playing: Boolean,
)
