package com.example.orgclock.desktop

import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSyncCoreIngressTest {
    private val mapper = DesktopSyncCoreIngressHttpMapper()
    private val json = Json

    @Test
    fun acceptedOutcomesUseTransportAcceptedStatus() {
        val accepted = mapper.responseFor(IngressOutcome.Accepted)
        val duplicate = mapper.responseFor(IngressOutcome.AlreadyAccepted)

        assertEquals(202, accepted.status)
        assertEquals("accepted", accepted.statusValue())
        assertEquals(202, duplicate.status)
        assertEquals("already_accepted", duplicate.statusValue())
    }

    @Test
    fun rejectedOutcomePreservesStatusAndStableErrorCode() {
        val secret = "SECRET-DETAIL-SHOULD-NOT-LEAK"
        val response = mapper.responseFor(
            IngressOutcome.Rejected(
                httpStatus = 409,
                error = SyncError(SyncErrorCode.ReplayConflict, "same sender/message with $secret"),
            ),
        )

        assertEquals(409, response.status)
        assertEquals("rejected", response.statusValue())
        assertEquals("ReplayConflict", response.errorCode())
        assertEquals("replay conflict", response.detail())
        kotlin.test.assertFalse(response.body.contains(secret))
    }

    @Test
    fun retryLaterOutcomeAddsRetryAfterHeader() {
        val secret = "SECRET-RETRY-LATER-SHOULD-NOT-LEAK"
        val response = mapper.responseFor(
            IngressOutcome.RetryLater(
                httpStatus = 503,
                retryAfterSeconds = 30,
                error = SyncError(SyncErrorCode.StoreUnavailable, "busy: $secret"),
            ),
        )

        assertEquals(503, response.status)
        assertEquals("30", response.headers["Retry-After"])
        assertEquals("StoreUnavailable", response.errorCode())
        assertEquals("store unavailable", response.detail())
        kotlin.test.assertFalse(response.body.contains(secret))
    }

    @Test
    fun payloadTooLargeUsesPayloadError() {
        val response = mapper.payloadTooLarge()

        assertEquals(413, response.status)
        assertEquals("PayloadTooLarge", response.errorCode())
    }

    @Test
    fun serverUnavailableDoesNotExposeExceptionDetail() {
        val response = mapper.serverUnavailable()

        assertEquals(503, response.status)
        assertEquals("StoreUnavailable", response.errorCode())
        assertEquals("store unavailable", response.detail())
    }

    private fun DesktopSyncCoreHttpResponse.statusValue(): String = json.parseToJsonElement(body)
        .jsonObject
        .getValue("status")
        .jsonPrimitive
        .content

    private fun DesktopSyncCoreHttpResponse.errorCode(): String = json.parseToJsonElement(body)
        .jsonObject
        .getValue("errorCode")
        .jsonPrimitive
        .content

    private fun DesktopSyncCoreHttpResponse.detail(): String = json.parseToJsonElement(body)
        .jsonObject
        .getValue("detail")
        .jsonPrimitive
        .content
}
