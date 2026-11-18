package ua.olex.music.soulseek

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ua.olex.music.MusicApp

/** Keeps the process eligible to transfer files only while the owner has downloads queued. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads get() = (application as MusicApp).graph.soulseek
    private var observation: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, downloads.channel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Soulseek downloads")
            .setContentIntent(DownloadActions.open(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        downloads.resume()
        observation?.cancel()
        observation = scope.launch {
            downloads.jobs.collect { rows ->
                if (rows.none { it.running }) stopSelf(startId)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.suspendTransfers()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        downloads.suspendTransfers()
        super.onDestroy()
    }

    private companion object {
        const val FOREGROUND_ID = 3900
    }
}

/** Notification actions stay private and identify a durable download rather than a display name. */
class DownloadActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(DOWNLOAD_ID, -1)
        if (id <= 0) return
        val downloads = (context.applicationContext as MusicApp).graph.soulseek
        when (intent.action) {
            CANCEL -> downloads.cancel(id)
            RETRY -> downloads.retry(id)
        }
    }

    companion object {
        const val DOWNLOAD_ID = "ua.olex.music.download_id"
        private const val CANCEL = "ua.olex.music.download.cancel"
        private const val RETRY = "ua.olex.music.download.retry"

        fun open(context: Context, id: Int = 0): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                intent.putExtra(DOWNLOAD_ID, id)
                PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        internal fun action(context: Context, id: Int, retry: Boolean): PendingIntent =
            PendingIntent.getBroadcast(context, id, Intent(context, DownloadActions::class.java)
                .setAction(if (retry) RETRY else CANCEL).putExtra(DOWNLOAD_ID, id), PendingIntent.FLAG_IMMUTABLE)
    }
}
