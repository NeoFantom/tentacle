package dev.tentacle

import android.app.Application

class TentacleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings(this).recordInstalledVersion()
        AppLog.initialize(this)
        dev.tentacle.notify.PhoneNotifications.recoverSavedVolume(this)
        RequestStores.get(this)
        dev.tentacle.codes.CodeStores.get(this)
        dev.tentacle.notify.PhoneNotifications.get(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(AppLog::append, previous))
    }
}
