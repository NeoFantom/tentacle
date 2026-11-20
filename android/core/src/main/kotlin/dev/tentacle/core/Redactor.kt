package dev.tentacle.core

/** Removes credentials and login links before text reaches persistent logs. */
class Redactor(private val currentSecrets: () -> Iterable<String> = { emptyList() }) {
    fun redact(text: String): String {
        var result = PEM_PRIVATE_KEY.replace(text, REDACTED)
        result = AUTHORIZATION.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = BEARER.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = SENSITIVE_VALUE.replace(result) { "${it.groupValues[1]}=$REDACTED" }
        result = URL_USERINFO.replace(result) { "${it.groupValues[1]}$REDACTED@" }
        result = TAILSCALE_LOGIN.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = REGISTER_PATH.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = URL_QUERY.replace(result) { "${it.groupValues[1]}?$REDACTED" }
        result = TSKEY.replace(result, REDACTED)
        result = BASE64URL_TOKEN.replace(result, REDACTED)
        currentSecrets().filter { it.isNotEmpty() }.forEach { result = result.replace(it, REDACTED) }
        return result
    }

    private companion object {
        const val REDACTED = "[REDACTED]"
        val PEM_PRIVATE_KEY = Regex(
            "-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)* PRIVATE KEY-----",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val AUTHORIZATION = Regex("(?i)(\\bAuthorization\\s*:\\s*)[^\\r\\n]*")
        val BEARER = Regex("(?i)(\\bBearer\\s+)[^\\s,;]+")
        val SENSITIVE_VALUE = Regex("(?i)(\\b(?:token|auth_key|authkey|key|password|passwd|secret))=([^&\\s]+)")
        val URL_USERINFO = Regex("(?i)(https?://)[^/@\\s]+@")
        val TAILSCALE_LOGIN = Regex("(?i)(https?://login\\.tailscale\\.com/a/)[^/?#\\s]+")
        val REGISTER_PATH = Regex("(?i)(/register/)[^/?#\\s]+")
        val URL_QUERY = Regex("(?i)(https?://[^?\\s]+)\\?[^#\\s]*")
        val TSKEY = Regex("(?i)\\btskey-[A-Za-z0-9_-]+(?:-[A-Za-z0-9_-]+)*\\b")
        val BASE64URL_TOKEN = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])")
    }
}
