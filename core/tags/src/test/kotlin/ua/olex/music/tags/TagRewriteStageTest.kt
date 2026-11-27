package ua.olex.music.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class TagRewriteStageTest {
    @get:Rule val directory = TemporaryFolder()
    private val audio = ByteArray(417 * 50).also {
        for (offset in it.indices step 417) {
            it[offset] = 0xFF.toByte()
            it[offset + 1] = 0xFB.toByte()
            it[offset + 2] = 0x90.toByte()
        }
    }
    private val plan get() = requireNotNull(TagWriter.plan(ByteArraySource(audio), mapOf("TITLE" to listOf("Resized title"))))

    private fun stage(original: File, pending: File) {
        original.writeBytes(audio)
        RandomAccessFile(original, "r").channel.use { TagRewriteStage(pending).prepare(it, plan) {} }
        assertTrue(pending.isFile)
    }

    private fun apply(original: File, pending: File) =
        RandomAccessFile(original, "rw").channel.use { TagRewriteStage(pending).apply(it, it) {} }

    @Test fun anInterruptedProviderWriteCanBeRecoveredFromItsExactPrefix() {
        val original = directory.newFile()
        val pending = File(directory.root, "track.pending")
        stage(original, pending)
        val expected = plan.head + audio
        original.writeBytes(expected.copyOf(250))
        apply(original, pending)
        assertFalse(pending.exists())
        val rewritten = original.readBytes()
        assertArrayEquals(expected, rewritten)
        assertEquals("Resized title", TagReader.read(ByteArraySource(rewritten))?.title)
    }

    @Test fun aPendingWriteCanAlsoCompleteBeforeTruncationOrAfterAFinishedCopy() {
        val original = directory.newFile()
        val pending = File(directory.root, "track.pending")
        val expected = plan.head + audio
        stage(original, pending)
        apply(original, pending)
        assertArrayEquals(expected, original.readBytes())
        stage(original, pending)
        original.writeBytes(expected)
        apply(original, pending)
        assertArrayEquals(expected, original.readBytes())
        assertFalse(pending.exists())
    }

    @Test fun aDifferentFileAtTheDestinationIsNeverOverwrittenAndItsStaleStageIsDropped() {
        val original = directory.newFile()
        val pending = File(directory.root, "track.pending")
        stage(original, pending)
        val replacement = audio.copyOf().apply { this[lastIndex] = 4 }
        original.writeBytes(replacement)
        apply(original, pending)
        assertFalse(pending.exists())
        assertArrayEquals(replacement, original.readBytes())
    }

    @Test fun aDamagedStageIsRejectedBeforeTheDestinationChanges() {
        val original = directory.newFile()
        val pending = File(directory.root, "track.pending")
        stage(original, pending)
        RandomAccessFile(pending, "rw").use { it.setLength(it.length() - 1) }
        assertThrows(IOException::class.java) { apply(original, pending) }
        assertArrayEquals(audio, original.readBytes())
    }
}
