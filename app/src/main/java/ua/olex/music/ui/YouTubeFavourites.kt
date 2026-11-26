package ua.olex.music.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.olex.music.AppGraph
import ua.olex.music.model.RecordingSource
import ua.olex.music.model.RecordingSourceKind
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.user.UserRepository

/** Shares the same local heart and session-specific outbox across UI and system controllers. */
internal class YouTubeFavourites(context: Context, private val graph: AppGraph) {
    private val prefs = context.getSharedPreferences("pending_likes", Context.MODE_PRIVATE)
    private val remote = Mutex()
    private val local = Mutex()
    private var lastPull = 0L
    private var lastScope: String? = null
    private var lastRevision = -1L
    val status = MutableStateFlow<String?>(null)

    suspend fun toggle(track: Track, existingId: String? = null): Boolean {
        val scope = graph.youTubeAccount.cacheScope.value
        return local.withLock {
            val liked = graph.userLibrary.toggleFavourite(track, existingId)
            val sources = graph.userLibrary.reference(track).first()?.references.orEmpty()
            queue(scope, sources, liked)
            liked
        }
    }

    suspend fun remove(id: String) {
        val scope = graph.youTubeAccount.cacheScope.value
        local.withLock {
            val sources = graph.userLibrary.reference(id).first()?.references.orEmpty()
            graph.userLibrary.removeFavourite(id)
            queue(scope, sources, false)
        }
    }

    private fun queue(scope: String, sources: List<RecordingSource>, liked: Boolean) {
        if (scope == "public") return
        val edit = prefs.edit()
        sources.filter { it.kind == RecordingSourceKind.YOUTUBE }.forEach { source ->
            val video = requireNotNull(source.uri).removePrefix(SavedSong.ONLINE_PREFIX)
            edit.putString("$scope/$video", "${if (liked) 1 else 0}:${java.util.UUID.randomUUID()}")
        }
        edit.apply()
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        remote.withLock sync@{
            val account = graph.youTubeAccount
            val scope = account.cacheScope.value
            val revision = account.revision.value
            fun current() = account.cacheScope.value == scope && account.revision.value == revision && graph.youTubeOn.value
            if (scope == "public" || !graph.youTubeOn.value) {
                status.value = null
                return@sync
            }
            if (!graph.networkAvailable()) {
                status.value = "Offline. Changes sync when the connection returns."
                return@sync
            }
            val prefix = "$scope/"
            val queued = prefs.all.filterKeys { it.startsWith(prefix) }
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && queued.isEmpty() && lastScope == scope && lastRevision == revision && now - lastPull < 300_000) return@sync
            status.value = "Syncing likes…"
            try {
                for ((key, operation) in queued) {
                    if (!current()) return@sync
                    val desired = when (operation) {
                        is Boolean -> operation
                        is String -> operation.startsWith("1:")
                        else -> continue
                    }
                    if (graph.youTube.like(key.removePrefix(prefix), desired, scope, revision)) local.withLock {
                        if (current() && prefs.all[key] == operation) prefs.edit().remove(key).apply()
                    }
                }
                if (!current()) return@sync
                val snapshot = graph.youTube.likedPlaylist(scope, revision)
                local.withLock {
                    if (!current()) return@sync
                    val pending = prefs.all.keys.filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
                    // An empty or unexpected renderer must never clear imported favourites.
                    val knownLikes = snapshot.entries.mapTo(HashSet()) { it.videoId }
                    val origins = records(scope)
                    val complete = snapshot.confirmsAllLikes
                    val confirmedTracks = if (complete) snapshot.tracks else snapshot.tracks.filter {
                        it.id.value.startsWith("yt:") && it.id.value.removePrefix("yt:") in knownLikes
                    }
                    val next = graph.userLibrary.mergeImportedFavourites(
                        confirmedTracks, origins, if (complete) knownLikes else knownLikes + origins.keys, pending, ::current,
                    )
                    if (!current()) return@sync
                    val stored = JSONObject()
                    next.forEach { (video, origin) ->
                        stored.put(video, JSONObject().put("id", origin.recordingId).put("at", origin.addedAt))
                    }
                    prefs.edit().putString("imported:$scope", stored.toString()).apply()
                    status.value = if (pending.isEmpty()) "Likes synced" else "${pending.size} changes waiting to sync"
                }
                lastScope = scope
                lastRevision = revision
                lastPull = now
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current()) status.value = "Couldn't sync likes. Try again."
            } finally {
                if (status.value == "Syncing likes…") status.value = null
            }
        }
    }

    private fun records(scope: String): Map<String, UserRepository.ImportedFavourite> {
        val json = prefs.getString("imported:$scope", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { video ->
            val item = json.optJSONObject(video) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            video to UserRepository.ImportedFavourite(id, item.optLong("at"))
        }.toMap()
    }
}
