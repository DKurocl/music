package ua.olex.music.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TagCoverReaderTest {
    @Test fun acceptsAValidSizedCoverAndRejectsEmptyOrUnsupportedFiles() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, readTagCover(ByteArrayInputStream(bytes), "image/png")?.bytes)
        assertNull(readTagCover(ByteArrayInputStream(byteArrayOf()), "image/png"))
        val rejected = object : InputStream() {
            override fun read() = error("An unsupported format should not be read")
        }
        assertNull(readTagCover(rejected, "image/gif"))
    }

    @Test fun anOversizedProviderStreamIsReadOnlyUpToTheLimitAndOneByte() {
        var read = 0
        val unending = object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(1, offset, offset + length)
                read += length
                return length
            }
        }
        assertNull(readTagCover(unending, "image/jpeg"))
        assertEquals(8 * 1024 * 1024 + 1, read)
    }
}
