package ua.olex.music.tags

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Recoverable provider rewrite. The app supplies a path scoped to one stable MediaStore row. */
class TagRewriteStage(private val pending: File) {
    val exists get() = pending.isFile

    fun prepare(source: FileChannel, plan: TagRewrite, checkActive: () -> Unit) {
        check(!exists)
        val size = source.size()
        require(plan.start in 0..plan.keepFrom && plan.keepFrom <= size)
        val directory = requireNotNull(pending.parentFile)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory)
            throw IOException("Cannot create tag rewrite stage")
        val temp = File(directory, pending.name + ".tmp")
        try {
            RandomAccessFile(temp, "rw").channel.use { stage ->
                stage.truncate(0)
                stage.position(HEADER_SIZE)
                val original = sha256()
                val updated = sha256()
                val buffer = ByteBuffer.allocate(CHUNK)
                source.consume(0, plan.start, buffer, checkActive) {
                    original.update(it.array(), 0, it.remaining())
                    updated.update(it.array(), 0, it.remaining())
                    FileCopy.writeFully(stage, it)
                }
                source.consume(plan.start, plan.keepFrom, buffer, checkActive) { original.update(it.array(), 0, it.remaining()) }
                updated.update(plan.head)
                FileCopy.writeFully(stage, ByteBuffer.wrap(plan.head))
                source.consume(plan.keepFrom, size, buffer, checkActive) {
                    original.update(it.array(), 0, it.remaining())
                    updated.update(it.array(), 0, it.remaining())
                    FileCopy.writeFully(stage, it)
                }
                val header = ByteBuffer.allocate(HEADER_SIZE.toInt()).putInt(MAGIC).put(original.digest()).put(updated.digest())
                header.flip()
                FileCopy.writeFully(stage, header, 0)
                val channel = ChannelSource(stage)
                val media = object : ByteSource {
                    override val size get() = channel.size - HEADER_SIZE
                    override fun read(position: Long, into: ByteArray, offset: Int, length: Int) =
                        channel.read(position + HEADER_SIZE, into, offset, length)
                }
                if (TagReader.read(media)?.codec == null) throw IOException("Rewritten file has no readable audio")
                stage.force(true)
            }
            checkActive()
            if (!temp.renameTo(pending)) throw IOException("Cannot publish tag rewrite stage")
        } finally {
            temp.delete()
        }
    }

    /** A fresh request can finish only its unchanged original or a prefix of its pending result. */
    fun apply(current: FileChannel, output: FileChannel, checkActive: () -> Unit) {
        RandomAccessFile(pending, "r").channel.use { staged ->
            val header = ByteBuffer.allocate(HEADER_SIZE.toInt())
            staged.readExactly(header, 0)
            header.flip()
            if (header.int != MAGIC) throw IOException("Invalid tag rewrite stage")
            val original = ByteArray(32).also(header::get)
            val expected = ByteArray(32).also(header::get)
            val stagedBytes = staged.size() - HEADER_SIZE
            val actualBytes = current.size()
            val buffer = ByteBuffer.allocate(CHUNK)
            if (!staged.digest(HEADER_SIZE, staged.size(), buffer, checkActive).contentEquals(expected))
                throw IOException("Damaged tag rewrite stage")

            val currentDigest = current.digest(0, actualBytes, buffer, checkActive)
            if (actualBytes == stagedBytes && currentDigest.contentEquals(expected)) {
                pending.delete()
                return
            }
            if (!currentDigest.contentEquals(original)) {
                val resultPrefix = actualBytes < stagedBytes &&
                    currentDigest.contentEquals(staged.digest(HEADER_SIZE, HEADER_SIZE + actualBytes, buffer, checkActive))
                // The file changed some other way (another tagger, a replaced copy): the stage
                // belongs to bytes that no longer exist, so drop it and let the new edit go on
                // instead of refusing every later edit of this file.
                if (!resultPrefix) { pending.delete(); return }
            }
            checkActive()
            // A killed or failed write still has its synced stage. The next explicit edit can
            // complete it only after proving the provider bytes are unchanged or an exact prefix.
            output.truncate(0)
            output.position(0)
            FileCopy.range(staged, output, HEADER_SIZE, staged.size())
            output.force(true)
            if (current.size() != stagedBytes || !current.digest(0, stagedBytes, buffer, checkActive).contentEquals(expected))
                throw IOException("Incomplete provider tag write")
            pending.delete()
        }
    }

    private companion object {
        const val MAGIC = 0x4D545231 // MTR1
        const val HEADER_SIZE = 68L
        const val CHUNK = 64 * 1024
        fun sha256() = MessageDigest.getInstance("SHA-256")

        fun FileChannel.readExactly(buffer: ByteBuffer, start: Long) {
            var position = start
            while (buffer.hasRemaining()) {
                val read = read(buffer, position)
                if (read <= 0) throw IOException("Incomplete tag rewrite input")
                position += read
            }
        }

        inline fun FileChannel.consume(start: Long, end: Long, buffer: ByteBuffer, checkActive: () -> Unit, onBytes: (ByteBuffer) -> Unit) {
            var position = start
            while (position < end) {
                checkActive()
                val count = minOf(buffer.capacity().toLong(), end - position).toInt()
                buffer.clear()
                buffer.limit(count)
                readExactly(buffer, position)
                buffer.flip()
                onBytes(buffer)
                position += count
            }
        }

        fun FileChannel.digest(start: Long, end: Long, buffer: ByteBuffer, checkActive: () -> Unit): ByteArray {
            val hash = sha256()
            consume(start, end, buffer, checkActive) { hash.update(it.array(), 0, it.remaining()) }
            return hash.digest()
        }
    }
}
