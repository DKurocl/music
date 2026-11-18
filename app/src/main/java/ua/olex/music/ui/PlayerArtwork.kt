@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import kotlinx.coroutines.delay
import ua.olex.music.library.ArtworkUris

/** The revision reopens a file replaced in place by an explicit cover edit. */
internal data class MotionArtwork(val uri: String, val revision: Long)

@Composable
internal fun ArtworkFrame(modifier: Modifier, corner: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

internal fun playerCoverRequest(context: Context, uri: String, pixels: Int, size: Int? = null): ImageRequest =
    ImageRequest.Builder(context).data(ArtworkUris.variant(uri, context.packageName, pixels))
        .crossfade(false).apply { if (size != null) size(size).scale(Scale.FILL) }.build()

/** Keep a decoded image behind the next request, so a cache miss never draws an empty frame. */
@Composable
internal fun PlayerArtwork(uri: String?, modifier: Modifier, corner: Dp, pixels: Int, motion: MotionArtwork? = null, moving: Boolean = true) {
    val context = LocalContext.current
    val saving = LocalPowerSave.current
    val resolved = remember { mutableStateOf<Painter?>(null) }
    val requestToken = remember(uri, pixels) { Any() }
    val latestToken by rememberUpdatedState(requestToken)
    // Capture the outgoing layer once per URI. Keep at most two decoded painters during the fade.
    var outgoing by remember(uri) { mutableStateOf(resolved.value) }
    var ready by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    val alpha = remember(uri) { Animatable(0f) }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(uri, ready, failed, saving) {
        when {
            uri == null || failed -> { outgoing = null; resolved.value = null }
            ready -> {
                if (outgoing == null || saving) alpha.snapTo(1f) else alpha.animateTo(1f, effects)
                outgoing = null
            }
            else -> {
                // A slow remote request must not leave the preceding track's artwork up indefinitely.
                delay(900)
                outgoing = null
                resolved.value = null
            }
        }
    }
    ArtworkFrame(modifier, corner) {
        val previous = outgoing.takeUnless { failed || uri == null }
        if (previous != null) {
            Image(previous, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (!ready || alpha.value < 1f) {
            Symbol(Glyph.NOTE, size = 24.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, filled = true)
        }
        if (uri != null) key(uri) {
            val request = remember(context, uri, pixels) { playerCoverRequest(context, uri, pixels) }
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
                onSuccess = {
                    if (requestToken === latestToken) {
                        resolved.value = it.painter
                        ready = true
                        failed = false
                    }
                },
                onError = { if (requestToken === latestToken) { failed = true; ready = false } },
            )
        }
        if (motion != null && !saving && !LocalInspectionMode.current) {
            val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
            if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) key(motion) {
                MotionCover(motion.uri, moving, Modifier.fillMaxSize())
            }
        }
    }
}

/** One muted decoder per active URI. The still image stays visible until a verified first frame. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MotionCover(uri: String, moving: Boolean, modifier: Modifier) {
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
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlaybackStateChanged(playbackState: Int) {
                // A fragmented MP4 may end without automatic repeat. Prepare it again from zero.
                if (playbackState == Player.STATE_ENDED && player.playWhenReady) {
                    if (!player.isCurrentMediaItemSeekable) {
                        ready = false
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.prepare()
                    } else player.seekToDefaultPosition()
                }
            }
        }
        player.addListener(listener)
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, moving) { player.playWhenReady = moving }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (ready) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "motion artwork",
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
        setTransform(Matrix().apply {
            setScale(maxOf(1f, ratio), maxOf(1f, 1f / ratio), width / 2f, height / 2f)
        })
    }
}
