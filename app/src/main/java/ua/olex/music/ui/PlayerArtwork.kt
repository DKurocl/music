@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import ua.olex.music.model.ArtworkPlan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.SuccessResult
import coil3.size.Scale
import ua.olex.music.library.ArtworkUris

/** The revision reopens a file replaced in place by an explicit cover edit. */
internal data class MotionArtwork(val uri: String, val revision: Long)

@Composable
internal fun ArtworkFrame(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

internal fun playerCoverRequest(context: Context, uri: String, pixels: Int, size: Int? = null): ImageRequest =
    ImageRequest.Builder(context).data(ArtworkUris.variant(uri, context.packageName, pixels))
        .crossfade(false).apply { if (size != null) size(size).scale(Scale.FILL) }.build()

/** A decoded still can move from a swipe neighbour to the centre or between player sizes. */
internal data class CoverFrame(val painter: Painter, val pixels: Int)

internal class PlayerCoverMemory {
    private val frames = androidx.compose.runtime.mutableStateMapOf<String, CoverFrame>()
    private val order = ArrayDeque<String>()

    operator fun get(uri: String?): CoverFrame? = uri?.let(frames::get)

    fun put(uri: String, frame: CoverFrame) {
        if ((frames[uri]?.pixels ?: 0) >= frame.pixels) return
        order.remove(uri)
        order.addLast(uri)
        frames[uri] = frame
        if (order.size > 5) frames.remove(order.removeFirst())
    }
}

internal val LocalPlayerCovers = staticCompositionLocalOf<PlayerCoverMemory?> { null }
/** Endpoints are measured in the unscaled page and its host, even during an interrupted drag. */
internal class PlayerTravel(private val scope: kotlinx.coroutines.CoroutineScope, initiallyOpen: Boolean = false) {
    var fraction by androidx.compose.runtime.mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set
    var thumbnail by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    var sleeve by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    var lyricsVisible by mutableStateOf(false)
    val coverInFlight: Boolean
        get() = !lyricsVisible && fraction > 0f && fraction < 1f && thumbnail.width > 0f && sleeve.width > 0f
    var page: androidx.compose.ui.layout.LayoutCoordinates? = null
    var host: androidx.compose.ui.layout.LayoutCoordinates? = null
    private var settling: kotlinx.coroutines.Job? = null

    fun drag(delta: Float) {
        settling?.cancel()
        fraction = (fraction + delta).coerceIn(0f, 1f)
    }

    fun settle(open: Boolean, instant: Boolean) {
        settling?.cancel()
        val end = if (open) 1f else 0f
        if (instant || fraction == end) { fraction = end; return }
        settling = scope.launch {
            androidx.compose.animation.core.animate(fraction, end,
                animationSpec = tween((150 + 190 * kotlin.math.abs(end - fraction)).toInt(), easing = FastOutSlowInEasing)
            ) { value, _ -> fraction = value }
        }
    }
}

internal val LocalPlayerTravel = staticCompositionLocalOf<PlayerTravel?> { null }
private val LocalDirectPlayerOpen = staticCompositionLocalOf { false }

@Composable
internal fun PlayerCoverLayout(travel: PlayerTravel, artwork: String? = null, directOpen: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPlayerTravel provides travel, LocalPlayerCovers provides remember { PlayerCoverMemory() },
        LocalDirectPlayerOpen provides directOpen,
    ) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { travel.host = it }) {
            content()
            val p = travel.fraction
            val small = travel.thumbnail
            val large = travel.sleeve
            if (!directOpen && travel.coverInFlight) {
                val density = LocalDensity.current
                val x = small.left + (large.left - small.left) * p
                val y = small.top + (large.top - small.top) * p
                val width = small.width + (large.width - small.width) * p
                val height = small.height + (large.height - small.height) * p
                val image = rememberCoverImage(artwork, ArtworkPlan.LARGE, large.width.toInt())
                PlayerCoverFace(image, Modifier.offset { IntOffset(x.toInt(), y.toInt()) }
                    .size(with(density) { width.toDp() }, with(density) { height.toDp() }),
                    corner = 14.dp + 18.dp * p)
            }
        }
    }
}

@Composable
internal fun Modifier.playerCoverEndpoint(mini: Boolean): Modifier {
    val travel = LocalPlayerTravel.current ?: return this
    val directOpen = LocalDirectPlayerOpen.current
    return graphicsLayer { if (!directOpen && (travel.coverInFlight || (!mini && travel.fraction < 1f))) alpha = 0f }.onGloballyPositioned { coordinates ->
        val parent = (if (mini) travel.host else travel.page) ?: return@onGloballyPositioned
        if (!parent.isAttached) return@onGloballyPositioned
        val bounds = parent.localBoundingBoxOf(coordinates, clipBounds = false)
        if (mini) travel.thumbnail = bounds else travel.sleeve = bounds
    }
}

/** Keep the page at full size while its artwork travels separately. */
@Composable
internal fun Modifier.playerTransform(travel: PlayerTravel): Modifier {
    val directOpen = LocalDirectPlayerOpen.current
    return graphicsLayer { alpha = if (directOpen) 1f else travel.fraction }
        .onGloballyPositioned { travel.page = it }
}

internal data class CoverImage(val frame: CoverFrame?, val loading: Boolean, val preview: CoverFrame? = null)

@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun rememberCoverImage(uri: String?, pixels: Int, targetSize: Int): CoverImage {
    val memory = LocalPlayerCovers.current ?: remember { PlayerCoverMemory() }
    val context = LocalContext.current
    val loader = SingletonImageLoader.get(context)
    val preview = if (LocalInspectionMode.current) LocalAsyncImagePreviewHandler.current else null
    var failed by remember(uri, targetSize) { mutableStateOf(false) }
    val size = minOf(pixels, targetSize)
    // A full player can stay visible while queue edits cycle many neighbours through the cache.
    var retained by remember(uri, memory) { mutableStateOf<CoverFrame?>(null) }
    val frame = memory[uri] ?: retained
    val previewFrame = remember(uri, frame) { retained?.takeIf { frame != null && it.pixels < frame.pixels } }
    androidx.compose.runtime.SideEffect { retained = frame }
    // Keep the request independent of draw visibility and of the shared element's animated size.
    LaunchedEffect(uri, size, loader, preview, memory) {
        if (uri == null || size <= 0 || (memory[uri]?.pixels ?: 0) >= size) return@LaunchedEffect
        val request = playerCoverRequest(context, uri, pixels, size)
        val painter = if (preview != null) (preview.handle(loader, request) as? AsyncImagePainter.State.Success)?.painter
            else (loader.execute(request) as? SuccessResult)?.image?.asPainter(context)
        if (painter != null) memory.put(uri, CoverFrame(painter, size))
        else failed = true
    }
    return CoverImage(frame, frame == null && uri != null && !failed, previewFrame)
}

@Composable
internal fun PlayerArtwork(uri: String?, modifier: Modifier, corner: Dp, pixels: Int, miniEndpoint: Boolean = false) {
    var measured by remember { mutableStateOf(0) }
    val image = rememberCoverImage(uri, pixels, measured)
    PlayerCoverFace(image, modifier.onSizeChanged { measured = maxOf(it.width, it.height) }
        .then(if (miniEndpoint) Modifier.playerCoverEndpoint(true) else Modifier), corner)
}

/** Keep the outline continuous as the shared element grows from a thumbnail to the album sleeve. */
private val playerCoverShape = RoundedCornerShape(object : androidx.compose.foundation.shape.CornerSize {
    override fun toPx(shapeSize: androidx.compose.ui.geometry.Size, density: androidx.compose.ui.unit.Density): Float =
        with(density) {
            val fraction = ((shapeSize.minDimension - 48.dp.toPx()) / 272.dp.toPx()).coerceIn(0f, 1f)
            (14.dp + 18.dp * fraction).toPx()
        }
})

/** A ready neighbour paints directly on the commit frame, without a second reveal animation. */
@Composable
internal fun PlayerCoverFace(image: CoverImage, modifier: Modifier, corner: Dp, motion: MotionArtwork? = null, moving: Boolean = false, showMotion: Boolean = true, retainWhileLoading: Boolean = true) {
    var previous by remember { mutableStateOf<CoverFrame?>(null) }
    val frame = image.frame ?: previous.takeIf { image.loading && retainWhileLoading }
    androidx.compose.runtime.SideEffect { previous = frame }
    val saving = LocalPowerSave.current
    ArtworkFrame(modifier, if (LocalPlayerTravel.current != null) playerCoverShape else RoundedCornerShape(corner)) {
        if (frame != null) {
            val preview = image.preview
            if (preview == null || saving) Image(frame.painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else key(frame) {
                val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(frame) { reveal.animateTo(1f, tween(100)) }
                if (reveal.value < 1f) Image(preview.painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Image(frame.painter, null, Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }, contentScale = ContentScale.Crop)
            }
        }
        else Symbol(Glyph.NOTE, size = 24.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, filled = true)
        if (motion != null && !saving && !LocalInspectionMode.current) {
            val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
            if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) key(motion) {
                MotionCover(motion.uri, moving && showMotion, showMotion, Modifier.fillMaxSize())
            }
        }
    }
}

/** One muted decoder per active URI. The still image stays visible until a verified first frame. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MotionCover(uri: String, moving: Boolean, visible: Boolean, modifier: Modifier) {
    var failed by remember { mutableStateOf(false) }
    if (failed) return
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(uri))
        }
    }
    fun restartLoop() {
        if (player.isCurrentMediaItemSeekable) player.seekToDefaultPosition()
        else {
            ready = false
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlaybackStateChanged(playbackState: Int) {
                // A fragmented MP4 may end without automatic repeat.
                if (playbackState == Player.STATE_ENDED && player.playWhenReady) restartLoop()
            }
        }
        player.addListener(listener)
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, moving) {
        if (moving && player.playbackState == Player.STATE_ENDED) restartLoop()
        player.playWhenReady = moving
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (ready && visible) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "motion artwork",
    )
    AndroidView(
        factory = { ctx -> CoverTexture(ctx).also(player::setVideoTextureView) },
        update = { view -> view.setVideoSize(videoSize) },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

/** TextureView stretches by default. Use the same centre crop as the still cover. */
@androidx.annotation.OptIn(UnstableApi::class)
private class CoverTexture(context: Context) : TextureView(context) {
    private var video = VideoSize.UNKNOWN

    init { isOpaque = false }

    fun setVideoSize(value: VideoSize) {
        if (video == value) return
        video = value
        crop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        crop()
    }

    private fun crop() {
        if (width == 0 || height == 0 || video.width == 0 || video.height == 0) return
        val ratio = (video.width * video.pixelWidthHeightRatio / video.height) / (width.toFloat() / height)
        if (!ratio.isFinite() || ratio <= 0f) return
        setTransform(Matrix().apply {
            setScale(maxOf(1f, ratio), maxOf(1f, 1f / ratio), width / 2f, height / 2f)
        })
    }
}
