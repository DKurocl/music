package ua.olex.music

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.model.RadioTeacherGraph
import ua.olex.music.model.RadioTeacherPage
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioTeacherStoreTest {
    @get:Rule val files = TemporaryFolder()

    private fun graph(seed: String, neighbour: String): RadioTeacherGraph {
        fun video(id: String) = Track(TrackId("yt:$id"), "ytm://$id", "", "")
        val page = requireNotNull(RadioTeacherPage.verify(seed, listOf(video(seed), video(neighbour))))
        return RadioTeacherGraph().observe(page, 1_000)
    }

    @Test fun personalEvidenceIsPartitionedAndPublicEvidenceSurvivesAccountReplacement() {
        val owner = graph("first000001", "first000002")
        val public = graph("world000001", "world000002")
        val replacement = graph("other000001", "other000002")
        RadioTeacherStore(files.root).apply {
            write("old-account", owner)
            write("public", public)
        }
        RadioTeacherStore(files.root).apply {
            assertEquals(owner, get("old-account"))
            assertEquals(RadioTeacherGraph(), get("new-account"))
            assertEquals(public, get("public"))
            write("new-account", replacement)
        }
        RadioTeacherStore(files.root).apply {
            assertEquals(replacement, get("new-account"))
            assertEquals(RadioTeacherGraph(), get("old-account"))
            assertEquals(public, get("public"))
        }
    }

    @Test fun invalidSnapshotCannotAffectAnotherPartitionAndNextCaptureRepairsIt() {
        val owner = graph("first000001", "first000002")
        val public = graph("world000001", "world000002")
        RadioTeacherStore(files.root).write("public", public)
        files.root.resolve("personal.json").writeText("{not a valid snapshot")
        RadioTeacherStore(files.root).apply {
            assertEquals(RadioTeacherGraph(), get("account"))
            assertEquals(public, get("public"))
            write("account", owner)
        }
        assertEquals(owner, RadioTeacherStore(files.root).get("account"))
    }
}
