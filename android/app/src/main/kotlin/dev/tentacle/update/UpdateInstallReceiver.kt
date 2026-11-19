package dev.tentacle.update

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.tentacle.R
import dev.tentacle.AppLanguage

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    publishFailure()
                } else if (AppUpdater.isAppForeground()) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                } else {
                    val open = PendingIntent.getActivity(context, 0, confirmation,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val version = intent.getStringExtra(AppUpdater.EXTRA_VERSION).orEmpty()
                    val localized = AppLanguage.wrap(context)
                    val notification = Notification.Builder(context, AppUpdater.CHANNEL)
                        .setSmallIcon(R.drawable.ic_server)
                        .setContentTitle(localized.getString(R.string.update_notification_ready_title, version))
                        .setContentText(localized.getString(R.string.update_notification_ready_text))
                        .setContentIntent(open).setAutoCancel(true).build()
                    context.getSystemService(NotificationManager::class.java)
                        .notify(AppUpdater.INSTALL_NOTIFICATION_ID, notification)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> context.getSystemService(NotificationManager::class.java).run {
                cancel(AppUpdater.NOTIFICATION_ID)
                cancel(AppUpdater.INSTALL_NOTIFICATION_ID)
            }
            else -> publishFailure()
        }
    }

    private fun publishFailure() {
        AppUpdater.publish(UpdateState.Error(UpdateError.INSTALL_FAILED))
    }
}
