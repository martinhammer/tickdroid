package com.martinhammer.tickdroid.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits whether the device's default network (the one the system uses for apps' traffic) has
 * the `INTERNET` capability.
 *
 * The initial value is read synchronously once, before registering. After that the state comes
 * only from the callbacks' own arguments ([DefaultNetworkTracker]). Never read `activeNetwork`
 * or `getNetworkCapabilities` inside a callback: Android warns they can return outdated
 * results, and in `onLost` they returned the network being lost, which left the app stuck
 * "online" until the collector restarted.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isOnline: Flow<Boolean> = callbackFlow {
        val tracker = DefaultNetworkTracker<Network>()
        trySend(currentlyOnline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                tracker.onAvailable(network)?.let(::trySend)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(tracker.onCapabilities(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)))
            }
            override fun onLost(network: Network) {
                tracker.onLost(network)?.let(::trySend)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Only for the initial value, outside any callback. */
    private fun currentlyOnline(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
