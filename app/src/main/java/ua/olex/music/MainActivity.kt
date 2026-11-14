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
        enableEdgeToEdge()
        setContent { AppTheme { MusicRoot(model) } }
    }

    override fun onStart() {
        super.onStart()
        model.connectPlayer()
        // Files change behind the app's back (downloads, a cable, another app): look again on return.
        model.refresh()
    }

    override fun onStop() {
        model.disconnectPlayer()
        super.onStop()
    }
}
