package dev.tentacle.update

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import dev.tentacle.AppLanguage
import dev.tentacle.AppLog
import dev.tentacle.BuildConfig
import dev.tentacle.MainActivity
import dev.tentacle.R
import dev.tentacle.Settings

object AfterUpdate {
    const val EXTRA_AFTER_UPDATE = "after_update"
    private const val NOTIFICATION_ID = 4

    fun onPackageReplaced(context: Context) {
        val settings = Settings(context)
        settings.recordInstalledVersion()
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_AFTER_UPDATE, true)

        // Post even when no in-app update is recorded or a vendor blocks the automatic launch.
        AppUpdater.refreshNotificationChannel(context)
        val localized = AppLanguage.wrap(context)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, AppUpdater.CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(localized.getString(R.string.update_notification_installed_title, BuildConfig.VERSION_NAME))
            .setContentText(localized.getString(R.string.update_notification_installed_text))
            .setContentIntent(open).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

        if (AfterUpdatePolicy.isRecentInAppUpdate(settings.updateStartedMillis, settings.updateTargetVersion,
                BuildConfig.VERSION_NAME, System.currentTimeMillis()) && SystemSettings.canDrawOverlays(context)) {
            try {
                context.startActivity(openIntent)
            } catch (_: Exception) {
                AppLog.append("Update reopen=blocked; notification available")
            }
        }
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
