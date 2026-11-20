package ua.olex.music.ui

import android.os.Bundle
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.core.os.bundleOf

/** Each bottom destination owns its catalogue history. Only navigation arguments enter saved state. */
internal class OnlineTabHistory {
    private val pages = mutableStateMapOf<Place, List<OnlineTarget>>()
    operator fun get(place: Place): List<OnlineTarget> = pages[place].orEmpty()

    fun open(place: Place, page: OnlineTarget) {
        val stack = this[place]
        if (stack.lastOrNull() != page) pages[place] = stack + page
    }

    fun back(place: Place) { pages[place] = this[place].dropLast(1) }
    fun clear(place: Place) { pages.remove(place) }

    companion object {
        private fun encodeSeed(track: ua.olex.music.model.Track?): String? = track?.takeIf(YouTubeMusic::isOnline)?.let {
            org.json.JSONObject().put("tracks", OnlinePageJson.tracks(listOf(it))).toString()
        }


        val saver = Saver<OnlineTabHistory, Bundle>(
            save = { history -> Bundle().apply {
                for (place in Place.entries) putParcelableArrayList(place.name, ArrayList(history[place].map { page ->
                    when (page) {
                        is OnlineTarget.Artist -> bundleOf("artist" to page.name, "id" to page.id, "full" to page.full,
                            "seed" to encodeSeed(page.seed), "photo" to page.photo)
                        is OnlineTarget.Album -> bundleOf("album" to page.title, "id" to page.id, "playlist" to page.playlist,
                            "cover" to page.cover, "subtitle" to page.subtitle, "seed" to encodeSeed(page.seed))
                    }
                }))
            } },
            restore = { saved -> OnlineTabHistory().apply {
                for (place in Place.entries) for (args in saved.getParcelableArrayList(place.name, Bundle::class.java).orEmpty()) {
                    val artist = args.getString("artist")
                    val id = args.getString("id")
                    val seed = args.getString("seed")?.let { json ->
                        runCatching { OnlinePageJson.tracks(org.json.JSONObject(json), "tracks").singleOrNull() }.getOrNull()
                    }
                    if (artist != null) open(place, OnlineTarget.Artist(artist, id, args.getBoolean("full"), seed, args.getString("photo")))
                    else if (id != null) open(place, OnlineTarget.Album(id, args.getString("album").orEmpty(),
                        args.getBoolean("playlist"), args.getString("cover"), args.getString("subtitle"), seed))
                }
            } },
        )
    }
}
