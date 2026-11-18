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
    val ordered = jobs.sortedWith(compareByDescending<SoulseekDownloads.Job> { it.id == requested }.thenByDescending { it.id })
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            if (ordered.isEmpty()) Text("No recent downloads.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ordered, key = { it.id }) { job ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(job.title, style = MaterialTheme.typography.titleMedium)
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
