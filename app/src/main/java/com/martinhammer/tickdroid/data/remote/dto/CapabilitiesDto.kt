package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Shape of `GET /ocs/v2.php/cloud/capabilities` -> `data`. The core Nextcloud capabilities
 * response carries many keys; we only care about the Tickbuddy app's registration, so every
 * field is nullable and unknown keys are ignored (see the shared Json config in NetworkModule).
 *
 * `data.capabilities.tickbuddy` is present only on Tickbuddy 1.0.6+ (the release that added the
 * `ICapability` provider). Its absence on a server we're already signed in to means the backend
 * predates capability discovery, i.e. 1.0.5 or earlier.
 */
@Serializable
data class CapabilitiesData(
    val capabilities: Capabilities? = null,
)

@Serializable
data class Capabilities(
    val tickbuddy: TickbuddyCapability? = null,
)

@Serializable
data class TickbuddyCapability(
    val version: String? = null,
    val apiVersion: Int? = null,
    val features: Map<String, Boolean> = emptyMap(),
)
