package ua.olex.music.model

/** Orders a deferred autoplay decision against newer transport commands on the player thread. */
class PlaybackIntent {
    private var revision = 0L
    private var playing = false

    fun revision(): Long = revision
    fun explicitlyPaused(): Boolean = revision != 0L && !playing

    fun request(playing: Boolean) {
        this.playing = playing
        revision++
    }

    fun shouldPlay(since: Long, default: Boolean): Boolean =
        if (since == revision) default else playing
}
