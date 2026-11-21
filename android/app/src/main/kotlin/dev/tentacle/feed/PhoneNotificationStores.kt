package dev.tentacle.feed

import android.content.Context
import dev.tentacle.AppLog
import dev.tentacle.Settings
import dev.tentacle.core.PhoneNotificationInput
import dev.tentacle.core.PhoneNotificationStore
import java.io.File
import java.util.concurrent.Executors

/** Serializes all access that can load or persist the phone-notification feed. */
object PhoneNotificationStores {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "phone-notification-store").apply { isDaemon = true }
    }

    @Volatile private var instance: PhoneNotificationStore? = null

    /** Runs notification-listener work in arrival order and keeps it off the listener callback thread. */
    internal fun dispatch(action: () -> Unit) {
        executor.execute {
            runCatching(action)
                .onFailure { AppLog.append("Phone notification dispatch failed") }
        }
    }

    /** Call only from a background thread. Use [execute] from UI or listener callbacks. */
    fun get(context: Context): PhoneNotificationStore {
        val app = context.applicationContext
        val store = instance ?: synchronized(this) {
            instance ?: PhoneNotificationStore(
                directory = File(app.filesDir, "phone-notifications"),
                initialEnabled = Settings(app).phoneNotificationsEnabled,
            ).also { instance = it }
        }
        val enabled = Settings(app).phoneNotificationsEnabled
        if (store.isEnabled != enabled) store.setEnabled(enabled)
        return store
    }

    fun execute(context: Context, action: (PhoneNotificationStore) -> Unit) {
        val app = context.applicationContext
        dispatch {
            runCatching { action(get(app)) }
                .onFailure { AppLog.append("Phone notification store operation failed") }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        execute(context) { store ->
            store.setEnabled(enabled)
            AppLog.append(
                if (enabled) "Phone notification sharing enabled"
                else "Phone notification sharing disabled; feed cleared",
            )
        }
    }

    /** For the listener's already-serialized dispatch block. */
    internal fun captureNow(context: Context, input: PhoneNotificationInput) {
        val settings = Settings(context)
        if (!settings.phoneNotificationsEnabled) return
        val captured = get(context).capture(
            input = input,
            includeOngoing = settings.phoneNotificationsIncludeOngoing,
            excludedPackages = settings.phoneNotificationsExcludedPackages,
            ownPackage = context.packageName,
        )
        if (captured != null) AppLog.append("Phone notification captured")
    }

    /** For the listener's already-serialized dispatch block. */
    internal fun markRemovedNow(context: Context, key: String) {
        if (!Settings(context).phoneNotificationsEnabled) return
        if (get(context).markRemoved(key) != null) AppLog.append("Phone notification removal observed")
    }
}
