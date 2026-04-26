package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import retrofit2.http.GET
import retrofit2.http.Query

interface TickbuddyApi {

    @GET("/ocs/v2.php/apps/tickbuddy/api/tracks")
    suspend fun getTracks(): OcsEnvelope<List<TrackDto>>

    @GET("/ocs/v2.php/apps/tickbuddy/api/ticks")
    suspend fun getTicks(
        @Query("from") from: String,
        @Query("to") to: String,
    ): OcsEnvelope<List<TickDto>>
}
