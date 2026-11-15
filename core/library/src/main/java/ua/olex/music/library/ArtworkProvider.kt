package ua.olex.music.library

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import ua.olex.music.model.ArtworkPlan

/** The application owns the one cache instance shared by UI and media-session bitmap loaders. */
interface LocalArtworkOwner { val localArtwork: LocalArtworkCache }

/** Private read-only thumbnail provider; source URIs are bounded and cannot recurse into this provider. */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read only")
        val app = requireNotNull(context).applicationContext
        val source = ArtworkUris.source(uri, app.packageName) ?: throw FileNotFoundException("Invalid artwork URI")
        val owner = app as? LocalArtworkOwner ?: throw FileNotFoundException("Artwork owner unavailable")
        return owner.localArtwork.open(source, uri.getQueryParameter("px")?.toIntOrNull() ?: ArtworkPlan.MEDIUM)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(columnNames.map { if (it == OpenableColumns.DISPLAY_NAME) "cover.png" else null }.toTypedArray())
        }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Artwork is read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Artwork is read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Artwork is read only")
}
