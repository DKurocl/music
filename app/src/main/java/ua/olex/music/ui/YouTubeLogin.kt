package ua.olex.music.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Google's own sign-in page for YouTube Music, in a plain WebView. The password goes to Google, not
 * to the app; once the page lands on music.youtube.com with a session, its cookies are kept
 * (YouTubeAccount) and the page closes.
 */
class YouTubeLogin : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = (application as ua.olex.music.MusicApp).graph.youTubeAccount
        val web = WebView(this)
        setContentView(web)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Google refuses sign-in in a WebView that says it is one ("; wv", "Version/4.0"): without
        // those marks it is the phone's own Chrome engine, as it really is.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+\s*"""), "")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.startsWith(YouTubeAccount.ORIGIN) != true) return
                val cookies = cookies() ?: return
                if (!YouTubeAccount.hasSession(cookies)) return
                account.save(cookies, null)
                setResult(RESULT_OK); finish()
            }
        }
        web.loadUrl(LOGIN)
    }

    /** The session cookies of music.youtube.com and the YouTube and Google domains it relies on. */
    private fun cookies(): String? {
        val manager = CookieManager.getInstance().also { it.flush() }
        val all = LinkedHashMap<String, String>()
        for (domain in listOf(YouTubeAccount.ORIGIN, "https://www.youtube.com", "https://youtube.com")) {
            manager.getCookie(domain)?.split(';')?.forEach { part ->
                val name = part.substringBefore('=').trim(); val value = part.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) all[name] = value
            }
        }
        return all.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
    }

    companion object {
        const val LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F&hl=en"
    }
}
