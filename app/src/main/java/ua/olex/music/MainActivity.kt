package ua.olex.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ua.olex.music.ui.AppTheme
import ua.olex.music.ui.LibraryViewModel
import ua.olex.music.ui.MusicRoot

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SplashHandoff.install(this)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent { AppTheme { MusicRoot(model) } }
        if (savedInstanceState == null) playFromSearch(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        playFromSearch(intent)
    }

    /** "Play Karma Police on Music": search the library the way Android Auto does and play the matches. */
    private fun playFromSearch(intent: android.content.Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
        model.playSearch(query)
    }

    override fun onStart() {
        super.onStart()
        model.connectPlayer()
        model.startWatching()
        // Files change behind the app's back (downloads, a cable, another app): look again on return.
        model.refresh()
    }

    override fun onStop() {
        model.stopWatching()
        model.disconnectPlayer()
        super.onStop()
    }
}
