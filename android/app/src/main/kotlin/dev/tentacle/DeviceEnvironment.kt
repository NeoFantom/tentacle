package dev.tentacle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import dev.tentacle.core.StorageRoot
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.TimeZone

object DeviceEnvironment {
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    val timezoneId: String get() = TimeZone.getDefault().id

    fun allFilesAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun roots(context: Context): List<StorageRoot> = if (Build.VERSION.SDK_INT >= 30) {
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { volume ->
                val directory = volume.directory ?: return@mapNotNull null
                val id = if (volume.isPrimary) "primary" else volume.uuid ?: return@mapNotNull null
                StorageRoot(id, directory, volume.getDescription(context))
            }.sortedBy { if (it.id == "primary") 0 else 1 }
    } else {
        listOf(StorageRoot("primary", Environment.getExternalStorageDirectory(), context.getString(R.string.internal_storage)))
    }

    fun addresses(context: Context): List<Pairing.Address> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val lanInterfaces = try {
            manager.allNetworks.mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    manager.getLinkProperties(network)?.interfaceName
                } else null
            }.toSet()
        } catch (e: SecurityException) {
            Log.w("tentacle", "Cannot classify network transports", e)
            emptySet()
        }
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: SocketException) {
            Log.w("tentacle", "Cannot enumerate network interfaces", e)
            emptyList()
        }
        return Pairing.sorted(interfaces.flatMap { network ->
            try {
                if (!network.isUp || network.isLoopback) emptyList()
                else network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
                    .map { Pairing.address(it.hostAddress!!, network.name, network.name in lanInterfaces) }
            } catch (e: SocketException) {
                Log.w("tentacle", "Cannot inspect network interface", e)
                emptyList()
            }
        })
    }
}
