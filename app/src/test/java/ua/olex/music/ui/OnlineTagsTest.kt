package ua.olex.music.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class OnlineTagsTest {
    private val json = """{"resultCount":2,"results":[
        {"artistName":"Tame Impala","collectionName":"Currents (Live)","trackName":"Eventually","releaseDate":"2017-01-01T08:00:00Z","trackNumber":3,"discNumber":1,"primaryGenreName":"Alternative","trackTimeMillis":330000,"artworkUrl100":"https://x/100x100bb.jpg"},
        {"artistName":"Tame Impala","collectionName":"Currents","trackName":"Eventually","releaseDate":"2015-07-17T07:00:00Z","trackNumber":6,"discNumber":1,"primaryGenreName":"Alternative","trackTimeMillis":318000,"artworkUrl100":"https://y/100x100bb.jpg"}]}"""

    @Test fun ranksTheRecordingOfTheSameLengthAndAlbumFirstAndAsksForALargeCover() = runBlocking {
        var asked = ""
        val tags = OnlineTags { url -> asked = url; json.toByteArray() }
        val track = Track(TrackId("local:1"), "content://1", "Eventually", "Tame Impala", album = "Currents", durationMs = 318_500)
        val found = tags.search(listOf(track))
        assertEquals("Currents", found.first().album)
        assertEquals(2015, found.first().year)
        assertEquals("https://y/1200x1200bb.jpg", found.first().coverUrl)
        assertEquals("6", found.first().fields(single = true)["TRACKNUMBER"])
        assert(asked.contains("entity=song")) { asked }
    }
}
