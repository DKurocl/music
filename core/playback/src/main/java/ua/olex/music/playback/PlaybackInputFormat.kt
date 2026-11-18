package ua.olex.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import ua.olex.music.model.AudioFormat

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun Tracks.selectedAudioFormat(): AudioFormat? {
    var selected: AudioFormat? = null
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (index in 0 until group.length) {
            if (!group.isTrackSelected(index)) continue
            val format = group.getTrackFormat(index).toSourceFormat() ?: return null
            // An adaptive group may mark several formats selected without saying which one is playing.
            if (selected != null && selected != format) return null
            selected = format
        }
    }
    return selected
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun Format.toSourceFormat(): AudioFormat? {
    val codec = when (sampleMimeType) {
        "audio/mp4a-latm" -> "aac"
        "audio/mpeg" -> "mp3"
        "audio/mpeg-L2" -> "mp2"
        "audio/mpeg-L1" -> "mp1"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/flac" -> "flac"
        "audio/alac" -> "alac"
        "audio/raw" -> "pcm"
        "audio/ac3" -> "ac3"
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/ac4" -> "ac4"
        "audio/true-hd" -> "truehd"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "dts"
        "audio/iamf" -> "iamf"
        else -> return null
    }
    val depth = if (codec == "pcm") when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    } else null
    return AudioFormat(codec, sampleRate.takeIf { it > 0 }, depth,
        averageBitrate.takeIf { it >= 1_000 }?.div(1_000), channelCount.takeIf { it > 0 })
}
