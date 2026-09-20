package com.app.youtube.lite.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Immutable
import com.app.youtube.lite.core.util.L
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A snapshot of the network the app is on, flattened into values the UI and the player actually
 * branch on.
 *
 * `@Immutable` matters here: this object is read by Compose, and an immutable data class lets the
 * runtime skip recomposition of everything that depends on it unless one of these four values
 * really changed.
 */
@Immutable
data class NetworkStatus(
    val online: Boolean = true,
    val validated: Boolean = true,
    val unmetered: Boolean = true,
    val vpn: Boolean = false,
) {
    /** Metered + mobile: the case that justifies capping quality and skipping prefetches. */
    val shouldConserveData: Boolean get() = online && !unmetered
}

/**
 * Tracks connectivity with a [NetworkRequest] callback rather than polling.
 *
 * Polling `ConnectivityManager` on a timer is the classic mistake: it costs a wakeup every few
 * seconds and still misses transitions. A registered callback costs nothing while the network is
 * stable and fires exactly when it changes — which is precisely when the UI wants to switch from
 * "Loading…" to "You are offline", and when the player wants to know that a stall is the
 * network's fault rather than the CDN's.
 *
 * The callback is registered lazily: the flow only exists while something is collecting it, and
 * `awaitClose` guarantees the unregister happens, so a backgrounded app is not registered at all.
 */
class NetworkMonitor(private val context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    val status: Flow<NetworkStatus> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            trySend(NetworkStatus())
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(read(connectivity))
            }

            override fun onLost(network: Network) {
                trySend(read(connectivity))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(read(connectivity))
            }
        }

        // Default network only: a VPN or a second SIM's idle network is not what the app uses.
        trySend(read(connectivity))
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { L.w("NetworkMonitor") { "could not register network callback: $it" } }

        awaitClose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }.conflate().distinctUntilChanged()

    private fun read(connectivity: ConnectivityManager): NetworkStatus {
        val network = connectivity.activeNetwork ?: return NetworkStatus(online = false)
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return NetworkStatus(online = false)
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return NetworkStatus(online = false)
        return NetworkStatus(
            online = true,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    companion object {
        /** Cheap synchronous check for callers outside a composition (e.g. prefetch decisions). */
        fun isOnline(context: Context): Boolean {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
