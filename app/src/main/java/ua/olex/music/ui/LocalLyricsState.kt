package ua.olex.music.ui

import ua.olex.music.lyrics.Lyrics

/** A source label and any storage warning accompany the exact displayed lyric snapshot. */
data class LocalLyricsState(val lyrics: Lyrics = Lyrics.EMPTY, val source: String = "No local lyrics",
    val note: String? = null, val mediaId: String? = null)
