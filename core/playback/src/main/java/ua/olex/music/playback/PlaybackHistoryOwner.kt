package ua.olex.music.playback

import ua.olex.music.model.Track

/** Application bridge for durable history; the playback module never depends on the UI or Room. */
interface PlaybackHistoryOwner {
    suspend fun recordPlayback(track: Track, observedAt: Long): Long
    suspend fun updateListening(eventId: Long, millis: Long)
    suspend fun recordExplicitEarlySkip(eventId: Long, audibleMs: Long) = Unit
}
