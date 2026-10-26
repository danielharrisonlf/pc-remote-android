package com.pcremote

import android.app.Notification
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

/**
 * Keeps the process running, with a progress notification, while a file moves in
 * either direction, so Android does not freeze the app halfway through a large
 * transfer. It holds no transfer logic: RemoteClient does the work, starts it from
 * the foreground and stops it when both directions are idle.
 */
internal class TransferService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification = build(this, intent?.getStringExtra(EXTRA_TEXT) ?: "Transferindo arquivo…", intent?.getIntExtra(EXTRA_PERMILLE, -1) ?: -1)
    try {
      if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, notification)
      foregroundActive = true
    } catch (_: RuntimeException) { stopSelf() }
    return START_NOT_STICKY
  }

  /** Android 15+ limits dataSync services; the transfer goes on while the process lives. */
  override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

  override fun onDestroy() {
    foregroundActive = false
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "transfers"
    private const val ID = 10
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_PERMILLE = "permille"

    /** Set once startForeground succeeded; an update without a live service would leave an ongoing notification behind. */
    @Volatile private var foregroundActive = false

    /** Starts the service from the foreground; false when Android refuses it. */
    fun start(context: Context, text: String, permille: Int): Boolean = try {
      val intent = Intent(context, TransferService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_PERMILLE, permille)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
      true
    } catch (_: RuntimeException) { false }

    fun update(context: Context, text: String, permille: Int) {
      if (!foregroundActive) return
      try { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(context, text, permille)) } catch (_: RuntimeException) {}
    }

    fun stop(context: Context) {
      try { context.stopService(Intent(context, TransferService::class.java)) } catch (_: RuntimeException) {}
      // After a timeout or a refused start the service is already gone; clear what update() may have posted.
      try { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: RuntimeException) {}
    }

    private fun build(context: Context, text: String, permille: Int): Notification {
      if (Build.VERSION.SDK_INT >= 26) try {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "Transferências de arquivos", NotificationManager.IMPORTANCE_LOW))
      } catch (_: RuntimeException) {}
      val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_file_up)
        .setContentTitle("PC Remote").setContentText(text).setContentIntent(open)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(1000, permille.coerceIn(0, 1000), permille < 0)
        .build()
    }
  }
}
