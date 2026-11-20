package dev.tentacle.codes

import android.content.Context
import dev.tentacle.AppLog
import dev.tentacle.Settings
import dev.tentacle.core.CodeStore

/** One memory-only store shared by capture sources, the settings screen and HTTP workers. */
object CodeStores {
    @Volatile private var instance: CodeStore? = null

    fun get(context: Context): CodeStore = instance ?: synchronized(this) {
        instance ?: CodeStore(initialEnabled = Settings(context.applicationContext).codesEnabled)
            .also { instance = it }
    }

    fun capture(context: Context, text: String, sender: String, source: String) {
        if (!Settings(context).codesEnabled) return
        CodeCaptureLog.capture(get(context), text, sender, source, AppLog::append)
    }
}
