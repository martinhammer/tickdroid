package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.CapabilitiesData
import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TickToggleResponse
import com.martinhammer.tickdroid.data.remote.dto.TickValueResponse
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface TickbuddyApi {

    /**
     * Version + feature discovery. Note this is a *core* Nextcloud endpoint (`/cloud/...`),
     * not under the Tickbuddy app base. See mobile_instructions.md §1.
     */
    @GET("/ocs/v2.php/cloud/capabilities")
    suspend fun getCapabilities(): OcsEnvelope<CapabilitiesData>

    /**
     * [includeArchived] true asks for archived tracks as well (Tickbuddy with capability
     * `archivedTracks`). Null leaves the parameter out: active tracks only on a new server, every
     * track on an older one. Only send true when the flag is set (see `ServerFeatures`).
     */
    @GET("/ocs/v2.php/apps/tickbuddy/api/tracks")
    suspend fun getTracks(
        @Query("includeArchived") includeArchived: Boolean? = null,
    ): OcsEnvelope<List<TrackDto>>

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

    // Track changes. Form-encoded exactly as Tickbuddy's own settings page sends them
    // (`TrackSettings.vue`, `URLSearchParams`), so the server's parameter parsing is proven.

    /** 201 with the new track, placed at `max(sort_order) + 1`. The server ignores `private` here. */
    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/tracks")
    suspend fun createTrack(
        @Field("name") name: String,
        @Field("type") type: String,
    ): OcsEnvelope<TrackDto>

    /**
     * Each call carries one change: [name], or [isPrivate], or [archived] (with [archivedAt] when
     * archiving). Retrofit omits null fields; a form body needs at least one. `archived=true`
     * stores [archivedAt] (YYYY-MM-DD, the phone's today) and leaves the sort order alone;
     * `archived=false` restores and moves the track to the end. Never add `sortOrder`: the server has a unique index on (user_id, sort_order),
     * so a single-track move can collide. [reorderTracks] is the only way order changes.
     */
    @FormUrlEncoded
    @PUT("/ocs/v2.php/apps/tickbuddy/api/tracks/{id}")
    suspend fun updateTrack(
        @Path("id") id: Long,
        @Field("name") name: String? = null,
        @Field("private") isPrivate: Boolean? = null,
        @Field("archived") archived: Boolean? = null,
        @Field("archivedAt") archivedAt: String? = null,
    ): OcsEnvelope<TrackDto>

    /**
     * Numbers the listed ids 1..n. On a server before archiving, any track left out keeps a
     * negative sort order and jumps to the top, so always send every active track the server has
     * (see `TrackEditor.reorder`). Archived tracks are placed after the listed ones.
     */
    @FormUrlEncoded
    @PUT("/ocs/v2.php/apps/tickbuddy/api/tracks/reorder")
    suspend fun reorderTracks(
        @Field("trackIds[]") trackIds: List<Long>,
    ): OcsEnvelope<List<TrackDto>>

    /** 204 with no body; deletes the track's ticks too. A `Response` so the empty body is safe. */
    @DELETE("/ocs/v2.php/apps/tickbuddy/api/tracks/{id}")
    suspend fun deleteTrack(@Path("id") id: Long): Response<Unit>
}
