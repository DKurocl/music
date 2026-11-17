package ua.olex.music.playback

import ua.olex.music.model.Track

/** A continuation is committed only after the service confirms that its requesting queue is current. */
data class QueueExtension(val tracks: List<Track>, val commit: () -> Boolean)

/** Radio and autoplay belong to the playback service lifecycle, independently of any UI controller. */
interface PlaybackQueueOwner {
    suspend fun extendPlaybackQueue(queue: List<Track>, current: Int): QueueExtension?
}
