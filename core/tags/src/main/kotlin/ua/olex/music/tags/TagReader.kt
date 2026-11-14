package ua.olex.music.tags

/**
 * Reads what a music file says about itself, by its content (never by its name): FLAC, MP3,
 * Ogg Vorbis, Opus, M4A (AAC, ALAC) and WAV. Only headers and tags are read; pictures are returned
 * as byte ranges and the audio itself is not touched, so a scan costs a few small reads per file.
 *
 * A damaged or cut file gives what could be read; a file that is not one of these gives null.
 * Nothing here throws on bad input.
 */
object TagReader {
    fun read(source: ByteSource): AudioTags? = try {
        val r = Reader(source)
        if (r.size < 12) null else {
            val id3 = Id3.sizeAt(r, 0)
            val magic = r.ascii(id3.coerceAtMost(r.size - 4), 4)
            when {
                magic == "fLaC" -> Flac.read(r, id3)
                magic == "OggS" -> Ogg.read(r)
                magic == "RIFF" -> Wav.read(r)
                id3 == 0L && r.ascii(4, 4) == "ftyp" -> Mp4.read(r)
                else -> mp3(r, id3)
            }
        }
    } catch (_: Truncated) {
        null
    } catch (_: RuntimeException) {
        // A parser bug must not stop a scan of ten thousand files: this file falls back to what the system knows.
        null
    }

    private fun mp3(r: Reader, id3: Long): AudioTags? {
        val fields = Fields()
        val pictures = ArrayList<Picture>()
        if (id3 > 0) Id3.read(r, 0, fields, pictures)
        val hasV1 = r.size >= 128 && r.ascii(r.size - 128, 3) == "TAG"
        if (hasV1) Id3.readV1(r, fields)
        return Mpeg.read(r, id3, r.size - if (hasV1) 128 else 0, fields, pictures)
    }
}
