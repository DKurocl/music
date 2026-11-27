package ua.olex.music.soulseek

import ua.olex.music.tags.ByteSource
import ua.olex.music.tags.TagReader

/** Validates formats the local tag reader can identify, using bytes from the staged file. */
internal object DownloadAudioHeader {
    fun matches(extension: String, source: ByteSource): Boolean {
        val codecs = when (extension) {
            "flac" -> setOf("flac")
            "wav" -> setOf("pcm", "pcm_float")
            "alac" -> setOf("alac")
            "mp3" -> setOf("mp3")
            // Every codec the MP4 reader can name: AAC, ALAC, and FLAC or Opus in an MP4 box.
            "m4a" -> setOf("aac", "alac", "flac", "opus")
            "aac" -> setOf("aac")
            "ogg" -> setOf("opus", "vorbis")
            "opus" -> setOf("opus")
            // The scanner cannot identify the remaining offered formats from tags alone.
            else -> return true
        }
        if (extension == "aac" && adtsFrame(source)) return true
        val audio = TagReader.read(source) ?: return false
        return audio.codec in codecs && (audio.sampleRateHz ?: 0) > 0 && (audio.channels ?: 0) > 0
    }

    private fun adtsFrame(source: ByteSource): Boolean {
        val bytes = ByteArray(10)
        fun read(offset: Long, count: Int): Boolean {
            if (offset > source.size - count) return false
            var done = 0
            while (done < count) {
                val n = source.read(offset + done, bytes, done, count - done)
                if (n <= 0) return false
                done += n
            }
            return true
        }
        if (!read(0, 7)) return false
        fun value(index: Int) = bytes[index].toInt() and 0xff
        var offset = 0L
        if (value(0) == 'I'.code && value(1) == 'D'.code && value(2) == '3'.code) {
            if (!read(0, 10) || value(3) !in 2..4 || (6..9).any { value(it) > 127 }) return false
            val length = (6..9).fold(0L) { size, i -> (size shl 7) or value(i).toLong() }
            val footer = if (value(3) == 4 && (value(5) and 0x10) != 0) 10 else 0
            offset = 10 + length + footer
            if (!read(offset, 7)) return false
        }
        if (value(0) != 0xff || (value(1) and 0xf6) != 0xf0) return false
        val sampleRate = (value(2) shr 2) and 0x0f
        val header = if ((value(1) and 1) == 0) 9 else 7
        val length = ((value(3) and 3) shl 11) or (value(4) shl 3) or (value(5) shr 5)
        return sampleRate <= 12 && length > header && length <= source.size - offset
    }
}
