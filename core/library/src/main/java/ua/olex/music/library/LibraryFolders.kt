package ua.olex.music.library

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Folders hidden from the library: voice recordings, ringtones, a podcast folder. Android's own
 * index already covers every folder on the phone; this only takes some out. Nothing is deleted.
 */
object LibraryFolders {
    private const val PREFS = "library_folders"
    private const val EXCLUDED = "excluded"

    fun excludedNow(context: Context): Set<String> = prefs(context).getStringSet(EXCLUDED, emptySet()).orEmpty()

    fun setExcluded(context: Context, folder: String, hidden: Boolean) {
        val prefix = folder.trimEnd('/') + "/"
        val next = excludedNow(context).toMutableSet().apply { if (hidden) add(prefix) else remove(prefix) }
        prefs(context).edit().putStringSet(EXCLUDED, next).apply()
    }

    fun excluded(context: Context): Flow<Set<String>> = callbackFlow {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == EXCLUDED) trySend(excludedNow(context)) }
        trySend(excludedNow(context))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
