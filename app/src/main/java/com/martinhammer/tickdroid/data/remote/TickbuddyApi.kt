package com.martinhammer.tickdroid.data.remote

import com.martinhammer.tickdroid.data.remote.dto.CapabilitiesData
import com.martinhammer.tickdroid.data.remote.dto.NoteDto
import com.martinhammer.tickdroid.data.remote.dto.NoteTextResponse
import com.martinhammer.tickdroid.data.remote.dto.StatsDto
import com.martinhammer.tickdroid.data.remote.dto.TickDto
import com.martinhammer.tickdroid.data.remote.dto.TickToggleResponse
import com.martinhammer.tickdroid.data.remote.dto.TickValueResponse
import com.martinhammer.tickdroid.data.remote.dto.TickYesNoResponse
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
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
     *
     * [includeAllTypes] true asks for tracks of every type, not just yes/no and counter (a
     * Tickbuddy with newer types hides them from clients that don't ask). Tickdroid always sends
     * it, as it shows unknown types read-only; a server that doesn't filter ignores it.
     */
    @GET("/ocs/v2.php/apps/tickbuddy/api/tracks")
    suspend fun getTracks(
        @Query("includeArchived") includeArchived: Boolean? = null,
        @Query("includeAllTypes") includeAllTypes: Boolean? = null,
    ): OcsEnvelope<List<TrackDto>>

    /** [includeAllTypes]: as for [getTracks], the ticks of tracks of every type. */
    @GET("/ocs/v2.php/apps/tickbuddy/api/ticks")
    suspend fun getTicks(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("includeAllTypes") includeAllTypes: Boolean? = null,
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

    /**
     * [setTick] for a yes/no day (capability `booleanSet`): [value] 1 ticks it, 0 unticks it. Its
     * own method only because the answer is read leniently ([TickYesNoResponse]).
     */
    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/ticks/set")
    suspend fun setYesNo(
        @Field("trackId") trackId: Long,
        @Field("date") date: String,
        @Field("value") value: Int,
    ): OcsEnvelope<TickYesNoResponse>

    /**
     * Adds [delta] (never 0) to a counter day (capability `counterIncrement`); answers the new
     * value, which includes other clients' changes. Not safe to repeat on its own, so always with
     * [idempotencyKey] (capability `idempotencyKey`): a quoted UUID, a Structured Fields string,
     * resent unchanged with the same [delta] until an answer arrives.
     */
    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/ticks/increment")
    suspend fun incrementTick(
        @Field("trackId") trackId: Long,
        @Field("date") date: String,
        @Field("delta") delta: Int,
        @Header("Idempotency-Key") idempotencyKey: String,
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

    // Notes (capability `notes`) and stats (capability `stats`). Form-encoded like the tick calls.

    /**
     * Every track's notes in the range, both ends included (private and archived tracks too).
     * [includeAllTypes] as for [getTicks]; a server that doesn't filter notes ignores it.
     */
    @GET("/ocs/v2.php/apps/tickbuddy/api/notes")
    suspend fun getNotes(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("includeAllTypes") includeAllTypes: Boolean? = null,
    ): OcsEnvelope<List<NoteDto>>

    /** Creates or replaces the day's note; answers the stored text. Never sent empty (see [deleteNote]). */
    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/notes/set")
    suspend fun setNote(
        @Field("trackId") trackId: Long,
        @Field("date") date: String,
        @Field("text") text: String,
    ): OcsEnvelope<NoteTextResponse>

    /**
     * Adds lines to the day's note, skipping text already there as whole lines (capability
     * `notesAppend`), so a retry is safe; answers the whole note. Never `allowDuplicate`.
     */
    @FormUrlEncoded
    @POST("/ocs/v2.php/apps/tickbuddy/api/notes/append")
    suspend fun appendNote(
        @Field("trackId") trackId: Long,
        @Field("date") date: String,
        @Field("text") text: String,
    ): OcsEnvelope<NoteTextResponse>

    /** 204, also when there was no note. A `Response`, so the empty body is safe (as [deleteTrack]). */
    @DELETE("/ocs/v2.php/apps/tickbuddy/api/notes")
    suspend fun deleteNote(
        @Query("trackId") trackId: Long,
        @Query("date") date: String,
    ): Response<Unit>

    /**
     * Every active track's stats as of [today], the phone's date. No `includeAllTypes`: the
     * parameter isn't defined here, so a track of a newer type may be missing from the answer.
     */
    @GET("/ocs/v2.php/apps/tickbuddy/api/stats")
    suspend fun getStats(@Query("today") today: String): OcsEnvelope<List<StatsDto>>
}
