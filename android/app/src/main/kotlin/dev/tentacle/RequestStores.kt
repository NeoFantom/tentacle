package dev.tentacle

import android.content.Context
import dev.tentacle.core.RequestStore
import java.io.File

/** Keeps the UI and HTTP server on the same process-wide request store. */
object RequestStores {
    @Volatile private var instance: RequestStore? = null

    fun get(context: Context): RequestStore = instance ?: synchronized(this) {
        instance ?: RequestStore(File(context.applicationContext.filesDir, "requests")).also { instance = it }
    }
}
