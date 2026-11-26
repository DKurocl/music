package ua.olex.music.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

class FileCopyTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun exactRangesAndPositionedWritesPreserveUnchangedBytes() {
        val source = files.newFile().apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4)) }
        val target = files.newFile()
        FileChannel.open(source.toPath(), READ).use { input ->
            FileChannel.open(target.toPath(), WRITE).use { output ->
                FileCopy.range(input, output, 1, 5)
                FileCopy.writeFully(output, ByteBuffer.wrap(byteArrayOf(7, 8)), 1)
            }
        }
        assertArrayEquals(byteArrayOf(1, 7, 8, 4), target.readBytes())
    }

    @Test fun aShrunkSourceFailsBeforeTouchingTheTarget() {
        val source = files.newFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val target = files.newFile().apply { writeBytes(byteArrayOf(7, 8)) }
        FileChannel.open(source.toPath(), READ).use { input ->
            FileChannel.open(target.toPath(), WRITE).use { output ->
                assertThrows(EOFException::class.java) { FileCopy.range(input, output, 0, 3) }
            }
        }
        assertArrayEquals(byteArrayOf(7, 8), target.readBytes())
    }
}
