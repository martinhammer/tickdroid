package com.martinhammer.tickdroid.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PushFailureTest {

    @Test fun `401 signs out, with or without a reason`() {
        assertEquals(PushFailure.SignOut, pushFailureFor(401, null))
        assertEquals(PushFailure.SignOut, pushFailureFor(401, "archived"))
    }

    @Test fun `423 is refused, with or without a reason`() {
        assertEquals(PushFailure.Refused, pushFailureFor(423, "archived"))
        assertEquals(PushFailure.Refused, pushFailureFor(423, null))
    }

    @Test fun `404 and 400 with a Tickbuddy reason are dropped`() {
        assertEquals(PushFailure.Drop, pushFailureFor(404, "trackNotFound"))
        assertEquals(PushFailure.Drop, pushFailureFor(400, "invalidValue"))
        assertEquals(PushFailure.Drop, pushFailureFor(400, "wrongTrackType"))
    }

    @Test fun `422 with a reason (a reused Idempotency-Key) is dropped, without one retried`() {
        assertEquals(PushFailure.Drop, pushFailureFor(422, "idempotencyKeyReused"))
        assertEquals(PushFailure.Retry, pushFailureFor(422, null))
    }

    @Test fun `404 and 400 without a reason are retried`() {
        // Nextcloud's own 404 while Tickbuddy is disabled: dropping would lose queued ticks.
        assertEquals(PushFailure.Retry, pushFailureFor(404, null))
        assertEquals(PushFailure.Retry, pushFailureFor(400, null))
    }

    @Test fun `other errors are retried, with or without a reason`() {
        for (code in listOf(403, 409, 429, 500, 503)) {
            assertEquals("HTTP $code", PushFailure.Retry, pushFailureFor(code, null))
            assertEquals("HTTP $code", PushFailure.Retry, pushFailureFor(code, "trackNotFound"))
        }
    }

    @Test fun `409 noteConflict (a note that kept changing while appending) is retried`() {
        assertEquals(PushFailure.Retry, pushFailureFor(409, "noteConflict"))
    }
}
