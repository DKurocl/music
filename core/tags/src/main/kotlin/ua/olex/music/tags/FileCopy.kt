package ua.olex.music.tags

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

object FileCopy {
    /** Copy exactly the requested bytes. Some Android file providers cannot use zero-copy. */
    fun range(source: FileChannel, target: FileChannel, start: Long, end: Long) {
        require(start >= 0 && end >= start)
        if (end > source.size()) throw EOFException("The source became shorter before copying")
        var position = start
        var fallback: ByteBuffer? = null
        while (position < end) {
            val moved = source.transferTo(position, end - position, target)
            if (moved > 0) {
                position += moved
            } else {
                val buffer = fallback ?: ByteBuffer.allocate(64 * 1024).also { fallback = it }
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), end - position).toInt())
                val read = source.read(buffer, position)
                if (read <= 0) throw EOFException("The source stopped before the requested range")
                buffer.flip()
                writeFully(target, buffer)
                position += read
            }
        }
    }

    fun writeFully(target: FileChannel, bytes: ByteBuffer, offset: Long? = null) {
        var position = offset
        while (bytes.hasRemaining()) {
            val written = if (position == null) target.write(bytes) else target.write(bytes, position)
            if (written <= 0) throw IOException("The destination stopped accepting bytes")
            if (position != null) position += written
        }
    }
}
