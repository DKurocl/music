package ua.olex.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsCleanupTest {
    @Test fun dropsHeadingCreditsAndTranslations() {
        val lyrics = Lyrics(listOf(
            Line(0, 1_000, "Eventually - Tame Impala"),
            Line(1_000, 2_000, "作词 : Kevin Parker"),
            Line(2_000, 3_000, "If only there could be another way", translation = "如果有另一种方式"),
            Line(4_000, 5_000, "Eventually"),
        ), Sync.LINE)
        val clean = LyricsCleanup.clean(lyrics, "Eventually", "Tame Impala")
        assertEquals(listOf("If only there could be another way", "Eventually"), clean.lines.map { it.text })
        assertNull(clean.lines.first().translation)
    }

    @Test fun dropsAHeadingWithExtrasAfterTheTitle() {
        val lyrics = Lyrics(listOf(Line(0, 1_000, "'Cause I'm A Man (Explicit) - Tame Impala"), Line(1_000, 2_000, "Like the brutal autumn sun")), Sync.LINE)
        assertEquals(listOf("Like the brutal autumn sun"), LyricsCleanup.clean(lyrics, "'Cause I'm A Man", "Tame Impala").lines.map { it.text })
    }

    @Test fun keepsAFirstLineThatIsOnlyTheTitle() {
        val lyrics = Lyrics(listOf(Line(0, 1_000, "Eventually"), Line(1_000, 2_000, "Next")), Sync.LINE)
        assertEquals(2, LyricsCleanup.clean(lyrics, "Eventually", "Tame Impala").lines.size)
    }
}
