package dev.fl1nt.ember.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.fl1nt.ember.api.FlareOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Recognizes addresses on the device's local network. */
object LocalAddresses {
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Decides from the host text alone, without a DNS lookup. */
    fun isLocalHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (normalized.endsWith(".local")) return true
        val literal = when {
            IPV4.matches(normalized) -> runCatching { InetAddress.getByName(normalized) }.getOrNull()
            normalized.contains(':') -> runCatching { InetAddress.getByName(normalized) }.getOrNull()
            else -> null
        } ?: return false
        return isLocal(literal)
    }

    /** Private IPv4 ranges, link-local addresses, and IPv6 unique local addresses. */
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress
        is Inet6Address -> address.isLinkLocalAddress || address.isSiteLocalAddress ||
            (address.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    /** Resolves a name such as `flare.home.arpa`. DNS itself needs no permission. */
    suspend fun resolvesToLocal(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).any(::isLocal) }.getOrDefault(false)
    }
}

/**
 * Android 17 asks people before an app may reach devices on their local network.
 * Many Flare servers run on a home network, so Ember checks whether the
 * connected address is local and asks for access only then.
 */
class LocalNetworkAccess(private val context: Context) {

    /** Whether this device and Ember's target SDK enforce the permission. */
    val isEnforced: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && isEnforced) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    suspend fun isLocal(origin: FlareOrigin): Boolean =
        LocalAddresses.isLocalHost(origin.host) || LocalAddresses.resolvesToLocal(origin.host)

    /** True when reaching [origin] needs a permission Ember doesn't have yet. */
    suspend fun needsPermission(origin: FlareOrigin): Boolean = isEnforced && !isGranted() && isLocal(origin)

    companion object {
        val PERMISSION: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) Manifest.permission.ACCESS_LOCAL_NETWORK else null
    }
}
