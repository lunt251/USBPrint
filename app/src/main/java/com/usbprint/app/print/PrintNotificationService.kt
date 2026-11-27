package com.usbprint.app.print

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.usbprint.app.MainActivity
import com.usbprint.app.R
import com.usbprint.app.USBPrintApp
import com.usbprint.core.print.JobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive while printing and shows progress with a Cancel action. */
class PrintNotificationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as USBPrintApp
        if (intent?.action == ACTION_CANCEL) {
            app.printing.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(getString(R.string.print_preparing), null, -1),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Not allowed right now (background start restrictions): printing still works while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        watcher?.cancel()
        watcher = scope.launch {
            app.printing.ui.collect { ui ->
                if (ui == null) return@collect
                val s = ui.job
                val nm = NotificationManagerCompat.from(this@PrintNotificationService)
                when (s) {
                    is JobState.Printing -> {
                        val pct = (((s.page + s.fraction) / s.totalPages.coerceAtLeast(1)) * 100).toInt()
                        notify(nm, build(ui.title, getString(R.string.print_page_of, s.page + 1, s.totalPages, pct), pct))
                    }
                    JobState.Preparing -> notify(nm, build(ui.title, getString(R.string.print_preparing), -1))
                    JobState.Cancelling -> notify(nm, build(ui.title, getString(R.string.print_cancelling), -1, cancellable = false))
                    else -> {
                        ServiceCompat.stopForeground(this@PrintNotificationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        val text = when (s) {
                            JobState.Done -> getString(R.string.print_done)
                            JobState.Cancelled -> getString(R.string.print_cancelled)
                            is JobState.Failed -> getString(R.string.print_failed) + ": " + s.message
                            else -> ""
                        }
                        if (s !is JobState.Cancelled) {
                            notify(nm, NotificationCompat.Builder(this@PrintNotificationService, CHANNEL)
                                .setSmallIcon(R.drawable.ic_stat_print)
                                .setContentTitle(ui.title)
                                .setContentText(text)
                                .setContentIntent(openApp())
                                .setAutoCancel(true)
                                .build(), DONE_ID)
                        }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(nm: NotificationManagerCompat, n: android.app.Notification, id: Int = NOTIFICATION_ID) {
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted.
        }
    }

    private fun build(title: String, text: String?, percent: Int, cancellable: Boolean = true) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .apply {
                if (cancellable) {
                    val cancel = PendingIntent.getService(
                        this@PrintNotificationService, 1,
                        Intent(this@PrintNotificationService, PrintNotificationService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(0, getString(R.string.cancel), cancel)
                }
            }
            .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "printing"
        private const val NOTIFICATION_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_CANCEL = "com.usbprint.app.CANCEL_PRINT"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PrintNotificationService::class.java)) }
        }
    }
}
