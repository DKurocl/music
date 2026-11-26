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
    private var errors: LoadErrorHandlingPolicy = PlaybackLoadErrors()
    override fun getSupportedTypes() = template.supportedTypes
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drm = provider; template.setDrmSessionManagerProvider(provider); return this
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        errors = policy; template.setLoadErrorHandlingPolicy(policy); return this
    }
    // Local files and content URIs as before; ytm://<id> is asked of the app at open time, and a
    // song played before comes from the stream cache (see CacheBudget) without asking at all.
    private val data = OnlineCachingDataSource.Factory(app, RetryingOnline.Factory(app, androidx.media3.datasource.ResolvingDataSource.Factory(
        androidx.media3.datasource.DefaultDataSource.Factory(app, androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")),
    ) { spec -> resolveMediaRequest(spec, app as? OnlineStreams) }))

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tags = ReplayGainCodec.tags(mediaItem.mediaMetadata.extras?.getString(ReplayGainCodec.TAGS))
        val guarded = androidx.media3.datasource.DataSource.Factory { SourceGuard(data.createDataSource()) }
        val factory = DefaultMediaSourceFactory(guarded, ReplayGainExtractorsFactory(WithDsd(DefaultExtractorsFactory()), tags))
        drm?.let(factory::setDrmSessionManagerProvider)
        factory.setLoadErrorHandlingPolicy(errors)
        return factory.createMediaSource(mediaItem)
    }
}

/** The provider can supply an HTTPS audio URL. Local playback keeps its original data request. */
@OptIn(UnstableApi::class)
internal fun resolveMediaRequest(spec: androidx.media3.datasource.DataSpec, streams: OnlineStreams?): androidx.media3.datasource.DataSpec {
    if (spec.uri.scheme != OnlineStreams.SCHEME) return spec
    val id = spec.uri.host ?: spec.uri.schemeSpecificPart.trimStart('/')
    val uri = streams?.resolve(id)?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
        ?: throw java.io.IOException("This song is not available online right now")
    return spec.withUri(uri)
}

/** Every audio track format carries the same source's tags; sample and seek operations are untouched. */
@OptIn(UnstableApi::class)
internal class ReplayGainExtractorsFactory(private val delegate: ExtractorsFactory, private val tags: ReplayGainTags) : ExtractorsFactory by delegate {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> = wrap(delegate.createExtractors(uri, responseHeaders))
    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map<Extractor, Extractor> { extractor ->
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

/** Android's extractors plus DSD (DSF, DFF), which Android cannot open on its own. */
@OptIn(UnstableApi::class)
internal class WithDsd(private val delegate: ExtractorsFactory) : ExtractorsFactory by delegate {
    override fun createExtractors(): Array<Extractor> = arrayOf<Extractor>(DsdExtractor()) + delegate.createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf<Extractor>(DsdExtractor()) + delegate.createExtractors(uri, responseHeaders)
}

/**
 * Opens an online song again once with a fresh address when the stream refuses the remembered one
 * (403, 404 or 410: it expired before its time), instead of failing on the same dead address.
 */
@OptIn(UnstableApi::class)
internal class RetryingOnline(private val app: android.content.Context, private val inner: androidx.media3.datasource.DataSource) : androidx.media3.datasource.DataSource by inner {
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long = try { inner.open(dataSpec) } catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
        if (dataSpec.uri.scheme != OnlineStreams.SCHEME || e.responseCode !in setOf(403, 404, 410)) throw e
        val id = dataSpec.uri.host ?: dataSpec.uri.schemeSpecificPart.trimStart('/')
        (app as? OnlineStreams)?.forget(id) ?: throw e
        runCatching { inner.close() }
        inner.open(dataSpec)
    }
    class Factory(private val app: android.content.Context, private val inner: androidx.media3.datasource.DataSource.Factory) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource = RetryingOnline(app, inner.createDataSource())
    }
}

/** An unavailable queue occurrence must never open its stale or ambiguous original source. */
@OptIn(UnstableApi::class)
internal class SourceGuard(private val inner: androidx.media3.datasource.DataSource) : androidx.media3.datasource.DataSource by inner {
    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
        if (spec.uri.scheme == PlaybackSourceItems.UNAVAILABLE) throw java.io.FileNotFoundException("No source is currently available for this song")
        return inner.open(spec)
    }
}
