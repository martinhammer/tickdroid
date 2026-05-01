package com.martinhammer.tickdroid.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regression test for the snapshot-then-stomp race called out in CLAUDE.md.
 * Pull and PushWorker.drain are coordinated via the same mutex; if a `runExclusive`
 * block could start while a pull was mid-reconcile, it would see stale state.
 *
 * The dispatcher signals when it's serving a request (i.e. pull is mid-mutex-window),
 * then blocks on a release latch. While the dispatcher is paused we kick `runExclusive`,
 * wait long enough for it to clearly attempt acquisition, and assert it has not entered.
 * Then we release the latch and let the pull complete.
 */
class SyncMutexTest {

    private lateinit var rig: SyncTestRig

    @Before fun setUp() {
        rig = SyncTestRig().also { it.assertSignedIn() }
    }

    @After fun tearDown() {
        rig.shutdown()
    }

    @Test fun runExclusive_waitsUntilPullReleasesMutex() = runBlocking {
        val pullInsideMutex = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exclusiveEntered = AtomicBoolean(false)

        rig.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pullInsideMutex.countDown()
                release.await()
                return MockResponse().setResponseCode(200).setBody(EMPTY_OCS)
            }
        }

        val from = LocalDate.parse("2026-04-01")
        val to = LocalDate.parse("2026-04-30")

        val pullJob = CoroutineScope(Dispatchers.IO).async {
            rig.syncManager.pull(from, to)
        }

        // Wait until pull has definitely entered the mutex (dispatcher signalled).
        pullInsideMutex.await()

        // Now kick the exclusive block — it must NOT enter while the dispatcher is paused.
        val exclusiveJob = CoroutineScope(Dispatchers.IO).async {
            rig.syncManager.runExclusive { exclusiveEntered.set(true) }
        }

        // Give exclusive plenty of time to enter if the mutex were unguarded.
        Thread.sleep(200)
        val enteredEarly = exclusiveEntered.get()

        // Release pull, let everything finish.
        release.countDown()
        pullJob.await()
        exclusiveJob.await()

        assertFalse(
            "runExclusive block entered while pull was still holding the mutex",
            enteredEarly,
        )
        assertTrue("exclusive never entered after release", exclusiveEntered.get())
    }

    private companion object {
        const val EMPTY_OCS = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[]}}"""
    }
}
