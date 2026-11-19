package dev.tentacle.core

import java.security.SecureRandom
import java.util.Base64

object Tokens {
    fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
