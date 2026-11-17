package ua.olex.music.playback

import android.net.Uri

/**
 * Application bridge for online songs: a queue item whose address is `ytm://<id>` is turned into
 * the real stream address only when the player opens it (such addresses expire within hours, so
 * they are never stored). The playback module never depends on the online client itself.
 */
interface OnlineStreams {
    /** The playable address for an online song id, or null. Called on a loader thread; may block. */
    fun resolve(id: String): Uri?

    /** Drops a remembered address that stopped working (it expired early), so the next open asks again. */
    fun forget(id: String) {}

    /** A cheap availability snapshot used to skip temporarily unavailable items without deleting them. */
    fun available(uri: String): Boolean = true

    companion object {
        const val SCHEME = "ytm"
    }
}
