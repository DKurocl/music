package ua.olex.music.playback

import ua.olex.music.lyrics.Lyrics
import ua.olex.music.model.Track

/** The same lyrics and user timing adjustment consumed by both app and lock-screen surfaces. */
data class PlaybackLyrics(val lyrics: Lyrics, val offsetMs: Long)

/** App bridge keeps sidecar grants, edited lyrics and user storage out of the playback module. */
interface PlaybackLyricsOwner {
    suspend fun playbackLyrics(track: Track): PlaybackLyrics
}
