package ua.olex.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import ua.olex.music.model.PlaybackSourceChoice
import ua.olex.music.model.SourceIssue
import java.util.UUID

/** Every transport path uses this conversion, preserving occurrence identity and playback context. */
@OptIn(UnstableApi::class)
internal object PlaybackSourceItems {
    const val ISSUE = "ua.olex.music.source_issue"
    const val UNAVAILABLE = "unavailable"

    /** Bundle reference equality must not rebuild an unchanged timeline after each cache event. */
    fun same(a: MediaItem, b: MediaItem): Boolean {
        val x = a.mediaMetadata; val y = b.mediaMetadata
        return a.mediaId == b.mediaId && a.localConfiguration?.uri == b.localConfiguration?.uri &&
            a.requestMetadata.mediaUri == b.requestMetadata.mediaUri && a.clippingConfiguration == b.clippingConfiguration &&
            x.title == y.title && x.artist == y.artist && x.albumTitle == y.albumTitle && x.albumArtist == y.albumArtist &&
            x.artworkUri == y.artworkUri && x.durationMs == y.durationMs && x.trackNumber == y.trackNumber && x.discNumber == y.discNumber &&
            listOf(PlayerConnection.QUEUE_KEY, PlayerConnection.CONTEXT_KEY, PlayerConnection.REQUESTED_KEY,
                PlayerConnection.RECORDING_KEY, PlayerConnection.SOURCES_KEY, PlayerConnection.PATH_KEY, ReplayGainCodec.TAGS, ISSUE)
                .all { key -> x.extras?.getString(key) == y.extras?.getString(key) }
    }

    suspend fun resolve(owner: MediaCatalogOwner?, items: List<MediaItem>, rejected: Set<String> = emptySet(), allowDirect: Boolean = false): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        if (owner == null) return items.map { item -> item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item }
        val choices = owner.playbackSources(items.map(PlayerConnection::track), rejected, allowDirect)
        require(choices.size == items.size) { "Source selection must preserve every queue occurrence" }
        return items.zip(choices).map { (original, choice) -> convert(original, choice) }
    }

    fun convert(original: MediaItem, choice: PlaybackSourceChoice): MediaItem {
        val old = original.mediaMetadata.extras
        val key = old?.getString(PlayerConnection.QUEUE_KEY) ?: UUID.randomUUID().toString()
        val intent = old?.getString(PlayerConnection.REQUESTED_KEY) ?: original.mediaId
        val selected = PlayerConnection.mediaItem(choice.track.copy(requestedId = intent), key, old?.getString(PlayerConnection.CONTEXT_KEY))
        val extras = Bundle(selected.mediaMetadata.extras ?: Bundle.EMPTY)
        val sameSource = original.requestMetadata.mediaUri?.toString() == choice.track.uri
        // A trusted direct URI may have gain tags but no current scanner entry. Never borrow them
        // for a different file or for an online reserve.
        if (sameSource && choice.track.metadata == null && !choice.track.uri.startsWith("ytm://"))
            old?.getString(ReplayGainCodec.TAGS)?.let { extras.putString(ReplayGainCodec.TAGS, it) }
        choice.issue?.let { extras.putString(ISSUE, it.name) }
        return selected.buildUpon().setMediaMetadata(selected.mediaMetadata.buildUpon().setExtras(extras).build())
            .apply { if (choice.issue != null) setUri(Uri.Builder().scheme(UNAVAILABLE).authority("source").appendPath(key).build()) }.build()
    }

    fun error(item: MediaItem?): String? = when (item?.mediaMetadata?.extras?.getString(ISSUE)) {
        SourceIssue.OFFLINE.name -> "This song needs online playback or a complete cached copy."
        SourceIssue.AMBIGUOUS.name -> "The available file may be a different version of this song."
        SourceIssue.MISSING.name -> "No source is currently available for this song."
        else -> null
    }
}
