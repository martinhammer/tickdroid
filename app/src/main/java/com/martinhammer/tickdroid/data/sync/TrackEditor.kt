package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.ocsErrorMessage
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.cleanTrackName
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of a track change, free of Retrofit and IO types so ViewModels can map it to UI. */
sealed interface TrackChange {
    data object Done : TrackChange

    /** Create only: the new track's server id, so the list can scroll to it. */
    data class Created(val serverId: Long) : TrackChange

    /** Create only: the track was created, but the follow-up call making it private failed. */
    data class CreatedNotPrivate(val serverId: Long) : TrackChange

    /** User-facing reason: the server's own message, or one of the decided fallbacks. */
    data class Failed(val message: String) : TrackChange

    /** 401: `BasicAuthInterceptor` has already signed out and the app is leaving. Show nothing. */
    data object SignedOut : TrackChange
}

/**
 * Track management against Tickbuddy: validates, runs the change through
 * [SyncManager.changeTracks] (sync lock + track re-read in one hold), and turns exceptions into
 * [TrackChange]s. Online only; there is no offline queue, and the inert `dirty` / `deleted`
 * columns on tracks stay unused.
 */
@Singleton
class TrackEditor @Inject constructor(
    private val syncManager: SyncManager,
    private val json: Json,
    private val clock: Clock,
) {
    suspend fun create(name: String, type: TrackType, private: Boolean): TrackChange {
        val clean = cleanTrackName(name) ?: return TrackChange.Failed(EMPTY_NAME)
        return run {
            val created = it.createTrack(clean, type.wireValue).ocs.data
            if (!private) return@run TrackChange.Created(created.id)
            // The server doesn't take `private` on create, so it's a second call in the same
            // lock hold. If it fails, the track stays (public) and the user is told.
            try {
                it.updateTrack(created.id, isPrivate = true)
                TrackChange.Created(created.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TrackChange.CreatedNotPrivate(created.id)
            }
        }
    }

    suspend fun rename(serverId: Long, name: String): TrackChange {
        val clean = cleanTrackName(name) ?: return TrackChange.Failed(EMPTY_NAME)
        return run {
            it.updateTrack(serverId, name = clean)
            TrackChange.Done
        }
    }

    suspend fun setPrivate(serverId: Long, private: Boolean): TrackChange = run {
        it.updateTrack(serverId, isPrivate = private)
        TrackChange.Done
    }

    /**
     * Sends the full order. Re-reads the server's ids first, inside the same lock hold: a track
     * added on another device since the last pull is appended (left out, it would keep a negative
     * sort order and jump to the top), and an id deleted elsewhere is dropped.
     */
    suspend fun reorder(serverIdsInOrder: List<Long>): TrackChange = run { api ->
        // Deliberately active tracks only (no includeArchived): reorder only ever involves them,
        // and the server places every track it isn't sent after the listed ones.
        val serverOrder = api.getTracks().ocs.data.sortedBy { it.sortOrder }.map { it.id }
        api.reorderTracks(mergeOrder(serverIdsInOrder, serverOrder))
        TrackChange.Done
    }

    /** Archives with the phone's own date. A 404 shows the server's message, as for rename. */
    suspend fun archive(serverId: Long): TrackChange = run {
        it.updateTrack(serverId, archived = true, archivedAt = clock.today().toString())
        TrackChange.Done
    }

    /** Restores; the server puts the track at the end of the list. */
    suspend fun restore(serverId: Long): TrackChange = run {
        it.updateTrack(serverId, archived = false)
        TrackChange.Done
    }

    suspend fun delete(serverId: Long): TrackChange = run(notFoundIsDone = true) {
        val response = it.deleteTrack(serverId)
        if (!response.isSuccessful) throw HttpException(response)
        TrackChange.Done
    }

    /**
     * Order matters, as for the sync layer: [SSLException] is an [IOException], so it must be
     * caught first, and classification is by type, never by message.
     */
    private suspend fun run(
        notFoundIsDone: Boolean = false,
        change: suspend (TickbuddyApi) -> TrackChange,
    ): TrackChange = try {
        syncManager.changeTracks(change)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SSLException) {
        TrackChange.Failed("Couldn't save. The server's certificate isn't trusted.")
    } catch (_: IOException) {
        TrackChange.Failed("Couldn't save. Check your connection and try again.")
    } catch (e: HttpException) {
        when {
            e.code() == 401 -> TrackChange.SignedOut
            // Already gone on the server, which is what a delete asked for.
            e.code() == 404 && notFoundIsDone -> TrackChange.Done
            else -> TrackChange.Failed(
                ocsErrorMessage(e.response()?.errorBody()?.string(), json)
                    ?: "Couldn't save. Tickbuddy returned an error (HTTP ${e.code()}).",
            )
        }
    } catch (_: Exception) {
        // A 2xx reply we couldn't parse. Not expected from any Tickbuddy version; caught so it
        // can't crash the screen that asked.
        TrackChange.Failed("Couldn't save. Tickbuddy returned an unexpected reply.")
    }

    private companion object {
        // The server's own wording for the same case. The UI never lets a blank name through.
        const val EMPTY_NAME = "Track name cannot be empty"
    }
}

/**
 * The order to send to `PUT /tracks/reorder`: [wanted] minus ids the server no longer has, then
 * any server ids [wanted] doesn't mention, in the server's order.
 */
internal fun mergeOrder(wanted: List<Long>, serverOrder: List<Long>): List<Long> {
    val onServer = serverOrder.toSet()
    val mentioned = wanted.toSet()
    return wanted.filter { it in onServer }.distinct() + serverOrder.filterNot { it in mentioned }
}
