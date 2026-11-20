package dev.tentacle

import android.app.Application

class TentacleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)
        RequestStores.get(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(AppLog::append, previous))
    }
}
