package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LargePlaybackCatalogueTest {
    @Test fun indexedOwnershipPreservesExactIntentAtRepresentativeLibrarySizes() {
        for (size in listOf(1_000, 10_000, 50_000)) {
            val files = List(size) { i ->
                Track(TrackId("local:$i"), "content://library/$i", "Song $i", "Artist ${i % 120}",
                    album = "Album ${i / 12}", path = "Music/Album ${i / 12}/Song $i.flac", durationMs = 180_000L + i)
            }
            val library = LibraryIndex.build(files)
            val policy = PlaybackSources(library, emptyList())
            for (index in listOf(0, size / 2, size - 1)) {
                val file = files[index]
                val provider = file.copy(id = TrackId("yt:video$index"), uri = "ytm://video$index", path = null)
                val resolved = policy.select(provider)
                assertNull(resolved.issue)
                assertEquals(file.uri, resolved.track.uri)
                assertEquals(provider.uri, SavedSong.from(resolved.track).online()?.uri)
            }
            assertEquals(size, library.songs.size)
        }
    }
}
