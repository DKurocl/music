package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class ArtworkPlanTest {
    @Test fun aspectRatioIsPreservedAndSmallImagesAreNeverUpscaled() {
        assertEquals(192 to 96, ArtworkPlan.fit(1000, 500, ArtworkPlan.SMALL))
        assertEquals(512 to 256, ArtworkPlan.fit(1000, 500, ArtworkPlan.MEDIUM))
        assertEquals(1000 to 500, ArtworkPlan.fit(1000, 500, ArtworkPlan.LARGE))
        assertEquals(640 to 1280, ArtworkPlan.fit(4000, 8000, ArtworkPlan.LARGE))
    }
    @Test fun unreasonableHeadersAndArbitraryOutputSizesAreRejected() {
        for ((width, height) in listOf(0 to 1, -1 to 100, 100000 to 100000))
            assertThrows(IllegalArgumentException::class.java) { ArtworkPlan.fit(width, height, ArtworkPlan.LARGE) }
        assertThrows(IllegalArgumentException::class.java) { ArtworkPlan.fit(100, 100, 100000) }
    }
    @Test fun folderNamingIsPredictableAndAmbiguityIsNeverChosen() {
        assertEquals("cover.jpg", ArtworkPlan.folderFile(listOf("folder.png", "cover.jpg")))
        assertEquals("COVER.PNG", ArtworkPlan.folderFile(listOf("COVER.PNG")))
        assertNull(ArtworkPlan.folderFile(listOf("Cover.jpg", "COVER.JPG")))
        assertNull(ArtworkPlan.folderFile(listOf("photo.jpg")))
    }
}
