package com.example.orgclock.template

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateSharingJsonCodecTest {
    private val revision = SharedTemplateRevision(
        revisionId = "rev-2",
        parentRevisionId = "rev-1",
        content = "* Work\n** Task\n",
        contentHash = "hash-2",
        updatedAt = Instant.parse("2026-06-12T00:00:00Z"),
        updatedByDeviceId = "desktop-a",
    )

    @Test
    fun fetchResponse_roundTrips() {
        val original = TemplateFetchResponse("android-a", "desktop-a", revision)

        val decoded = TemplateSharingJsonCodec.decodeFetchResponse(
            TemplateSharingJsonCodec.encodeFetchResponse(original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun pushRequest_roundTripsWithExpectedRevision() {
        val original = TemplatePushRequest("desktop-a", "android-a", revision, "rev-1")

        val decoded = TemplateSharingJsonCodec.decodePushRequest(
            TemplateSharingJsonCodec.encodePushRequest(original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun conflictResult_roundTrips() {
        val original = TemplatePushResult.Conflict("rev-other", "rev-2")

        val decoded = TemplateSharingJsonCodec.decodePushResult(
            TemplateSharingJsonCodec.encodePushResult(original),
        )

        assertEquals(original, decoded)
    }
}
