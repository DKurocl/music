package ua.olex.music.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.view.KeyEvent
import android.widget.RemoteViews

/**
 * The home-screen widget: cover, song, artist, previous / play-pause / next. It never polls: the
 * playback service pushes a new state when the song or play state changes ([NowPlaying.publish]),
 * and the last state is kept so a newly added widget shows it at once. Buttons are media-button
 * broadcasts to Media3's receiver, which starts the service if needed (playback resumption).
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = NowPlaying.render(context, ids)
}

/** What the widget and the Quick Settings tile show; written only by the playback service. */
internal object NowPlaying {
    private const val PREFS = "now_playing"

    fun publish(context: Context, title: String?, artist: String?, artworkUri: String?, playing: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = prefs.getString("title", null) != title || prefs.getString("artist", null) != artist ||
            prefs.getString("art", null) != artworkUri || prefs.getBoolean("playing", false) != playing
        if (!changed) return
        prefs.edit().putString("title", title).putString("artist", artist).putString("art", artworkUri).putBoolean("playing", playing).apply()
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
        if (ids.isNotEmpty()) Thread { render(context, ids) }.start()
        MusicTileService.refresh(context)
    }

    fun playing(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("playing", false)
    fun title(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("title", null)

    fun render(context: Context, ids: IntArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val playing = prefs.getBoolean("playing", false)
        val art = artwork(context, prefs.getString("art", null))
        fun layout(id: Int) = RemoteViews(context.packageName, id).apply {
            setTextViewText(R.id.widget_title, prefs.getString("title", null) ?: context.getString(R.string.widget_idle))
            setTextViewText(R.id.widget_artist, prefs.getString("artist", null).orEmpty())
            setImageViewResource(R.id.widget_play, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
            setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.widget_pause else R.string.widget_play))
            art?.let { setImageViewBitmap(R.id.widget_art, it) }
                ?: setImageViewResource(R.id.widget_art, R.drawable.widget_note)
            setOnClickPendingIntent(R.id.widget_play, button(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            setOnClickPendingIntent(R.id.widget_next, button(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            setOnClickPendingIntent(R.id.widget_previous, button(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        // Two rows when the widget is tall enough, one row when it is short; the launcher picks.
        val views = RemoteViews(mapOf(
            android.util.SizeF(0f, 0f) to layout(R.layout.widget_now_playing_short),
            android.util.SizeF(0f, 90f) to layout(R.layout.widget_now_playing),
        ))
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
    }

    /** A small cover; widgets have a bitmap memory budget, so never the full picture. */
    private fun artwork(context: Context, uri: String?): Bitmap? = uri?.let {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(it))) { decoder, info, _ ->
                val scale = 192f / maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    fun button(context: Context, keyCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setClassName(context, "androidx.media3.session.MediaButtonReceiver")
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

}

/** Quick Settings tile: play or pause without opening anything; shows the song as its subtitle. */
class MusicTileService : android.service.quicksettings.TileService() {
    override fun onStartListening() { update() }

    override fun onClick() {
        runCatching { NowPlaying.button(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).send() }
    }

    private fun update() {
        val tile = qsTile ?: return
        val playing = NowPlaying.playing(this)
        tile.state = if (playing) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE
        tile.subtitle = NowPlaying.title(this)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) = runCatching {
            requestListeningState(context, ComponentName(context, MusicTileService::class.java))
        }
    }
}
