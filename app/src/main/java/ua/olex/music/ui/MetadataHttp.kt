package ua.olex.music.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared transport for public artist, tags and lyric metadata, separate from audio. */
internal object MetadataHttp {
    private const val BYTES = 4 shl 20
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).build()

    private fun call(url: String) = client.newCall(Request.Builder().url(url.toHttpUrlOrNull() ?: throw IOException("Invalid metadata address"))
        .header("User-Agent", "Music for Android (personal player)").build())

    private fun read(response: Response, limit: Int): ByteArray? = response.use {
        if (it.code == 404) return null
        if (!it.isSuccessful) throw IOException("Metadata returned HTTP ${it.code}")
        val bytes = it.body?.byteStream()?.readNBytes(limit + 1) ?: return null
        if (bytes.size > limit) throw IOException("Metadata exceeded the request budget")
        bytes
    }

    fun getBlocking(url: String): ByteArray? = try { read(call(url).execute(), BYTES) } catch (_: IOException) { null }

    suspend fun get(url: String, limit: Int = BYTES): ByteArray? = suspendCancellableCoroutine { continuation ->
        val call = call(url)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                val result = try { Result.success(read(response, limit)) } catch (failure: IOException) { Result.failure(failure) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}
