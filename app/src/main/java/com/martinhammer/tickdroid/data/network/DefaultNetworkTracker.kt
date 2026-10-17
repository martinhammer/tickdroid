package com.martinhammer.tickdroid.data.network

/**
 * Online state of the default network, from callback data only. Each function returns the
 * value to emit, or null for none.
 *
 * Generic over the network type so JVM tests can use plain values instead of
 * `android.net.Network`. All callbacks for one registration arrive on the same thread, one at
 * a time, so there is no locking.
 */
internal class DefaultNetworkTracker<N : Any> {
    private var current: N? = null

    /** Capabilities always follow (API 26+); wait for them rather than guessing. */
    fun onAvailable(network: N): Boolean? {
        current = network
        return null
    }

    fun onCapabilities(network: N, hasInternet: Boolean): Boolean {
        current = network
        return hasInternet
    }

    /** Only the current default going away means offline; a late onLost for an old default changes nothing. */
    fun onLost(network: N): Boolean? =
        if (network == current) {
            current = null
            false
        } else {
            null
        }
}
