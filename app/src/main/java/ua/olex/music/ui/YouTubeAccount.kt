package ua.olex.music.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * The owner's YouTube Music account, for their own home, mixes and radio. Signing in happens on
 * Google's own page (YouTubeLogin): the app never sees the password, only the session cookies the
 * page leaves, kept in the app's private storage and sent to music.youtube.com alone.
 *
 * Requests are signed the way music.youtube.com signs them in a browser: an Authorization header
 * `SAPISIDHASH <time>_<sha1("<time> <SAPISID> <origin>")>` with the cookies.
 */
class YouTubeAccount(context: Context) {
    private val prefs = context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE)
    private val _signedIn = MutableStateFlow(cookies() != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _cacheScope = MutableStateFlow(scopeFor(cookies()))
    internal val cacheScope: StateFlow<String> = _cacheScope
    private val _revision = MutableStateFlow(0L)
    internal val revision: StateFlow<Long> = _revision

    private fun scopeFor(cookies: String?) = cookies?.let(OnlinePageStore::digest) ?: "public"

    fun cookies(): String? = prefs.getString("cookies", null)?.takeIf { hasSession(it) }
    val name: String? get() = prefs.getString("name", null)

    @Synchronized
    fun save(cookies: String, name: String?) {
        prefs.edit().putString("cookies", cookies).putString("name", name).apply()
        moveTo(scopeFor(cookies.takeIf(::hasSession)))
        _signedIn.value = hasSession(cookies)
    }

    @Synchronized
    fun signOut() {
        prefs.edit().clear().apply()
        moveTo("public")
        _signedIn.value = false
        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
    }

    private fun moveTo(scope: String) {
        if (_cacheScope.value == scope) return
        _revision.value += 1
        _cacheScope.value = scope
    }

    /** Headers that make a request the owner's own; null when signed out. */
    @Synchronized
    fun headers(nowSeconds: Long = System.currentTimeMillis() / 1000): Map<String, String>? {
        val cookies = cookies() ?: return null
        val auth = authorization(cookies, nowSeconds) ?: return null
        return mapOf("Cookie" to cookies, "Authorization" to auth, "X-Origin" to ORIGIN, "X-Goog-AuthUser" to "0")
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"

        /** A session is there when both the SID and the SAPISID (or their secure forms) are. */
        fun hasSession(cookies: String): Boolean {
            val names = cookies.split(';').map { it.substringBefore('=').trim() }.toSet()
            return ("SAPISID" in names || "__Secure-3PAPISID" in names) && ("SID" in names || "__Secure-3PSID" in names)
        }

        fun authorization(cookies: String, nowSeconds: Long, origin: String = ORIGIN): String? {
            val values = cookies.split(';').associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            val sapisid = values["SAPISID"] ?: values["__Secure-3PAPISID"] ?: return null
            val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $sapisid $origin".toByteArray())
            return "SAPISIDHASH ${nowSeconds}_" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
