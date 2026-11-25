package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's file plays instead of the YouTube Music copy of the same recording. */
class OwnedFileWinsTest {
    private val file = Track(TrackId("local:7"), "content://media/7", "Pulsewidth", "Aphex Twin", album = "Selected Ambient Works 85-92",
        durationMs = 227_000, path = "Music/Aphex Twin/SAW/04 Pulsewidth.flac", format = AudioFormat("flac", 44_100, 16))
    private fun online(ms: Long, artist: String = "Aphex Twin") =
        Track(TrackId("yt:abcdefghijk"), "ytm://abcdefghijk", "Pulsewidth", artist, album = "Selected Ambient Works 85-92", durationMs = ms)
    private fun pick(request: Track, proposed: Track? = file, saved: List<SavedSong> = emptyList()) =
        PlaybackSources(LibraryIndex.build(listOf(file)), saved).select(request, allowDirect = true, proposedLocal = proposed).track.uri

    @Test fun anUnknownLengthNeedsMoreThanAProposedFile() = assertEquals(online(0).uri, pick(online(0)))
    @Test fun aFileWinsOverASeveralArtistCredit() = assertEquals(file.uri, pick(online(228_000, "Aphex Twin, Someone")))
    @Test fun historyWithoutAFilesLinkOrDurationCannotConfirmAnUnknownTake() =
        assertEquals(online(0).uri, pick(online(0), saved = listOf(SavedSong.from(online(0)).copy(createdAt = 1L))))

    @Test fun aConfirmedLocalLinkWorksEvenWhenTheCurrentProviderLengthIsMissing() {
        val known = SavedSong.from(SavedSong.from(online(227_000)).bind(file)).copy(id = "recording:linked")
        assertEquals(file.uri, pick(online(0), saved = listOf(known)))
    }
}
