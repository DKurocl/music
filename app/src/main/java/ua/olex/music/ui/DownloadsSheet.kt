package ua.olex.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.olex.music.soulseek.SoulseekDownloads

/** A notification opens its own download first, even after Android recreated the process. */
@Composable
internal fun DownloadsSheet(downloads: SoulseekDownloads, requested: Int, onDismiss: () -> Unit) {
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val ordered = androidx.compose.runtime.remember(jobs, requested) {
        jobs.sortedWith(compareByDescending<SoulseekDownloads.Job> { it.id == requested }
            .thenBy { when { it.running -> 0; it.failed -> 1; else -> 2 } }.thenByDescending { it.id })
    }
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (jobs.any { !it.running }) TextButton(onClick = downloads::clearFinished) { Text("Clear finished") }
            }
            if (ordered.isEmpty()) EmptyState(Glyph.DOWNLOAD, "No recent downloads",
                "Choose Download on a song or album to save a local copy.")
            else {
                if (requested > 0 && jobs.none { it.id == requested }) Text("This download is no longer in the history.",
                    style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ordered, key = { it.id }) { job ->
                        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                            color = if (job.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Symbol(when { job.running -> Glyph.DOWNLOADING; job.done -> Glyph.CHECK; job.failed -> Glyph.INFO; else -> Glyph.CLOSE })
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(job.title, style = MaterialTheme.typography.titleSmall)
                                    Text(job.status, style = MaterialTheme.typography.bodyMedium)
                                    if (job.running) {
                                        val progress = job.progress
                                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                        else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                if (job.running) TextButton(onClick = { downloads.cancel(job.id) }) { Text("Cancel") }
                                if (job.failed || job.cancelled) TextButton(onClick = { downloads.retry(job) }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
    }
}
