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
    // The cover may need the network (an online song's), which the main thread may not use.
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread { try { NowPlaying.render(context, ids) } finally { pending.finish() } }.start()
    }
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
        // One snapshot of the state, numbered: a slower earlier render (its cover still loading) can
        // never overwrite a newer one. Title and buttons go out at once; the cover follows.
        val generation = rendered.incrementAndGet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString("title", null); val artist = prefs.getString("artist", null)
        val art = prefs.getString("art", null); val playing = prefs.getBoolean("playing", false)
        val manager = AppWidgetManager.getInstance(context)
        val known = lastArt?.takeIf { it.first == art }?.second
        manager.updateAppWidget(ids, views(context, title, artist, known, playing, interactive = true))
        if (known != null || art == null) return
        val bitmap = artwork(context, art) ?: return
        if (rendered.get() == generation) manager.updateAppWidget(ids, views(context, title, artist, bitmap, playing, interactive = true))
    }
    private val rendered = java.util.concurrent.atomic.AtomicLong()

    private fun views(context: Context, title: String?, artist: String?, art: Bitmap?, playing: Boolean, interactive: Boolean): RemoteViews {
        fun layout(id: Int) = RemoteViews(context.packageName, id).apply {
            setTextViewText(R.id.widget_title, title ?: context.getString(R.string.widget_idle))
            setTextViewText(R.id.widget_artist, artist.orEmpty())
            setImageViewResource(R.id.widget_play, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
            setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.widget_pause else R.string.widget_play))
            art?.let { setImageViewBitmap(R.id.widget_art, it) }
                ?: setImageViewResource(R.id.widget_art, R.drawable.widget_note)
            if (interactive) {
                setOnClickPendingIntent(R.id.widget_play, button(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, button(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                setOnClickPendingIntent(R.id.widget_previous, button(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                // A tap on the widget opens the player itself, as the media notification does.
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { putExtra(PlaybackService.OPEN_PLAYER, true); addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP) }?.let {
                    setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
        }
        // Two rows when the widget is tall enough, one row when it is short; the launcher picks.
        return RemoteViews(mapOf(
            android.util.SizeF(0f, 0f) to layout(R.layout.widget_now_playing_short),
            android.util.SizeF(0f, 90f) to layout(R.layout.widget_now_playing),
        ))
    }

    /**
     * The widget picker's picture (Android 15+ generated previews): the widget as it really looks,
     * with the owner's last song and its cover, playing, instead of an empty "Nothing playing".
     * Published once per app version (and once more when the first song has played); the call
     * is rate-limited, so a refusal is simply retried next time. Runs off the main thread.
     */
    fun publishPreview(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        val title = prefs.getString("title", null)
        val key = "$version:${title != null}"
        if (prefs.getString("preview", null) == key) return
        val views = views(context, title ?: context.getString(R.string.widget_sample_title), prefs.getString("artist", null) ?: context.getString(R.string.widget_sample_artist),
            artwork(context, prefs.getString("art", null)), playing = true, interactive = false)
        val ok = runCatching {
            AppWidgetManager.getInstance(context).setWidgetPreview(ComponentName(context, NowPlayingWidget::class.java),
                android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN or android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD, views)
        }.getOrDefault(false)
        if (ok) prefs.edit().putString("preview", key).apply()
    }

    /** A small cover; widgets have a bitmap memory budget, so never the full picture. */
    @Volatile private var lastArt: Pair<String, Bitmap>? = null

    /**
     * A small cover; widgets have a bitmap memory budget, so never the full picture. A file's cover
     * is decoded from the device; an online song's is fetched at 256 px (its address asks for a
     * size) and kept, so play, pause and skip do not fetch it again.
     */
    private fun artwork(context: Context, uri: String?): Bitmap? = uri?.let {
        lastArt?.takeIf { (key, _) -> key == it }?.let { (_, bitmap) -> return bitmap }
        runCatching {
            if (it.startsWith("http")) {
                val small = it.replace(Regex("""=w\d+-h\d+"""), "=w256-h256")
                val connection = java.net.URL(small).openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
                val bytes = connection.getInputStream().use { input -> input.readNBytes(2 shl 20) }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = generateSequence(1) { it * 2 }.first { s -> maxOf(bounds.outWidth, bounds.outHeight) / s <= 384 }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(it))) { decoder, info, _ ->
                val scale = 192f / maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()?.also { bitmap -> lastArt = it to bitmap }
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
