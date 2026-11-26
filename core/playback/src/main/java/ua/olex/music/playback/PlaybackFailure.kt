package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import ua.olex.music.model.RetryAfter

/** Failures shared by other queue items should leave the current occurrence available to retry. */
@OptIn(UnstableApi::class)
internal object PlaybackFailure {
    fun keepCurrent(error: PlaybackException): Boolean {
        if (error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )) return true
        return httpFailure(error)?.responseCode in setOf(401, 429)
    }
}

private fun httpFailure(error: Throwable) = generateSequence(error) { it.cause }.take(8)
    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

/** Bound provider throttling without repeatedly refreshing known expired addresses. */
@OptIn(UnstableApi::class)
internal class PlaybackLoadErrors : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val response = httpFailure(info.exception) ?: return super.getRetryDelayMsFor(info)
        return when (response.responseCode) {
            401, 403, 404, 410 -> C.TIME_UNSET
            429 -> {
                val retryAfter = response.headerFields.entries
                    .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }?.value?.firstOrNull()
                retryAfterMs(retryAfter, info.errorCount, System.currentTimeMillis()) ?: C.TIME_UNSET
            }
            else -> super.getRetryDelayMsFor(info)
        }
    }

    companion object {
        internal fun retryAfterMs(header: String?, attempt: Int, now: Long): Long? {
            if (attempt != 1) return null
            return RetryAfter.milliseconds(header, now)?.takeIf { it in 1_000..30_000 }
        }
    }
}
