package ua.olex.music.playback

import android.content.Context
import android.content.Intent

/**
 * Music talks to the owner's Earbuds app (ua.olex.sonyanc), as two parts of one system:
 *  - Sony correction chosen in Music: the earbuds' own equalizer is turned off, so the two never
 *    stack (Music used to ask the owner to do it by hand);
 *  - the sleep timer has stopped the music: the earbuds do what Earbuds is set to (turn off by
 *    default), so they do not run down overnight.
 * The broadcasts are explicit and need Earbuds' signature permission: only apps signed with the
 * owner's key can send them, and nothing happens when Earbuds is not installed.
 */
internal object EarbudsLink {
    private const val PACKAGE = "ua.olex.sonyanc"
    private const val PERMISSION = "ua.olex.sonyanc.permission.CONTROL"
    const val EQ_OFF = "ua.olex.sonyanc.action.EQ_OFF"
    const val SLEEP = "ua.olex.sonyanc.action.SLEEP"

    fun send(context: Context, action: String) {
        runCatching {
            context.sendBroadcast(Intent(action).setPackage(PACKAGE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), PERMISSION)
        }
    }
}
