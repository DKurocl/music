package ua.olex.music.model

/**
 * A cue sheet: one audio file that holds a whole album, described as its tracks. Read from a
 * `.cue` beside the file or from a FLAC's embedded CUESHEET tag. Times are INDEX 01 positions
 * (mm:ss:ff, 75 frames a second); a track ends where the next one starts.
 */
data class CueSheet(val title: String?, val performer: String?, val genre: String?, val date: String?, val tracks: List<CueTrack>) {
    /**
     * The album's tracks as library tracks cut from [file]: same file, own title and performer,
     * start and end in milliseconds. Track ids are "<file id>#<number>" so each has its own place in
     * queues and history.
     */
    fun split(file: Track): List<Track> {
        val usable = tracks.sortedBy { it.startMs }
        return usable.mapIndexed { i, t ->
            val end = usable.getOrNull(i + 1)?.startMs ?: file.durationMs.takeIf { it > t.startMs }
            file.copy(
                id = TrackId("${file.id.value}#${t.number}"),
                title = t.title ?: "Track ${t.number}",
                artist = t.performer ?: performer ?: file.artist,
                artists = listOf(t.performer ?: performer ?: file.artist),
                album = title ?: file.album,
                albumArtist = performer ?: file.albumArtist,
                trackNumber = t.number,
                year = date?.take(4)?.toIntOrNull() ?: file.year,
                genre = genre ?: file.genre,
                genres = listOfNotNull(genre ?: file.genre),
                durationMs = if (end != null) end - t.startMs else 0,
                clipStartMs = t.startMs,
                clipEndMs = end,
            )
        }
    }

    companion object {
        private val TIME = Regex("""(\d+):(\d{1,2}):(\d{1,2})""")

        /** Parses cue text; null when it has no usable tracks. Tolerant of case, quotes and odd spacing. */
        fun parse(text: String): CueSheet? {
            var title: String? = null; var performer: String? = null; var genre: String? = null; var date: String? = null
            val tracks = ArrayList<CueTrack>()
            var current: CueTrack? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim().trimStart('﻿')
                if (line.isEmpty()) continue
                val word = line.substringBefore(' ').uppercase()
                val rest = line.substringAfter(' ', "").trim()
                fun value() = rest.trim().removeSurrounding("\"")
                when (word) {
                    "TRACK" -> { current?.let(tracks::add); current = CueTrack(rest.substringBefore(' ').toIntOrNull() ?: (tracks.size + 1)) }
                    "TITLE" -> if (current == null) title = value() else current = current.copy(title = value())
                    "PERFORMER" -> if (current == null) performer = value() else current = current.copy(performer = value())
                    "INDEX" -> {
                        val number = rest.substringBefore(' ').toIntOrNull()
                        val m = TIME.find(rest.substringAfter(' '))
                        if (number == 1 && m != null && current != null) {
                            val (mm, ss, ff) = m.destructured
                            current = current.copy(startMs = mm.toLong() * 60_000 + ss.toLong() * 1_000 + ff.toLong() * 1000 / 75)
                        }
                    }
                    "REM" -> {
                        val key = rest.substringBefore(' ').uppercase()
                        val v = rest.substringAfter(' ', "").trim().removeSurrounding("\"")
                        if (current == null) when (key) { "GENRE" -> genre = v; "DATE" -> date = v }
                    }
                }
            }
            current?.let(tracks::add)
            val valid = tracks.filter { it.startMs >= 0 }.distinctBy { it.number }
            return if (valid.size < 2) null else CueSheet(title, performer, genre, date, valid)
        }
    }
}

data class CueTrack(val number: Int, val title: String? = null, val performer: String? = null, val startMs: Long = -1)
