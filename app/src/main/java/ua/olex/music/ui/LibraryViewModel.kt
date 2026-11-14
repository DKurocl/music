package ua.olex.music.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.olex.music.graph
import ua.olex.music.model.Track
import ua.olex.music.playback.PlayerState

/** The library and the player, as the first screens need them. */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val player get() = graph.player

    /** Null until the first read from the database: the list shows nothing rather than "empty". */
    val tracks: StateFlow<List<Track>?> = graph.library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val playing: StateFlow<PlayerState> get() = player.state

    var allowed by mutableStateOf(hasAccess()); private set
    var scanning by mutableStateOf(false); private set

    val positionMs: Long get() = player.positionMs

    /** Called when the app comes forward and after the permission dialog. */
    fun refresh() {
        allowed = hasAccess()
        if (!allowed || scanning) return
        scanning = true
        viewModelScope.launch {
            try { graph.library.rescan() } finally { scanning = false }
        }
    }

    fun play(list: List<Track>, index: Int) = player.play(list, index)
    fun toggle() = player.toggle()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    private fun hasAccess() =
        getApplication<Application>().checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
}
