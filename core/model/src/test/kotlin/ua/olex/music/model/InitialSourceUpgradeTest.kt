package ua.olex.music.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialSourceUpgradeTest {
    private val original = Track(TrackId("yt:exact"), "ytm://exact", "Song", "Artist", durationMs = 210_000)
    private val file = original.copy(id = TrackId("local:one"), uri = "content://local/one",
        path = "Music/song.flac", durationMs = 210_300, addedAt = 8_000)
    private val local = SavedSong.from(original).bind(file).copy(requestedId = original.id.value)

    @Test fun aVerifiedCopyThatExistedBeforeTheTapCanReplaceTheStream() {
        assertTrue(InitialSourceUpgrade.eligible(original, local, 10_500))
        assertTrue(InitialSourceUpgrade.eligible(original.copy(durationMs = 0), local, 10_500))
    }

    @Test fun timestampsInTheSameSecondOrAfterTheTapAreIneligible() {
        for (stamp in listOf(null, 0L, 10_000L, 11_000L)) {
            assertFalse(InitialSourceUpgrade.eligible(original, local.copy(addedAt = stamp), 10_500))
        }
    }

    @Test fun differentVideosClipsAndTimelinesAreIneligible() {
        assertFalse(InitialSourceUpgrade.eligible(original.copy(uri = "ytm://other"), local, 10_500))
        assertFalse(InitialSourceUpgrade.eligible(original, local.copy(clipStartMs = 0, clipEndMs = 210_300), 10_500))
        assertFalse(InitialSourceUpgrade.eligible(original, local.copy(durationMs = 211_500), 10_500))
        assertFalse(InitialSourceUpgrade.eligible(original.copy(durationMs = 208_900), local, 10_500))
    }
}
