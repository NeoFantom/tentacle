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
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
import dev.tentacle.core.Redactor

/** Framework delivery boundary. All alarm resources and callbacks are owned by the main thread. */
object PhoneNotifications {
    const val NORMAL_CHANNEL = "computer_messages"
    const val URGENT_CHANNEL = "computer_urgent"
    const val EXTRA_ID = "notify_id"
    const val ACTION_ACK = "dev.tentacle.notify.ACK"
    const val ACTION_DISMISS = "dev.tentacle.notify.DISMISS"
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
        manager.createNotificationChannel(NotificationChannel(URGENT_CHANNEL,
            localized.getString(R.string.notify_urgent_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttributes())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 400, 700, 400)
            // Do not request or force DND bypass. The owner controls the channel.
        })
    }

    internal fun permissions(context: Context): NotifyPermissions {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotifyPermissions(
            urgentAllowed = Settings(context).urgentAllowed,
            notificationsAllowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
            normalChannelAllowed = manager.getNotificationChannel(NORMAL_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
            urgentChannelAllowed = manager.getNotificationChannel(URGENT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
            fullScreenAllowed = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent(),
        )
    }

    private fun post(context: Context, message: NotifyMessage, delivery: NotifyDelivery, quiet: Boolean = false) {
        val localized = AppLanguage.wrap(context)
        val urgent = delivery.level == "urgent" && !quiet
        val tap = PendingIntent.getActivity(context, 0,
            Intent(context, NotificationOpenActivity::class.java).setData(uri(message.id, "tap"))
                .putExtra(EXTRA_ID, message.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(localized, if (urgent) URGENT_CHANNEL else NORMAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(Notification.BigTextStyle().bigText(message.text))
            .setContentIntent(tap)
            .setDeleteIntent(broadcast(context, message.id, ACTION_DISMISS))
            .setAutoCancel(true)
            .setCategory(if (urgent) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
        if (delivery.level == "urgent") {
            builder.addAction(Notification.Action.Builder(null, localized.getString(R.string.notify_acknowledge),
                broadcast(context, message.id, ACTION_ACK)).build())
        }
        if (urgent && permissions(context).fullScreenAllowed) {
            val fullScreen = PendingIntent.getActivity(context, 0,
                Intent(context, UrgentAlertActivity::class.java).setData(uri(message.id, "alert"))
                    .putExtra(EXTRA_ID, message.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setFullScreenIntent(fullScreen, true)
        }
        if (quiet) builder.setOnlyAlertOnce(true)
        if (urgent) deadlines.register(message.id)
        context.getSystemService(NotificationManager::class.java).notify(message.id, NOTIFICATION_ID, builder.build())
        if (urgent) {
            handler.post {
                val entry = runCatching { get(context).get(message.id) }.getOrNull()
                if (entry?.state != "shown") return@post
                timeouts.arm(message.id) { stopAlarm(context, message.id, quiet = true) }
                // An unlocked phone may show heads-up instead of launching the full-screen activity.
                // Keep the alarm continuous in that case too. Without full-screen approval, use
                // only the system's heads-up sound, as reported in the delivery response.
                if (permissions(context).fullScreenAllowed) startAlarm(context, message.id)
            }
        }
    }

    private fun broadcast(context: Context, id: String, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, NotifyReceiver::class.java).setAction(action).setData(uri(id, action)).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun respond(context: Context, id: String, state: String) {
        // Activities and receivers run here; serialize alarm cleanup with the timeout callback.
        handler.post {
            val changed = runCatching { get(context).transition(id, state) }.getOrNull()
            stopAlarm(context, id, quiet = false)
            context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)
            if (changed != null) AppLog.append("notify id=$id state=${changed.state}")
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
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(URGENT_CHANNEL)
        // Respect the owner's sound and vibration choices for the channel.
        channel?.sound?.let { sound ->
            val media = MediaPlayer()
            player = media
            try {
                media.setAudioAttributes(alarmAttributes())
                media.setDataSource(context, sound)
                media.isLooping = true
                media.setOnErrorListener { _, _, _ -> stopAlarm(context, id, quiet = true); true }
                media.prepare()
                media.start()
            } catch (_: Exception) {
                media.release()
                player = null
                AppLog.append("notify alarm audio unavailable")
            }
        }
        if (channel?.shouldVibrate() == true) {
            @Suppress("DEPRECATION")
            val motor = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = motor
            runCatching { motor.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 400), 0), alarmAttributes()) }
        }
    }

    internal fun stopAlarm(context: Context, id: String, quiet: Boolean) {
        timeouts.cancel(id)
        if (activeId == id) {
            runCatching { player?.release() }
            player = null
            runCatching { vibrator?.cancel() }
            vibrator = null
            alarmWakeLock?.let { if (it.isHeld) it.release() }
            alarmWakeLock = null
            activeId = null
        }
        if (quiet) {
            val entry = runCatching { get(context).get(id) }.getOrNull()
            if (entry?.state == "shown") runCatching {
                // Replace, don't update: an updated record keeps playing the urgent channel's alarm
                // sound in the system. Cancelling by the app does not fire the dismiss intent.
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

    private fun alarmAttributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private fun uri(id: String, action: String): Uri = Uri.Builder().scheme("tentacle-notify").authority("event")
        .appendPath(id).appendPath(action).build()
}
