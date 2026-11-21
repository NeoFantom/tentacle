package dev.tentacle.codes

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.tentacle.Settings
import dev.tentacle.core.PhoneNotificationInput
import dev.tentacle.feed.PhoneNotificationStores

/** The app's single notification-access component, dispatching to each opted-in consumer. */
class CodeNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        PhoneNotificationStores.dispatch { processPosted(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        PhoneNotificationStores.dispatch {
            PhoneNotificationStores.markRemovedNow(this, sbn.key)
        }
    }

    private fun processPosted(sbn: StatusBarNotification) {
        val settings = Settings(this)
        val captureCodes = settings.codesEnabled && settings.codesNotifications
        val captureFeed = settings.phoneNotificationsEnabled
        if (!captureCodes && !captureFeed) return

        val notification = sbn.notification
        val extras = notification.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map(CharSequence::toString)
            ?: emptyList()
        val appLabel = applicationLabel(sbn.packageName)

        if (captureCodes && sbn.packageName != packageName &&
            notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) == 0) {
            runCatching {
                NotificationCodeText.combine(title, listOf(text, bigText) + textLines)?.let { codeText ->
                    CodeStores.capture(this, codeText, appLabel.ifBlank { title }, "notification")
                }
            }.onFailure { dev.tentacle.AppLog.append("Notification code capture failed") }
        }

        if (captureFeed) {
            runCatching {
                PhoneNotificationStores.captureNow(
                    this,
                    PhoneNotificationInput(
                        key = sbn.key,
                        packageName = sbn.packageName,
                        appLabel = appLabel.ifBlank { sbn.packageName },
                        title = title,
                        text = text,
                        bigText = bigText,
                        textLines = textLines,
                        messages = messagingTexts(extras),
                        subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
                        category = notification.category.orEmpty(),
                        postTimeMs = sbn.postTime,
                        ongoing = sbn.isOngoing || notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                        foregroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                        groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                    ),
                )
            }.onFailure { dev.tentacle.AppLog.append("Phone notification capture failed") }
        }
    }

    private fun applicationLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun messagingTexts(extras: Bundle): List<String> = runCatching {
        listOf(Notification.EXTRA_HISTORIC_MESSAGES, Notification.EXTRA_MESSAGES).flatMap { key ->
            val values = extras.getParcelableArray(key) ?: return@flatMap emptyList()
            if (Build.VERSION.SDK_INT >= 30) {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(values)
                    .mapNotNull { it.text?.toString() }
            } else {
                // EXTRA_MESSAGES is publicly defined as an array of message Bundles. The framework's
                // public decoder arrived in API 30, so older supported releases read its text field.
                values.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }
            }
        }
    }.getOrDefault(emptyList())
}
