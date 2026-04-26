package com.martinhammer.tickdroid.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OcsEnvelope<T>(
    val ocs: OcsBody<T>,
)

@Serializable
data class OcsBody<T>(
    val meta: OcsMeta,
    val data: T,
)

@Serializable
data class OcsMeta(
    val status: String,
    @SerialName("statuscode") val statusCode: Int,
    val message: String? = null,
)
