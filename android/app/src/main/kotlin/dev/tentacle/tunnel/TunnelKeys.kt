package dev.tentacle.tunnel

import android.content.Context
import android.os.Build
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.File
import java.util.Base64

object TunnelKeys {
    private const val PRIVATE_KEY_FILE = "tunnel_ecdsa"

    @Synchronized
    fun publicKey(context: Context): String {
        val jsch = JSch()
        val privateKey = privateKeyFile(context)
        val keyPair = if (privateKey.exists()) {
            KeyPair.load(jsch, privateKey.absolutePath)
        } else {
            KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256).also { generated ->
                generated.writePrivateKey(privateKey.absolutePath)
                privateKey.setReadable(false, false)
                privateKey.setWritable(false, false)
                check(privateKey.setReadable(true, true) && privateKey.setWritable(true, true)) {
                    "Cannot restrict SSH private key permissions"
                }
            }
        }
        return keyPair.useKeyPair {
            val blob = Base64.getEncoder().encodeToString(it.publicKeyBlob)
            "ecdsa-sha2-nistp256 $blob tentacle-${deviceName()}"
        }
    }

    internal fun privateKeyPath(context: Context): String {
        publicKey(context)
        return privateKeyFile(context).absolutePath
    }

    private fun privateKeyFile(context: Context) = File(context.filesDir, PRIVATE_KEY_FILE)

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
        .trim().replace(Regex("[\\r\\n]"), " ")

    private inline fun <T> KeyPair.useKeyPair(block: (KeyPair) -> T): T = try {
        block(this)
    } finally {
        dispose()
    }
}
