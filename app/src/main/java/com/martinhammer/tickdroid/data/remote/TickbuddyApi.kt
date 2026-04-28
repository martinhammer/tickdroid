package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TickToggleResponse
import com.martinhammer.tickdroid.data.remote.dto.TickValueResponse
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface TickbuddyApi {

    @GET("/ocs/v2.php/apps/tickbuddy/api/tracks")
    suspend fun getTracks(): OcsEnvelope<List<TrackDto>>

    @GET("/ocs/v2.php/apps/tickbuddy/api/ticks")
    suspend fun getTicks(
        @Query("from") from: String,
        @Query("to") to: String,
    ): OcsEnvelope<List<TickDto>>

    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/ticks/toggle")
    suspend fun toggleTick(
        @Field("trackId") trackId: Long,
        @Field("date") date: String,
    ): OcsEnvelope<TickToggleResponse>

    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/ticks/set")
    suspend fun setTick(
        @Field("trackId") trackId: Long,
        @Field("date") date: String,
        @Field("value") value: Int,
    ): OcsEnvelope<TickValueResponse>
}
