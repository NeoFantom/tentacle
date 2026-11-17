package dev.phone2agent

import android.content.Context
import dev.phone2agent.core.Tokens

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val token: String
        get() = synchronized(tokenLock) {
            prefs.getString("token", null) ?: Tokens.generate().also {
                prefs.edit().putString("token", it).apply()
            }
        }

    fun regenerateToken(): String = synchronized(tokenLock) {
        Tokens.generate().also { prefs.edit().putString("token", it).apply() }
    }

    var port: Int
        get() = prefs.getInt("port", 8765)
        set(value) {
            require(value in 1024..65535) { "Port must be between 1024 and 65535" }
            prefs.edit().putInt("port", value).apply()
        }

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", prefs.getBoolean("started_once", false))
        set(value) { prefs.edit().putBoolean("start_on_boot", value).apply() }

    var keepAwake: Boolean
        get() = prefs.getBoolean("keep_awake", false)
        set(value) { prefs.edit().putBoolean("keep_awake", value).apply() }

    fun wasPermissionRequested(permission: String): Boolean = prefs.getBoolean("requested:$permission", false)

    fun markPermissionRequested(permission: String) {
        prefs.edit().putBoolean("requested:$permission", true).apply()
    }

    fun markStarted() { prefs.edit().putBoolean("started_once", true).apply() }

    companion object {
        const val PREFS_NAME = "phone2agent"
        private val tokenLock = Any()
    }
}
