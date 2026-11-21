package dev.tentacle.notify

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import dev.tentacle.AppLog
import dev.tentacle.Settings
import kotlin.math.PI
import kotlin.math.sin

/** Owns forced-alarm resources independently of any Activity lifecycle. Calls run on the main thread. */
internal object ForcedAlarm {
    private const val SAVED_STREAM = "forced_saved_stream"
    private const val SAVED_INDEX = "forced_saved_volume_index"
    private const val REAPPLY_MS = 2_000L
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutTasks = linkedMapOf<String, Runnable>()
    private val sessions = ForcedAlarmSessions()
    private var player: MediaPlayer? = null
    private var beep: AudioTrack? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var targetIndex: Int? = null
    private var loggedVolumeFailure = false
    private var appContext: Context? = null

    private val reapply = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            val target = targetIndex ?: return
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < target) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
                checkApplied(audio, target)
            }
            handler.postDelayed(this, REAPPLY_MS)
        }
    }

    fun start(
        context: Context,
        id: String,
        capMinutes: Int,
        volumePercent: Int,
        onTimeout: (String) -> Unit,
        onAllStopped: () -> Unit,
    ): Boolean {
        require(capMinutes in setOf(5, 10, 15, 20, 30))
        require(volumePercent in 10..100)
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_ALARM) else 1
        val target = AlarmVolume.targetIndex(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), minimum, volumePercent)
        val capMs = capMinutes * 60_000L
        val deadline = SystemClock.elapsedRealtime() + capMs
        if (!beginForcedSession(savedVolume(app), audio.getStreamVolume(AudioManager.STREAM_ALARM),
                sessions, id, deadline)) return false

        val timeout = Runnable {
            timeoutTasks.remove(id)
            sessions.stop(id)
            onTimeout(id)
            if (timeoutTasks.isEmpty()) {
                releaseAndRestore(app)
                onAllStopped()
            } else refreshWakeLock(app)
        }
        timeoutTasks[id] = timeout
        handler.postDelayed(timeout, capMs)
        appContext = app
        targetIndex = target
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
        checkApplied(audio, target)
        if (player == null && beep == null) startSound(app)
        if (vibrator == null) startVibration(app)
        refreshWakeLock(app)
        handler.removeCallbacks(reapply)
        handler.postDelayed(reapply, REAPPLY_MS)
        return true
    }

    fun stop(context: Context, id: String) {
        timeoutTasks.remove(id)?.let(handler::removeCallbacks)
        sessions.stop(id)
        if (timeoutTasks.isEmpty()) releaseAndRestore(context.applicationContext)
        else refreshWakeLock(context.applicationContext)
    }

    fun stopAll(context: Context): List<String> {
        val ids = timeoutTasks.keys.toList()
        timeoutTasks.values.forEach(handler::removeCallbacks)
        timeoutTasks.clear()
        sessions.clear()
        releaseAndRestore(context.applicationContext)
        return ids
    }

    fun isRinging(id: String): Boolean = id in timeoutTasks
    fun hasActive(): Boolean = timeoutTasks.isNotEmpty()

    /** Repairs a volume left changed when Android killed the process during a forced alert. */
    fun recoverSavedVolume(context: Context) {
        restoreSavedVolume(context.applicationContext)
    }

    @SuppressLint("ApplySharedPref") // The original index must survive a kill before volume changes.
    private fun savedVolume(context: Context): SavedAlarmVolume {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return SavedAlarmVolume(
            read = { prefs.takeIf { it.contains(SAVED_INDEX) }?.getInt(SAVED_INDEX, 0) },
            persist = { original -> prefs.edit().putInt(SAVED_STREAM, AudioManager.STREAM_ALARM)
                .putInt(SAVED_INDEX, original).commit() },
            clear = { prefs.edit().remove(SAVED_STREAM).remove(SAVED_INDEX).commit() },
        )
    }

    @SuppressLint("ApplySharedPref") // Clear only after the exact saved volume was restored.
    private fun restoreSavedVolume(context: Context) {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val stream = prefs.getInt(SAVED_STREAM, AudioManager.STREAM_ALARM)
        val audio = context.getSystemService(AudioManager::class.java)
        val saved = savedVolume(context)
        if (!saved.restore { index -> runCatching {
                audio.setStreamVolume(stream, index, 0)
                audio.getStreamVolume(stream) == index
            }.getOrDefault(false)
        }) AppLog.append("forced volume restore pending")
    }

    private fun releaseAndRestore(context: Context) {
        handler.removeCallbacks(reapply)
        runCatching { player?.release() }
        player = null
        runCatching { beep?.stop() }
        runCatching { beep?.release() }
        beep = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        targetIndex = null
        loggedVolumeFailure = false
        appContext = null
        restoreSavedVolume(context)
    }

    private fun refreshWakeLock(context: Context) {
        if (wakeLock == null) {
            wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tentacle:forced-alert").apply {
                    setReferenceCounted(false)
                }
        }
        val now = SystemClock.elapsedRealtime()
        val remaining = (sessions.latestDeadline() ?: now) - now
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
            lock.acquire(remaining.coerceAtLeast(1L) + REAPPLY_MS)
        }
    }

    private fun checkApplied(audio: AudioManager, target: Int) {
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < target && !loggedVolumeFailure) {
            AppLog.append("forced volume not applied")
            loggedVolumeFailure = true
        }
    }

    private fun startSound(context: Context) {
        val candidates = listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ).filterNotNull()
        for (uri in candidates) {
            val media = MediaPlayer()
            try {
                media.setAudioAttributes(attributes())
                media.setDataSource(context, uri)
                media.isLooping = true
                media.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (player === failed) {
                        player = null
                        beep = runCatching { startBeep() }.getOrElse {
                            AppLog.append("forced alarm audio unavailable")
                            null
                        }
                    }
                    true
                }
                media.prepare()
                media.start()
                player = media
                return
            } catch (_: Exception) {
                media.release()
            }
        }
        beep = runCatching { startBeep() }.getOrElse {
            AppLog.append("forced alarm audio unavailable")
            null
        }
    }

    private fun generatedBeep(): AudioTrack {
        val rate = 16_000
        val seconds = 2
        val samples = ShortArray(rate * seconds)
        for (i in samples.indices) {
            val phase = i % rate
            val frequency = when (phase) {
                in 0 until 5_600 -> 880.0
                in 6_400 until 12_000 -> 1_320.0
                else -> 0.0
            }
            samples[i] = if (frequency == 0.0) 0 else (sin(2 * PI * frequency * i / rate) * 24_000).toInt().toShort()
        }
        val track = AudioTrack.Builder().setAudioAttributes(attributes())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        try {
            check(track.write(samples, 0, samples.size) == samples.size)
            check(track.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
            return track
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }

    private fun startBeep(): AudioTrack {
        val track = generatedBeep()
        try {
            track.play()
            return track
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }

    private fun startVibration(context: Context) {
        @Suppress("DEPRECATION")
        val motor = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator = motor
        runCatching { motor.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 300, 800, 300), 0), attributes()) }
    }

    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
}

/** Testable crash-safe rule: save once, clear only after a verified exact restore. */
internal class SavedAlarmVolume(
    private val read: () -> Int?,
    private val persist: (Int) -> Boolean,
    private val clear: () -> Boolean,
) {
    fun saveOnce(index: Int): Boolean = read() != null || persist(index)

    fun restore(apply: (Int) -> Boolean): Boolean {
        val original = read() ?: return true
        return apply(original) && clear()
    }
}

internal class ForcedAlarmSessions {
    private val deadlines = linkedMapOf<String, Long>()

    fun start(id: String, deadline: Long) { deadlines[id] = deadline }
    fun stop(id: String): Boolean { deadlines.remove(id); return deadlines.isEmpty() }
    fun clear() = deadlines.clear()
    fun latestDeadline(): Long? = deadlines.values.maxOrNull()
}

internal fun beginForcedSession(
    savedVolume: SavedAlarmVolume,
    originalIndex: Int,
    sessions: ForcedAlarmSessions,
    id: String,
    deadline: Long,
): Boolean {
    if (!savedVolume.saveOnce(originalIndex)) return false
    sessions.start(id, deadline)
    return true
}
