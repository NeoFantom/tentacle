package dev.tentacle.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import dev.tentacle.AppLanguage
import dev.tentacle.AppLog
import dev.tentacle.R
import dev.tentacle.Settings
import dev.tentacle.codes.CodeStores
import dev.tentacle.core.NotifyDelivery
import dev.tentacle.core.NotifyMessage
import dev.tentacle.core.NotifyStore
import dev.tentacle.core.NotifyHistory
import dev.tentacle.core.Redactor
import dev.tentacle.core.AlertQuestionStore
import java.io.File

/** Framework delivery boundary. All alarm resources and callbacks are owned by the main thread. */
object PhoneNotifications {
    const val NORMAL_CHANNEL = "computer_messages"
    const val URGENT_CHANNEL = UrgentChannel.ID
    const val EXTRA_ID = "notify_id"
    const val ACTION_ACK = "dev.tentacle.notify.ACK"
    const val ACTION_DISMISS = "dev.tentacle.notify.DISMISS"
    private const val EXTRA_FORCED_MARKER = "dev.tentacle.notify.FORCED"
    private const val NOTIFICATION_ID = 2
    private val handler = Handler(Looper.getMainLooper())
    private val deadlines = AlertDeadline(SystemClock::elapsedRealtime)
    private val timeouts = AlarmTimeouts(deadlines,
        schedule = { task, delay -> handler.postDelayed(task, delay) },
        remove = { task -> handler.removeCallbacks(task) })
    private val observers = mutableSetOf<(String) -> Unit>()
    private var activeId: String? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var alarmWakeLock: PowerManager.WakeLock? = null
    @Volatile private var store: NotifyStore? = null
    @Volatile private var questionStore: AlertQuestionStore? = null
    @Volatile private var historyStore: NotifyHistory? = null

    fun history(context: Context): NotifyHistory = historyStore ?: synchronized(this) {
        historyStore ?: NotifyHistory(File(context.applicationContext.filesDir, "notify-history.json"))
            .also { historyStore = it }
    }

    fun get(context: Context): NotifyStore = store ?: synchronized(this) {
        store ?: run {
            val app = context.applicationContext
            createChannels(app)
            NotifyStore(
                prepare = { message ->
                    val decision = NotifyPolicy.decide(message.requestedLevel, permissions(app))
                    NotifyDelivery(level = decision.level, downgraded = decision.downgraded,
                        shown = decision.shown, reason = decision.reason)
                },
                deliver = { message, planned ->
                    val result = if (!planned.shown) planned else try {
                        post(app, message, planned)
                        planned
                    } catch (_: SecurityException) {
                        planned.copy(shown = false, reason = "notifications_disabled")
                    } catch (error: RuntimeException) {
                        AppLog.append("notify delivery failed type=${error.javaClass.simpleName}")
                        planned.copy(shown = false, reason = "notification_failed")
                    }
                    runCatching { history(app).record(message, result) }.onFailure {
                        AppLog.append("notify history could not be saved")
                    }
                    AppLog.append(NotifyLog.delivery(result.level, result.shown, result.downgraded, message.text,
                        Redactor(currentSecrets = { listOf(Settings(app).token) },
                            currentCodes = { CodeStores.get(app).codesForRedaction() })))
                    result
                },
            )
        }.also { store = it }
    }

    fun createChannels(context: Context) {
        val localized = AppLanguage.wrap(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NORMAL_CHANNEL,
            localized.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT))
        manager.deleteNotificationChannel(UrgentChannel.REPLACED_ID)
        manager.createNotificationChannel(NotificationChannel(URGENT_CHANNEL,
            localized.getString(R.string.notify_ring_channel), UrgentChannel.IMPORTANCE).apply {
            setSound(UrgentChannel.sound, null)
            enableVibration(UrgentChannel.VIBRATION_ENABLED)
            lockscreenVisibility = UrgentChannel.LOCKSCREEN_VISIBILITY
            // Do not request or force DND bypass. The owner controls the channel.
        })
    }

    internal fun permissions(context: Context): NotifyPermissions {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotifyPermissions(
            urgentAllowed = Settings(context).urgentAllowed,
            forcedAllowed = Settings(context).forcedAllowed,
            notificationsAllowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
            normalChannelAllowed = manager.getNotificationChannel(NORMAL_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
            urgentChannelAllowed = manager.getNotificationChannel(URGENT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
            fullScreenAllowed = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent(),
        )
    }

    private fun post(context: Context, message: NotifyMessage, delivery: NotifyDelivery, quiet: Boolean = false) {
        val localized = AppLanguage.wrap(context)
        val alarm = NotifyPolicy.usesAppAlarm(delivery.level, quiet)
        val forced = delivery.level == "forced"
        val tapActivity = if (forced) ForcedAlertActivity::class.java else NotificationOpenActivity::class.java
        val tap = PendingIntent.getActivity(context, 0,
            Intent(context, tapActivity).setData(uri(message.id, "tap"))
                .putExtra(EXTRA_ID, message.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(localized, if (delivery.level == "normal") NORMAL_CHANNEL else URGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(Notification.BigTextStyle().bigText(message.text))
            .setContentIntent(tap)
            .setDeleteIntent(broadcast(context, message.id, ACTION_DISMISS))
            .addExtras(Bundle().apply { putBoolean(EXTRA_FORCED_MARKER, forced) })
            .setAutoCancel(!forced)
            .setOngoing(forced && (!quiet || ForcedAlarm.isRinging(message.id)))
            .setCategory(if (delivery.level == "normal") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_ALARM)
        if (delivery.level == "urgent") {
            builder.addAction(Notification.Action.Builder(null, localized.getString(R.string.notify_acknowledge),
                broadcast(context, message.id, ACTION_ACK)).build())
        }
        if (alarm && permissions(context).fullScreenAllowed) {
            val activity = if (forced) ForcedAlertActivity::class.java else UrgentAlertActivity::class.java
            val fullScreen = PendingIntent.getActivity(context, 0,
                Intent(context, activity).setData(uri(message.id, "alert"))
                    .putExtra(EXTRA_ID, message.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setFullScreenIntent(fullScreen, true)
        }
        if (quiet) builder.setOnlyAlertOnce(true)
        context.getSystemService(NotificationManager::class.java).notify(message.id, NOTIFICATION_ID, builder.build())
        if (alarm) {
            handler.post {
                val entry = runCatching { get(context).get(message.id) }.getOrNull()
                if (entry?.state != "shown") return@post
                if (forced) {
                    val settings = Settings(context)
                    if (!settings.forcedAllowed) {
                        abortForced(context, message.id, "dismissed", "forced alert disabled before start")
                        return@post
                    }
                    val currentPermissions = permissions(context)
                    if (!currentPermissions.notificationsAllowed || !currentPermissions.urgentChannelAllowed) {
                        abortForced(context, message.id, "expired", "forced alert notification unavailable before start")
                        return@post
                    }
                    val started = runCatching {
                        ForcedAlarm.start(
                            context, message.id, settings.forcedCapMinutes, settings.forcedVolumePercent,
                            onTimeout = { timeoutForced(context, it) },
                            onAllStopped = { refreshRingVibration(context) },
                        )
                    }.getOrElse {
                        ForcedAlarm.stop(context, message.id)
                        refreshRingVibration(context)
                        false
                    }
                    if (!started) {
                        abortForced(context, message.id, "expired", "forced alert could not save alarm volume")
                    } else {
                        // ForcedAlarm has replaced the application-wide vibration pattern.
                        vibrator = null
                    }
                } else {
                    deadlines.register(message.id)
                    timeouts.arm(message.id) { stopAlarm(context, message.id, quiet = true) }
                    startAlarm(context, message.id)
                }
            }
        }
    }

    private fun broadcast(context: Context, id: String, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, NotifyReceiver::class.java).setAction(action).setData(uri(id, action)).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun respond(context: Context, id: String, state: String) {
        // Activities and receivers run here; serialize alarm cleanup with the timeout callback.
        handler.post {
            val entry = runCatching { get(context).get(id) }.getOrNull() ?: return@post
            if (entry.delivery.level == "forced") return@post
            val changed = runCatching { get(context).transition(id, state) }.getOrNull()
            stopAlarm(context, id, quiet = false)
            context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
            if (changed != null) AppLog.append("notify id=$id state=${changed.state}")
        }
    }

    fun questions(context: Context): AlertQuestionStore = questionStore ?: synchronized(this) {
        questionStore ?: AlertQuestionStore(File(context.applicationContext.filesDir, "notify-questions.json"))
            .also { questionStore = it }
    }

    fun recoverSavedVolume(context: Context) {
        ForcedAlarm.recoverSavedVolume(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter {
            it.id == NOTIFICATION_ID && it.notification.extras.getBoolean(EXTRA_FORCED_MARKER, false)
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun abortForced(context: Context, id: String, state: String, logMessage: String) {
        val changed = if (state == "dismissed") {
            runCatching { get(context).transitionForcedDismissed(id) }.getOrNull()
        } else {
            runCatching { get(context).transition(id, state) }.getOrNull()
        }
        context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
        observers.toList().forEach { it(id) }
        AppLog.append("$logMessage id=$id state=${changed?.state ?: "unchanged"}")
    }

    fun answerForced(context: Context, id: String) {
        handler.post {
            val entry = runCatching { get(context).get(id) }.getOrNull() ?: return@post
            if (entry.delivery.level != "forced" || entry.state !in setOf("shown", "timeout")) return@post
            val changed = runCatching { get(context).transitionForcedAnswered(id) }.getOrNull() ?: return@post
            val forcedIds = ForcedAlarm.stopAll(context)
            refreshRingVibration(context)
            (forcedIds + changed.orEmpty().map { it.message.id } + id).distinct().forEach {
                context.getSystemService(NotificationManager::class.java).cancel(it, NOTIFICATION_ID)
                observers.toList().forEach { observer -> observer(it) }
            }
            changed?.forEach { AppLog.append("notify id=${it.message.id} state=${it.state}") }
        }
    }

    private fun timeoutForced(context: Context, id: String) {
        val changed = runCatching { get(context).transition(id, "timeout") }.getOrNull()
        val entry = runCatching { get(context).get(id) }.getOrNull()
        if (entry != null) runCatching { post(context, entry.message, entry.delivery, quiet = true) }
        observers.toList().forEach { it(id) }
        if (changed != null) AppLog.append("notify id=$id state=${changed.state}")
    }

    fun dismissForcedFromSettings(context: Context) {
        handler.post {
            val ids = ForcedAlarm.stopAll(context)
            refreshRingVibration(context)
            ids.forEach { id ->
                runCatching { get(context).transitionForcedDismissed(id) }
                context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
                observers.toList().forEach { it(id) }
            }
        }
    }

    fun onNotificationDismissed(context: Context, id: String) {
        handler.post {
            val entry = runCatching { get(context).get(id) }.getOrNull() ?: return@post
            if (entry.delivery.level == "forced" && entry.state == "shown") {
                // Some Android builds permit an ongoing notification to be swiped; put it back without alerting.
                runCatching { post(context, entry.message, entry.delivery, quiet = true) }
            } else if (entry.delivery.level == "forced") {
                context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
            } else {
                respond(context, id, "dismissed")
            }
        }
    }

    internal fun observeStops(observer: (String) -> Unit): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    internal fun startAlarm(context: Context, id: String) {
        if (activeId == id) return
        activeId?.let { stopAlarm(context, it, quiet = true) }
        val entry = runCatching { get(context).get(id) }.getOrNull() ?: return
        if (entry.state != "shown" || entry.delivery.level != "urgent" || !Settings(context).urgentAllowed ||
            !permissions(context).notificationsAllowed || !permissions(context).urgentChannelAllowed || deadlines.remaining(id) <= 0) return
        timeouts.arm(id) { stopAlarm(context.applicationContext, id, quiet = true) }
        activeId = id
        alarmWakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tentacle:urgent-alert").apply {
                // Keep the two-minute stop timer running even when the screen goes back to sleep.
                acquire(deadlines.remaining(id).coerceAtLeast(1))
            }
        val style = Settings(context).ringStyle
        val alarmVolume = context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        val sound = if (UrgentAlarm.shouldPlaySound(style, alarmVolume)) UrgentAlarm.sound(
            alarm = { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) },
            notification = { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) },
            ringtone = { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) },
        ) else null
        sound?.let {
            val media = MediaPlayer()
            player = media
            try {
                media.setAudioAttributes(alarmAttributes())
                media.setDataSource(context, sound)
                media.isLooping = true
                media.setOnErrorListener { failed, _, _ ->
                    // Audio failure must not cancel vibration or the remaining alarm budget.
                    failed.release()
                    if (player === failed) player = null
                    AppLog.append("notify alarm audio unavailable")
                    true
                }
                media.prepare()
                media.start()
            } catch (_: Exception) {
                media.release()
                player = null
                AppLog.append("notify alarm audio unavailable")
            }
        }
        refreshRingVibration(context)
    }

    internal fun stopAlarm(context: Context, id: String, quiet: Boolean) {
        timeouts.cancel(id)
        if (activeId == id) {
            runCatching { player?.release() }
            player = null
            if (UrgentAlarm.mayCancelVibrator(ForcedAlarm.hasActive())) runCatching { vibrator?.cancel() }
            vibrator = null
            alarmWakeLock?.let { if (it.isHeld) it.release() }
            alarmWakeLock = null
            activeId = null
        }
        if (quiet) {
            val entry = runCatching { get(context).get(id) }.getOrNull()
            if (entry?.state == "shown") runCatching {
                // Replace with a quiet normal notification. App cancellation does not fire the dismiss intent.
                context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
                post(context, entry.message, entry.delivery, quiet = true)
            }
        }
        observers.toList().forEach { it(id) }
    }

    fun stopAlarms(context: Context) {
        handler.post {
            (timeouts.ids + listOfNotNull(activeId)).distinct().forEach {
                stopAlarm(context, it, quiet = true)
            }
        }
    }

    fun stopForcedAlarms(context: Context) = dismissForcedFromSettings(context)

    private fun refreshRingVibration(context: Context) {
        if (activeId == null || vibrator != null ||
            !UrgentAlarm.shouldVibrate(Settings(context).ringStyle, ForcedAlarm.hasActive())) return
        @Suppress("DEPRECATION")
        val motor = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator = motor
        runCatching { motor.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 400), 0), alarmAttributes()) }
    }

    private fun alarmAttributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private fun uri(id: String, action: String): Uri = Uri.Builder().scheme("tentacle-notify").authority("event")
        .appendPath(id).appendPath(action).build()
}
