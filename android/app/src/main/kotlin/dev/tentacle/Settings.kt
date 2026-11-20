package dev.tentacle

import android.content.Context
import dev.tentacle.core.Tokens

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

    var tunnelEnabled: Boolean
        get() = prefs.getBoolean("tunnel_enabled", false)
        set(value) { prefs.edit().putBoolean("tunnel_enabled", value).apply() }

    var tunnelHost: String
        get() = prefs.getString("tunnel_host", "")!!
        set(value) { prefs.edit().putString("tunnel_host", value).apply() }

    var tunnelPort: Int
        get() = prefs.getInt("tunnel_port", DEFAULT_TUNNEL_PORT)
        set(value) { prefs.edit().putInt("tunnel_port", value).apply() }

    var tunnelUser: String
        get() = prefs.getString("tunnel_user", null) ?: DEFAULT_TUNNEL_USER
        set(value) { prefs.edit().putString("tunnel_user", value).apply() }

    var tunnelRemotePort: Int
        get() = prefs.getInt("tunnel_remote_port", DEFAULT_REMOTE_PORT)
        set(value) { prefs.edit().putInt("tunnel_remote_port", value).apply() }

    val trustedHostKey: String
        get() = prefs.getString("trusted_host_key", "")!!

    val trustedHostFingerprint: String
        get() = prefs.getString("trusted_host_fingerprint", "")!!

    val trustedHostEndpoint: String
        get() = prefs.getString("trusted_host_endpoint", "")!!

    @android.annotation.SuppressLint("ApplySharedPref") // Persist the complete pin before using the connection.
    fun trustHostKey(endpoint: String, key: String, fingerprint: String) {
        check(prefs.edit().putString("trusted_host_endpoint", endpoint)
            .putString("trusted_host_key", key).putString("trusted_host_fingerprint", fingerprint).commit())
    }

    var tailnetEnabled: Boolean
        get() = prefs.getBoolean("tailnet_enabled", false)
        set(value) { prefs.edit().putBoolean("tailnet_enabled", value).apply() }

    /** Empty selects Tailscale's default control server; any Headscale URL works. */
    var tailnetControlUrl: String
        get() = prefs.getString("tailnet_control_url", "")!!
        set(value) { prefs.edit().putString("tailnet_control_url", value).apply() }

    /** Optional pre-auth key for the first login. Never displayed; cleared once logged in. */
    var tailnetAuthKey: String
        get() = prefs.getString("tailnet_auth_key", "")!!
        set(value) { prefs.edit().putString("tailnet_auth_key", value).apply() }

    var tailnetHostname: String
        get() = prefs.getString("tailnet_hostname", null)?.takeIf { it.isNotEmpty() }
            ?: dev.tentacle.tailnet.TailnetLogic.defaultHostname(android.os.Build.MODEL ?: "")
        set(value) { prefs.edit().putString("tailnet_hostname", value).apply() }

    /** Control URL the saved node identity belongs to. */
    var tailnetStateControlUrl: String?
        get() = prefs.getString("tailnet_state_control_url", null)
        set(value) { prefs.edit().putString("tailnet_state_control_url", value).apply() }

    var publicUrl: String
        get() = prefs.getString("public_url", "")!!
        set(value) { prefs.edit().putString("public_url", value).apply() }

    var language: String
        get() = prefs.getString("language", "")!!
        set(value) { prefs.edit().putString("language", value).apply() }

    var updateSource: String
        get() = prefs.getString("update_source", null)
            ?: BuildConfig.UPDATE_REPO.takeIf { it.isNotEmpty() }
                ?.let { "https://api.github.com/repos/$it/releases/latest" }.orEmpty()
        set(value) { prefs.edit().putString("update_source", value).apply() }

    var dismissedUpdateVersion: String
        get() = prefs.getString("dismissed_update_version", "")!!
        set(value) { prefs.edit().putString("dismissed_update_version", value).apply() }

    var lastAutomaticUpdateCheckMillis: Long
        get() = prefs.getLong("last_automatic_update_check_millis", 0L)
        set(value) { prefs.edit().putLong("last_automatic_update_check_millis", value).apply() }

    /** Xiaomi autostart cannot be detected; the "needs attention" item hides once its page was opened. */
    var autostartOpened: Boolean
        get() = prefs.getBoolean("autostart_opened", false)
        set(value) { prefs.edit().putBoolean("autostart_opened", value).apply() }

    fun wasPermissionRequested(permission: String): Boolean = prefs.getBoolean("requested:$permission", false)

    fun markPermissionRequested(permission: String) {
        prefs.edit().putBoolean("requested:$permission", true).apply()
    }

    fun markStarted() { prefs.edit().putBoolean("started_once", true).apply() }

    companion object {
        const val PREFS_NAME = "tentacle"
        const val DEFAULT_TUNNEL_USER = "tentacle"
        const val DEFAULT_TUNNEL_PORT = 22
        const val DEFAULT_REMOTE_PORT = 8765
        private val tokenLock = Any()
    }
}
