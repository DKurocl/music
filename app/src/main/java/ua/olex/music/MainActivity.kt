package ua.olex.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.olex.music.ui.AppTheme
import ua.olex.music.ui.LibraryViewModel
import ua.olex.music.ui.MusicRoot

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash stays until Home has its library (or 0.7 s at most), then hands over.
        val graph = (application as MusicApp).graph
        SplashHandoff.install(this) { graph.index.value != null }
        if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch { graph.index.first { it != null } }
        }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        if (savedInstanceState == null) { playFromSearch(intent); openPlayer(intent); openDownloads(intent); shortcut(intent) }
        setContent { AppTheme { MusicRoot(model) } }
    }

    /** The media notification (or the lock screen) was tapped: open the player, not just the app. */
    private fun openPlayer(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(ua.olex.music.playback.PlaybackService.OPEN_PLAYER, false) == true) model.openPlayerFromSystem()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        playFromSearch(intent)
        openPlayer(intent)
        openDownloads(intent)
        shortcut(intent)
    }

    /** A launcher shortcut (long-press on the icon): Resume, Shuffle all, Liked or Search. */
    private fun shortcut(intent: android.content.Intent?) {
        when (intent?.action) {
            "ua.olex.music.action.RESUME" -> model.resumeFromShortcut()
            "ua.olex.music.action.SHUFFLE_ALL" -> model.shuffleAllFromShortcut()
            "ua.olex.music.action.LIKED" -> model.likedFromShortcut()
            "ua.olex.music.action.SEARCH" -> model.requestSearch()
        }
    }

    private fun openDownloads(intent: android.content.Intent?) {
        intent?.getIntExtra(ua.olex.music.soulseek.DownloadActions.DOWNLOAD_ID, -1)
            ?.takeIf { it >= 0 }?.let(model::requestDownloads)
    }

    /** "Play Karma Police on Music": search the library the way Android Auto does and play the matches. */
    private fun playFromSearch(intent: android.content.Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
        model.playSearch(query)
    }

    override fun onStart() {
        super.onStart()
        (application as MusicApp).graph.resources.setVisible(true)
        model.setPlaybackScreenVisible(true)
        model.connectPlayer()
        model.startWatching()
    }

    override fun onPostResume() {
        super.onPostResume()
        (application as MusicApp).graph.soulseek.requestResume()
    }

    override fun onStop() {
        (application as MusicApp).graph.resources.setVisible(false)
        model.stopWatching()
        model.setPlaybackScreenVisible(false)
        super.onStop()
    }
}
