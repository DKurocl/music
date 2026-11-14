package ua.olex.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import ua.olex.music.dsp.ReplayGainTags

/** Preserves extractor custom data while carrying scanner tags on the decoder's timestamped format. */
internal data class ReplayGainStreamData(val tags: ReplayGainTags, val extractorData: Any?)

/**
 * Attaches the selected item's tags at extraction, not on audible item transitions. Media3 queues
 * formats with decoded timestamps and copies customData into the sink's PCM format, even on reuse.
 */
@OptIn(UnstableApi::class)
internal class ReplayGainMediaSourceFactory(context: Context) : MediaSource.Factory {
    private val app = context.applicationContext
    private val template = DefaultMediaSourceFactory(app)
    private var drm: DrmSessionManagerProvider? = null
    private var errors: LoadErrorHandlingPolicy? = null
    override fun getSupportedTypes() = template.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drm = provider; template.setDrmSessionManagerProvider(provider); return this
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        errors = policy; template.setLoadErrorHandlingPolicy(policy); return this
    }
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tags = ReplayGainCodec.tags(mediaItem.mediaMetadata.extras?.getString(ReplayGainCodec.TAGS))
        val factory = DefaultMediaSourceFactory(app, ReplayGainExtractorsFactory(DefaultExtractorsFactory(), tags))
        drm?.let(factory::setDrmSessionManagerProvider); errors?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(mediaItem)
    }
}

/** Every audio track format carries the same source's tags; sample and seek operations are untouched. */
@OptIn(UnstableApi::class)
internal class ReplayGainExtractorsFactory(private val delegate: ExtractorsFactory, private val tags: ReplayGainTags) : ExtractorsFactory by delegate {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> = wrap(delegate.createExtractors(uri, responseHeaders))
    private fun wrap(extractors: Array<Extractor>) = extractors.map { extractor ->
        object : Extractor by extractor {
            override fun init(output: ExtractorOutput) = extractor.init(object : ExtractorOutput by output {
                override fun track(id: Int, type: Int): TrackOutput {
                    val target = output.track(id, type)
                    if (type != C.TRACK_TYPE_AUDIO) return target
                    return object : TrackOutput by target {
                        override fun format(format: Format) = target.format(format.buildUpon()
                            .setCustomData(ReplayGainStreamData(tags, format.customData)).build())
                    }
                }
            })
        }
    }.toTypedArray()
}
