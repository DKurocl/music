package ua.olex.music.model

/** Records audible starts once per occurrence; buffering and pause/resume do not inflate history. */
class PlaybackHistoryGate {
    private var recorded: String? = null

    fun transition() { recorded = null }

    fun shouldRecord(occurrence: String?, isPlaying: Boolean): Boolean {
        if (!isPlaying || occurrence.isNullOrBlank() || recorded == occurrence) return false
        recorded = occurrence
        return true
    }
}
