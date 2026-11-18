package ua.olex.music.user

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: UserDatabase
    private lateinit var repository: UserRepository
    private var time = 1_000L
    private val a = track(1)
    private val b = track(2)
    private val c = track(3)

    @Before fun open() {
        context.deleteDatabase("user-test.db")
        reopen()
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, UserDatabase::class.java, "user-test.db").build()
        repository = UserRepository(database) { time++ }
    }

    @After fun close() { database.close(); context.deleteDatabase("user-test.db") }

    @Test fun catalogueIncludesSavedOnlineSongsBeyondRecentHistoryAndSurvivesReopen() = runBlocking {
        val online = a.copy(id = TrackId("yt:online"), uri = "ytm://online", path = null)
        repository.recordPlay(online)
        repeat(201) { repository.recordPlay(b) }
        repository.toggleFavourite(c)
        val playlist = repository.createPlaylist("Saved", listOf(a))
        assertFalse(repository.history.first().any { it.song.path == "ytm://online" })
        assertEquals(3, repository.catalogue.first().size)
        database.close()
        reopen()
        val recordingId = idOf(a)
        assertTrue(repository.catalogue.first().single { it.id == recordingId }.references.any { it.uri == "ytm://online" })
        repository.deletePlaylist(playlist)
        repository.toggleFavourite(c)
        assertEquals(setOf(idOf(online), idOf(b)), repository.catalogue.first().map { it.id }.toSet())
    }

    @Test fun undoRestoresTheOccurrenceUnlessAnotherEditChangedTheOrder() = runBlocking {
        val id = repository.createPlaylist("Loop", listOf(a, b))
        repository.addToPlaylist(id, listOf(a), duplicates = true)
        val removed = requireNotNull(repository.removeFromPlaylist(id, idOf(a), position = 0))
        assertTrue(repository.restorePlaylistOccurrence(removed))
        assertEquals(listOf("Song 1", "Song 2", "Song 1"), names())

        val stale = requireNotNull(repository.removeFromPlaylist(id, idOf(a), position = 2))
        repository.addToPlaylist(id, listOf(c))
        assertFalse(repository.restorePlaylistOccurrence(stale))
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), names())
    }

    @Test fun duplicatesOnPurposeAndPositionalEdits() = runBlocking {
        val id = repository.createPlaylist("Loop", listOf(a))
        repository.addToPlaylist(id, listOf(b, a), duplicates = true)
        assertEquals(listOf("Song 1", "Song 2", "Song 1"), names())
        repository.removeFromPlaylist(id, idOf(a), position = 2)
        assertEquals(listOf("Song 1", "Song 2"), names())
        repository.addToPlaylist(id, listOf(a), duplicates = true)
        repository.moveInPlaylist(id, idOf(a), -1, position = 2)
        assertEquals(listOf("Song 1", "Song 1", "Song 2"), names())
        assertEquals(listOf(0, 1, 2), database.userData().entries(id).map { it.position })
    }

    @Test fun playlistEditsPreserveOrderAndDeduplicateAdds() = runBlocking {
        val id = repository.createPlaylist("  Evening  ", listOf(a, b, a))
        repository.addToPlaylist(id, listOf(b, c))
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), names())
        repository.moveInPlaylist(id, idOf(c), -1)
        assertEquals(listOf("Song 1", "Song 3", "Song 2"), names())
        repository.removeFromPlaylist(id, idOf(a))
        repository.addToPlaylist(id, listOf(a))
        assertEquals(listOf("Song 3", "Song 2", "Song 1"), names())
        repository.renamePlaylist(id, " Night ")
        assertEquals("Night", repository.playlists.first().single().name)
        assertEquals(listOf(0, 1, 2), database.userData().entries(id).map { it.position })
    }

    @Test fun metadataRefreshAndPlaylistDeletionCannotEraseFavouritesHistoryOrOffsets() = runBlocking {
        repository.toggleFavourite(a)
        repository.setLyricOffset(a, -250)
        val id = repository.createPlaylist("Evening", listOf(a))
        repository.recordPlay(a.copy(album = "Updated album"))
        repository.deletePlaylist(id)
        assertTrue(repository.playlists.first().isEmpty())
        assertEquals("Updated album", repository.favourites.first().single().album)
        assertEquals(1, repository.history.first().size)
        assertEquals(-250L, repository.lyricOffset(SavedSong.from(a)).first())
    }

    @Test fun allPersonalDataSurvivesDatabaseReopen() = runBlocking {
        repository.createPlaylist("Travel", listOf(b, a))
        repository.toggleFavourite(a)
        repository.recordPlay(a)
        repository.recordPlay(a)
        repository.setLyricOffset(a, 750)
        database.close()
        reopen()
        assertEquals(listOf("Song 2", "Song 1"), names())
        assertEquals(idOf(a), repository.favourites.first().single().id)
        assertEquals(2, repository.history.first().size)
        assertEquals(750L, repository.lyricOffset(SavedSong.from(a)).first())
    }

    @Test fun movedFavouriteCanBeRemovedUsingItsOriginalReference() = runBlocking {
        assertTrue(repository.toggleFavourite(a))
        assertFalse(repository.toggleFavourite(a.copy(path = "Moved/song.flac"), idOf(a)))
        assertTrue(repository.favourites.first().isEmpty())
    }

    @Test fun invalidNameRollsBackWithoutAnEmptyPlaylist() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.createPlaylist("   ", listOf(a)) } }
        runBlocking { assertTrue(repository.playlists.first().isEmpty()) }
    }

    @Test fun importedMissingReferencesAndOrderSurviveReopen() = runBlocking {
        val missing = ua.olex.music.model.M3uPlaylist.resolve(
            ua.olex.music.model.M3uPlaylist.parse("#EXTINF:120,Artist - Missing\nMusic/missing.flac"),
            ua.olex.music.model.LibraryIndex.EMPTY).songs.single()
        val saved = SavedSong.from(a)
        repository.importPlaylist(" Imported ", listOf(missing, saved, missing))
        val expected = repository.playlists.first().single().songs
        database.close(); reopen()
        val playlist = repository.playlists.first().single()
        assertEquals("Imported", playlist.name)
        assertEquals(expected, playlist.songs)
        assertEquals(listOf(missing.path, saved.path, missing.path), playlist.songs.map { it.path })
        assertEquals(playlist.songs[0].id, playlist.songs[2].id)
    }

    @Test fun largeImportsAreBatchedWithoutLosingPositionsOrFavourites() = runBlocking {
        repository.toggleFavourite(a)
        val songs = (1..1201).map { SavedSong.from(track(it)) }
        val id = repository.importPlaylist("Large", songs)
        assertEquals(songs.map { it.path }, repository.playlists.first().single().songs.map { it.path })
        assertEquals(1201, repository.playlists.first().single().songs.map { it.id }.distinct().size)
        assertEquals((0..1200).toList(), database.userData().entries(id).map { it.position })
        assertEquals(idOf(a), repository.favourites.first().single().id)
        assertEquals(a.path, repository.favourites.first().single().path)
    }

    @Test fun invalidImportsDoNotLeaveEmptyPlaylistHeaders() {
        for (songs in listOf(emptyList(), listOf(SavedSong.from(a).copy(path = "", sources = emptyList())))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.importPlaylist("Invalid", songs) } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.importPlaylist(" ", listOf(SavedSong.from(a))) } }
        runBlocking { assertTrue(repository.playlists.first().isEmpty()) }
    }

    @Test fun unsupportedSchemaNeverDestructivelyRebuildsUserData() = runBlocking {
        repository.createPlaylist("Keep this", listOf(a))
        database.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("user-test.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 } // a version from the future
        reopen()
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.playlists.first() } }
        database.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("user-test.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM playlists", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Keep this", cursor.getString(0))
            }
        }
    }

    private suspend fun idOf(track: Track) = repository.reference(track).first()!!.id
    private suspend fun names() = repository.playlists.first().single().songs.map { it.title }
    private fun track(id: Int) = Track(TrackId("local:$id"), "content://$id", "Song $id", "Artist", path = "Music/$id.flac", durationMs = 120_000)

    @Test fun ratingsAndPlayCountsFollowTheSongAcrossCopies() = runBlocking {
        repository.recordPlay(a); repository.recordPlay(a); repository.recordPlay(b)
        repository.setRating(a, 4)
        var stats = repository.stats.first()
        assertEquals(2, stats.getValue(a.key.value).plays)
        assertEquals(4, stats.getValue(a.key.value).rating)
        assertEquals(1, stats.getValue(b.key.value).plays)
        repository.setRating(a, 2)
        assertEquals("a new rating replaces the old one", 2, repository.stats.first().getValue(a.key.value).rating)
        repository.setRating(a, 0)
        stats = repository.stats.first()
        assertEquals("0 clears it; plays stay", 0, stats.getValue(a.key.value).rating)
        assertEquals(2, stats.getValue(a.key.value).plays)
    }

    @Test fun recordingIdAndOnlineReserveSurviveLocalPreferenceAndReopen() = runBlocking {
        val online = a.copy(id = TrackId("yt:reserve"), uri = "ytm://reserve", path = null, artworkUri = "https://online/cover")
        repository.toggleFavourite(online)
        val id = idOf(online)
        assertTrue(id.startsWith("recording:"))
        repository.recordPlay(a.copy(artworkUri = "content://local/cover"))
        repository.setRating(a, 5)
        repository.setLyricOffset(a, 321)
        assertEquals(id, idOf(a))
        database.close(); reopen()
        val song = repository.favourites.first().single()
        assertEquals(id, song.id)
        assertEquals(a.path, song.path)
        assertEquals(2, song.references.size)
        val fallback = song.resolve(ua.olex.music.model.LibraryIndex.EMPTY)!!
        assertEquals("ytm://reserve", fallback.uri)
        assertEquals("https://online/cover", fallback.artworkUri)
        assertEquals(id, fallback.recordingId)
        assertEquals(5, repository.stats.first().getValue(id).rating)
        assertEquals(1, repository.stats.first().getValue(id).plays)
        assertEquals(321L, repository.lyricOffset(SavedSong.from(fallback)).first())
    }

    @Test fun unknownDurationsAndDifferentTakesKeepSeparateSettings() = runBlocking {
        val unknown = a.copy(id = TrackId("yt:unknown"), uri = "ytm://unknown", path = null, durationMs = 0)
        val live = a.copy(id = TrackId("yt:live"), uri = "ytm://live", path = null, title = "Song 1 (Live)")
        repository.setRating(a, 5)
        repository.setRating(unknown, 2)
        repository.setRating(live, 3)
        assertEquals(3, repository.catalogue.first().size)
        assertEquals(setOf(5, 2, 3), listOf(a, unknown, live).map { repository.stats.first().getValue(idOf(it)).rating }.toSet())
        assertFalse(repository.stats.first().containsKey(a.key.value))
        repository.setRating(unknown, 0)
        assertEquals(5, repository.stats.first().getValue(idOf(a)).rating)
    }

    @Test fun aConfirmedLegacyMergeKeepsEveryOccurrenceEventAndLatestSetting() = runBlocking {
        val online = a.copy(id = TrackId("yt:old"), uri = "ytm://old", path = null)
        val file = SavedSong.from(a).copy(id = "a-legacy", createdAt = 0)
        val remote = SavedSong.from(online).copy(id = "b-legacy", createdAt = 0)
        val dao = database.userData()
        dao.saveSongs(listOf(UserSongEntity.from(file), UserSongEntity.from(remote)))
        dao.saveAliases(listOf(RecordingAliasEntity(file.id, file.id), RecordingAliasEntity(remote.id, remote.id)))
        val playlist = dao.createPlaylist(PlaylistEntity(name = "Repeated", createdAt = 1))
        dao.insertEntries(listOf(file.id, remote.id, file.id).mapIndexed { index, id -> PlaylistEntryEntity(playlist, id, index) })
        dao.addHistory(HistoryEntity(id = 10, songId = file.id, playedAt = 100))
        dao.addHistory(HistoryEntity(id = 11, songId = remote.id, playedAt = 200))
        dao.addFavourite(FavouriteEntity(remote.id, 300))
        dao.setRating(RatingEntity(file.id, 5, 100)); dao.setRating(RatingEntity(remote.id, 2, 200))
        dao.setOffset(LyricOffsetEntity(file.id, 500, 400)); dao.setOffset(LyricOffsetEntity(remote.id, 100, 200))
        val merged = repository.keepReference(online)
        assertEquals(file.id, merged.id)
        assertEquals(listOf(file.id, file.id, file.id), dao.entries(playlist).map { it.songId })
        assertEquals(listOf(11L, 10L), repository.history.first().map { it.id })
        assertEquals(listOf(200L, 100L), repository.history.first().map { it.playedAt })
        assertEquals(2, repository.stats.first().getValue(file.id).plays)
        assertEquals(2, repository.stats.first().getValue(file.id).rating)
        assertEquals(500L, repository.lyricOffset(remote).first())
        assertEquals(file.id, dao.canonicalId(remote.id))
        assertEquals(1, dao.allRecordings().size)
        repository.removeFromPlaylist(playlist, remote.id, position = 1)
        assertEquals(listOf(0, 1), dao.entries(playlist).map { it.position })
        repository.removeFavourite(remote.id)
        assertTrue(repository.favourites.first().isEmpty())
    }

    @Test fun stalePositionDoesNotRemoveOrMoveAnotherOccurrence() = runBlocking {
        val id = repository.createPlaylist("Order", listOf(a, b))
        repository.addToPlaylist(id, listOf(a), duplicates = true)
        repository.removeFromPlaylist(id, idOf(a), position = 1)
        repository.moveInPlaylist(id, idOf(a), 1, position = 1)
        assertEquals(listOf("Song 1", "Song 2", "Song 1"), names())
    }

    @Test fun staleQueueReserveCannotOverwriteFreshPrimarySource() = runBlocking {
        val old = a.copy(id = TrackId("yt:one"), uri = "ytm://one", path = null, artworkUri = "https://old")
        val initial = repository.keepReference(old)
        val linkedFile = initial.bind(a)
        repository.keepReference(old.copy(artworkUri = "https://fresh"))
        repository.keepReference(linkedFile)
        assertEquals("https://fresh", repository.reference(a).first()!!.online()!!.artworkUri)
    }

    @Test fun unchangedRegistrationDoesNotInvalidateTheCatalogue() = runBlocking {
        repository.keepReference(a)
        fun changes(): Long = database.openHelper.writableDatabase.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
        val before = changes()
        repeat(3) { repository.keepReference(a) }
        assertEquals(before, changes())
    }


    @Test fun scanReconciliationKeepsAReserveAndDoesNotWriteOnItsOwnFeedback() = runBlocking {
        val online = a.copy(id = TrackId("yt:scan"), uri = "ytm://scan", path = null)
        repository.toggleFavourite(online)
        val id = idOf(online)
        val index = ua.olex.music.model.LibraryIndex.build(listOf(a, b))
        repository.reconcile(index, repository.catalogue.first())
        val first = repository.catalogue.first()
        assertEquals(1, first.size)
        assertEquals(id, first.single().id)
        assertEquals(setOf("ytm://scan", a.uri), first.single().references.map { it.uri }.toSet())
        fun changes(): Long = database.openHelper.writableDatabase.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
        val before = changes()
        repository.reconcile(index, first)
        assertEquals(before, changes())
        assertEquals("ytm://scan", first.single().resolve(ua.olex.music.model.LibraryIndex.EMPTY)!!.uri)
    }


    @Test fun latestClearCannotResurrectAnOlderRatingDuringAMerge() = runBlocking {
        val dao = database.userData()
        val legacy = SavedSong.from(a).copy(id = "old-rated", createdAt = 0)
        dao.saveSongs(listOf(UserSongEntity.from(legacy)))
        dao.setRating(RatingEntity(legacy.id, 5, 100))
        val online = a.copy(id = TrackId("yt:clear"), uri = "ytm://clear", path = null)
        val other = SavedSong.from(online).copy(id = "new-cleared", createdAt = 1)
        dao.saveSongs(listOf(UserSongEntity.from(other)))
        dao.setRating(RatingEntity(other.id, 0, 200))
        val recording = repository.keepReference(online)
        assertEquals(legacy.id, recording.id)
        assertEquals(0, repository.stats.first().getValue(recording.id).rating)
        assertTrue(repository.stats.first().getValue(recording.id).ratingSet)
    }


    @Test fun anImportedRelativePathGetsIdentityOnceAndCannotClaimAReplacementTake() = runBlocking {
        val missing = ua.olex.music.model.M3uPlaylist.resolve(
            ua.olex.music.model.M3uPlaylist.parse("#EXTINF:120,Old artist - Old title\nAlbum/1.flac"),
            ua.olex.music.model.LibraryIndex.EMPTY).songs.single()
        repository.importPlaylist("Import", listOf(missing))
        val before = repository.playlists.first().single().songs.single().id
        val arrived = a.copy(path = "Music/Album/1.flac")
        repository.reconcile(ua.olex.music.model.LibraryIndex.build(listOf(arrived)), repository.catalogue.first())
        val saved = repository.playlists.first().single().songs.single()
        assertEquals(before, saved.id)
        assertEquals(a.title, saved.title)
        assertFalse(saved.references.any { it.kind == ua.olex.music.model.RecordingSourceKind.MISSING_LOCAL })
        assertNull(saved.resolve(ua.olex.music.model.LibraryIndex.build(listOf(arrived.copy(title = "Another recording")))))
    }


    @Test fun repeatedAmbiguousFilePlaybackDoesNotCreateANewRecordingEveryTime() = runBlocking {
        val actual = a.copy(durationMs = 202_000)
        val dao = database.userData()
        for ((id, ms) in listOf("take-a" to 200_000L, "take-b" to 205_000L)) {
            val remote = actual.copy(id = TrackId("yt:$id"), uri = "ytm://$id", path = null, durationMs = ms)
            val saved = SavedSong.from(remote).copy(id = id, sources = listOf(
                ua.olex.music.model.RecordingSource.from(actual)!!, ua.olex.music.model.RecordingSource.from(remote)!!))
            dao.saveSongs(listOf(UserSongEntity.from(saved)))
            dao.saveSources(saved.sources.map { RecordingSourceEntity.from(id, it, 0) })
        }
        repeat(3) { repository.recordPlay(actual) }
        val history = repository.history.first()
        assertEquals(3, history.size)
        assertEquals(1, history.map { it.song.id }.distinct().size)
        assertTrue(history.all { it.song.id.startsWith("recording:") })
        assertEquals(3, dao.allRecordings().size)
        repository.recordPlay(actual.copy(recordingId = "take-a"))
        assertEquals("take-a", repository.history.first().first().song.id)
        assertEquals(3, dao.allRecordings().size)
    }


    @Test fun aRatingClearRetainsItsIdentityWithoutCollectingAnUnplayedOnlineSong() = runBlocking {
        val online = a.copy(id = TrackId("yt:unrated"), uri = "ytm://unrated", path = null)
        repository.setRating(online, 0)
        val id = idOf(online)
        assertTrue(repository.catalogue.first().isEmpty())
        repository.setRating(online, 4)
        assertEquals(id, repository.catalogue.first().single().id)
        repository.setRating(online, 0)
        assertTrue(repository.catalogue.first().isEmpty())
        assertEquals(id, idOf(online))
        assertTrue(repository.stats.first().getValue(id).ratingSet)
    }


    @Test fun playbackPolicyUsesUncollectedEvidenceToRejectAnAmbiguousSubstitution() = runBlocking {
        val first = a.copy(id = TrackId("yt:a"), uri = "ytm://a", path = null, durationMs = 200_000)
        val second = first.copy(id = TrackId("yt:b"), uri = "ytm://b", durationMs = 205_000)
        repository.setRating(first, 0); repository.setRating(second, 0)
        assertTrue(repository.catalogue.first().isEmpty())
        val physical = a.copy(durationMs = 202_000)
        val policy = repository.sourcePolicy(ua.olex.music.model.LibraryIndex.build(listOf(physical)))
        assertEquals(first.uri, policy.select(first).track.uri)
        assertEquals(second.uri, policy.select(second).track.uri)
    }


    @Test fun reconciliationAndPreferencesDoNotMergeAnAmbiguousUnresolvedFile() = runBlocking {
        val a = this@UserRepositoryTest.a.copy(id = TrackId("yt:a"), uri = "ytm://a", path = null, durationMs = 200_000)
        val b = a.copy(id = TrackId("yt:b"), uri = "ytm://b", durationMs = 205_000)
        val file = this@UserRepositoryTest.a.copy(durationMs = 202_000)
        repository.toggleFavourite(a); repository.setRating(b, 0)
        repeat(2) { repository.recordPlay(file) }
        val rawId = idOf(file)
        val aId = idOf(a)
        val weak = repository.reference(a).first()!!.bind(file)
        repository.setRating(weak, 4)
        assertEquals(3, database.userData().allRecordings().size)
        assertEquals(2, repository.stats.first().getValue(rawId).plays)
        assertEquals(4, repository.stats.first().getValue(aId).rating)
        repository.reconcile(ua.olex.music.model.LibraryIndex.build(listOf(file)), repository.recordings.first())
        assertEquals(3, database.userData().allRecordings().size)
        val selected = repository.sourcePolicy(ua.olex.music.model.LibraryIndex.build(listOf(file))).select(a)
        assertEquals(a.uri, selected.track.uri)
        repository.recordPlay(selected.track)
        assertEquals(aId, repository.history.first().first().song.id)
        assertEquals(2, repository.stats.first().getValue(rawId).plays)
    }

}
