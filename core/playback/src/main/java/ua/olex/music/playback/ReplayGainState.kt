package ua.olex.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import org.json.JSONObject
import ua.olex.music.dsp.ReplayGainMode
import ua.olex.music.dsp.ReplayGainSettings
import ua.olex.music.dsp.ReplayGainTags

/** Versioned local/session representation. Missing legacy queue tags are neutral, never guessed. */
internal object ReplayGainCodec {
    const val TAGS = "ua.olex.music.replaygain.tags"
    fun encode(settings: ReplayGainSettings) = JSONObject().apply {
        put("version", 1); put("mode", settings.mode.name); put("preamp", settings.preampDb); put("guard", settings.preventClipping)
    }.toString()
    fun decode(text: String): ReplayGainSettings {
        require(text.length <= 1024)
        val row = JSONObject(text); require(row.getInt("version") == 1)
        return ReplayGainSettings(ReplayGainMode.valueOf(row.getString("mode")), row.getDouble("preamp"), row.getBoolean("guard"))
    }
    fun encodeTags(tags: ReplayGainTags) = JSONObject().apply {
        put("track", tags.trackGainDb?.takeIf { it.isFinite() }); put("trackPeak", tags.trackPeak?.takeIf { it.isFinite() })
        put("album", tags.albumGainDb?.takeIf { it.isFinite() }); put("albumPeak", tags.albumPeak?.takeIf { it.isFinite() })
    }.toString()
    fun tags(text: String?): ReplayGainTags {
        if (text == null || text.length > 1024) return ReplayGainTags()
        return runCatching {
            val row = JSONObject(text)
            fun number(name: String) = if (row.isNull(name)) null else row.getDouble(name).takeIf { it.isFinite() }
            ReplayGainTags(number("track"), number("trackPeak"), number("album"), number("albumPeak"))
        }.getOrDefault(ReplayGainTags())
    }
}

/** App-private global loudness command. The service publishes one immutable audio-thread snapshot. */
internal object ReplayGainCommand {
    const val ACTION = "ua.olex.music.REPLAY_GAIN"
    const val KEY = "ua.olex.music.replaygain"
    val command = SessionCommand(ACTION, Bundle.EMPTY)
    fun fromExtras(extras: Bundle): ReplayGainSettings = extras.getString(KEY)?.let {
        runCatching { ReplayGainCodec.decode(it) }.getOrNull()
    } ?: ReplayGainSettings()
}
