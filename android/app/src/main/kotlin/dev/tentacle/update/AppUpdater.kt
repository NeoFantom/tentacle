package dev.tentacle.update

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import dev.tentacle.BuildConfig
import dev.tentacle.MainActivity
import dev.tentacle.R
import dev.tentacle.Settings
import dev.tentacle.AppLanguage
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Disabled : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int?) : UpdateState
    data class PermissionRequired(val release: ReleaseInfo) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Error(val kind: UpdateError) : UpdateState
}

enum class UpdateError { CHECK_FAILED, DOWNLOAD_FAILED, CHECKSUM_MISMATCH, SIGNATURE_MISMATCH, INVALID_APK, INSTALL_FAILED }

object AppUpdater {
    private val listeners = CopyOnWriteArraySet<(UpdateState) -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tentacle-updates").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var application: Application? = null
    @Volatile private var foregroundActivities = 0
    @Volatile private var lastRelease: ReleaseInfo? = null
    @Volatile var state: UpdateState = UpdateState.Idle
        private set
    val available: ReleaseInfo?
        get() = when (val current = state) {
            is UpdateState.Available -> current.release
            is UpdateState.Downloading -> current.release
            is UpdateState.PermissionRequired -> current.release
            is UpdateState.Installing -> current.release
            else -> lastRelease
        }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext as Application
        application = app
        val localized = AppLanguage.wrap(app)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localized.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foregroundActivities++ }
            override fun onActivityStopped(activity: Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
                if (foregroundActivities == 0) (state as? UpdateState.Available)?.release?.let { notifyAvailable(app, it) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        check(app)
    }

    fun check(context: Context, manual: Boolean = false) {
        val app = context.applicationContext
        val source = Settings(app).updateSource.trim()
        if (source.isEmpty()) {
            lastRelease = null
            publish(UpdateState.Disabled)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        lastRelease = null
        publish(UpdateState.Checking)
        executor.execute {
            val result = try {
                val release = Releases.parse(readText(source))
                val currentSource = Settings(app).updateSource.trim()
                when {
                    currentSource.isEmpty() -> UpdateState.Disabled
                    currentSource != source -> UpdateState.Idle
                    Releases.compareVersions(release.version, BuildConfig.VERSION_NAME) > 0 -> {
                        lastRelease = release
                        UpdateState.Available(release)
                    }
                    else -> UpdateState.UpToDate
                }
            } catch (_: Exception) {
                UpdateState.Error(UpdateError.CHECK_FAILED)
            } finally {
                busy.set(false)
            }
            // Observers can start downloading immediately once availability is delivered.
            publish(result)
            if (result is UpdateState.Available && foregroundActivities == 0) notifyAvailable(app, result.release)
        }
    }

    fun downloadAndInstall(activity: Activity) {
        val release = available ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            publish(UpdateState.PermissionRequired(release))
            return
        }
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                val apk = download(activity.applicationContext, release)
                verifyChecksum(release, apk)
                verifyApkSignature(activity.applicationContext, apk)
                publish(UpdateState.Installing(release))
                try {
                    install(activity.applicationContext, apk, release)
                } catch (_: Exception) {
                    throw UpdateFailure(UpdateError.INSTALL_FAILED)
                }
            } catch (error: UpdateFailure) {
                publish(UpdateState.Error(error.kind))
            } catch (_: Exception) {
                publish(UpdateState.Error(UpdateError.DOWNLOAD_FAILED))
            } finally {
                busy.set(false)
            }
        }
    }

    fun openUnknownSources(activity: Activity) {
        activity.startActivity(Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
    }

    fun observe(listener: (UpdateState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    fun stateText(context: Context): String = when (val current = state) {
        UpdateState.Idle -> context.getString(R.string.update_status_idle)
        UpdateState.Disabled -> context.getString(R.string.update_status_disabled)
        UpdateState.Checking -> context.getString(R.string.update_status_checking)
        UpdateState.UpToDate -> context.getString(R.string.update_status_current)
        is UpdateState.Available -> context.getString(R.string.update_status_available, current.release.version)
        is UpdateState.Downloading -> current.percent?.let { context.getString(R.string.update_status_downloading_percent, it) }
            ?: context.getString(R.string.update_status_downloading)
        is UpdateState.PermissionRequired -> context.getString(R.string.update_status_permission_required)
        is UpdateState.Installing -> context.getString(R.string.update_status_installing)
        is UpdateState.Error -> context.getString(when (current.kind) {
            UpdateError.CHECK_FAILED -> R.string.update_error_check
            UpdateError.DOWNLOAD_FAILED -> R.string.update_error_download
            UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum
            UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
            UpdateError.INVALID_APK -> R.string.update_error_invalid_apk
            UpdateError.INSTALL_FAILED -> R.string.update_error_install
        })
    }

    internal fun isAppForeground(): Boolean = foregroundActivities > 0

    internal fun publish(next: UpdateState) {
        state = next
        mainHandler.post { listeners.forEach { it(next) } }
        val app = application
        if (app != null && next is UpdateState.Downloading) notifyProgress(app, next)
        if (app != null && (next is UpdateState.Error || next is UpdateState.Installing || next is UpdateState.UpToDate)) {
            cancelNotification(app)
        }
    }

    private fun readText(url: String): String {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) throw UpdateFailure(UpdateError.DOWNLOAD_FAILED)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(context: Context, release: ReleaseInfo): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = release.apk.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, safeName)
        val part = File(directory, "$safeName.part")
        val metadata = File(directory, "$safeName.part.meta")
        val stored = metadata.takeIf { it.isFile }?.readLines().orEmpty()
        val canResume = part.isFile && stored.getOrNull(0) == release.apk.url && !stored.getOrNull(1).isNullOrBlank() &&
            (release.apk.size < 0 || part.length() < release.apk.size)
        if (!canResume) {
            part.delete()
            metadata.delete()
        }
        val offset = if (canResume) part.length() else 0L
        var connection = open(release.apk.url) {
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("If-Range", stored[1])
            }
        }
        val append = offset > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL &&
            connection.getHeaderField("Content-Range")?.matches(Regex("bytes ${Regex.escape(offset.toString())}-\\d+/\\d+")) == true
        if (offset > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL && !append) {
            connection.disconnect()
            part.delete()
            metadata.delete()
            connection = open(release.apk.url)
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            if (responseCode == 416) {
                part.delete()
                metadata.delete()
            }
            throw UpdateFailure(UpdateError.DOWNLOAD_FAILED)
        }
        val start = if (append) offset else 0L
        if (!append) part.delete()
        metadata.writeText("${release.apk.url}\n${connection.getHeaderField("ETag").orEmpty()}\n")
        val expected = release.apk.size.takeIf { it >= 0 }
            ?: connection.contentLengthLong.takeIf { it >= 0 }?.let { start + it }
        try {
            FileOutputStream(part, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = start
                    var lastPercent: Int? = null
                    var lastPublished = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val percent = expected?.takeIf { it > 0 }?.let { ((written * 100) / it).toInt().coerceIn(0, 100) }
                        val now = System.currentTimeMillis()
                        if (percent != lastPercent || now - lastPublished >= 1_000) {
                            publish(UpdateState.Downloading(release, percent))
                            lastPercent = percent
                            lastPublished = now
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (release.apk.size >= 0 && part.length() != release.apk.size) throw UpdateFailure(UpdateError.DOWNLOAD_FAILED)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        metadata.delete()
        return target
    }

    private fun verifyChecksum(release: ReleaseInfo, apk: File) {
        val asset = release.checksum ?: return
        val expected = try { readText(asset.url).trim().split(Regex("\\s+"), limit = 2).first() } catch (_: Exception) {
            throw UpdateFailure(UpdateError.DOWNLOAD_FAILED)
        }
        if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) throw UpdateFailure(UpdateError.CHECKSUM_MISMATCH)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw UpdateFailure(UpdateError.CHECKSUM_MISMATCH)
    }

    private fun verifyApkSignature(context: Context, apk: File) {
        val manager = context.packageManager
        val archive = try { packageInfo(manager, apk.absolutePath, archive = true) } catch (_: Exception) { null }
            ?: throw UpdateFailure(UpdateError.INVALID_APK)
        if (archive.packageName != context.packageName) throw UpdateFailure(UpdateError.INVALID_APK)
        val installed = try { packageInfo(manager, context.packageName, archive = false) } catch (_: Exception) { null }
            ?: throw UpdateFailure(UpdateError.SIGNATURE_MISMATCH)
        if (signatures(archive) != signatures(installed)) throw UpdateFailure(UpdateError.SIGNATURE_MISMATCH)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(manager: PackageManager, name: String, archive: Boolean): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        if (archive) manager.getPackageArchiveInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else manager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else if (Build.VERSION.SDK_INT >= 28) {
        if (archive) manager.getPackageArchiveInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        else manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        if (archive) manager.getPackageArchiveInfo(name, PackageManager.GET_SIGNATURES)
        else manager.getPackageInfo(name, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        if (values.isEmpty()) throw UpdateFailure(UpdateError.SIGNATURE_MISMATCH)
        return values.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet()
    }

    private fun install(context: Context, apk: File, release: ReleaseInfo) {
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        })
        val session = installer.openSession(sessionId)
        try {
            session.openWrite(apk.name, 0, apk.length()).use { output -> apk.inputStream().use { it.copyTo(output) }; session.fsync(output) }
            val callback = Intent(context, UpdateInstallReceiver::class.java).putExtra(EXTRA_VERSION, release.version)
            val pending = PendingIntent.getBroadcast(context, sessionId, callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            session.abandon()
            throw error
        } finally {
            session.close()
        }
    }

    private fun open(url: String, configure: HttpURLConnection.() -> Unit = {}): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "tentacle/${BuildConfig.VERSION_NAME}")
            configure()
        }

    private fun notifyAvailable(context: Context, release: ReleaseInfo) {
        refreshNotificationChannel(context)
        val localized = AppLanguage.wrap(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_INSTALL_UPDATE, true)
        val open = PendingIntent.getActivity(context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            baseNotification(context)
                .setContentTitle(localized.getString(R.string.update_notification_available_title, release.version))
                .setContentText(localized.getString(R.string.update_notification_available_text))
                .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun notifyProgress(context: Context, downloading: UpdateState.Downloading) {
        refreshNotificationChannel(context)
        val localized = AppLanguage.wrap(context)
        val builder = baseNotification(context)
            .setContentTitle(localized.getString(R.string.update_notification_downloading, downloading.release.version))
            .setOngoing(true).setOnlyAlertOnce(true)
        downloading.percent?.let { builder.setProgress(100, it, false) } ?: builder.setProgress(0, 0, true)
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    fun refreshNotificationChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, AppLanguage.wrap(context).getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun baseNotification(context: Context): Notification.Builder = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_server).setCategory(Notification.CATEGORY_PROGRESS)

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    internal const val CHANNEL = "updates"
    internal const val NOTIFICATION_ID = 2
    internal const val INSTALL_NOTIFICATION_ID = 3
    internal const val EXTRA_VERSION = "version"
    const val EXTRA_INSTALL_UPDATE = "install_update"
}

private class UpdateFailure(val kind: UpdateError) : Exception()
