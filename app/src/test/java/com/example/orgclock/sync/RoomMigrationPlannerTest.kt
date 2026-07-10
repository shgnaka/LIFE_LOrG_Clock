package com.example.orgclock.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMigrationPlannerTest {
    @Test
    fun v2ToCurrent_preservesOutgoingSentinelsAndMapsState() {
        val pending = legacyOutgoing(commandId = "cmd-a", createdAtEpochMs = 100, state = "PENDING", expiresAtEpochMs = null)
            .toV3OutgoingRow(sequence = 1)
        val sent = legacyOutgoing(commandId = "cmd-b", createdAtEpochMs = 200, state = "SENT", retryCount = 3)
            .toV3OutgoingRow(sequence = 2)
        val acked = legacyOutgoing(commandId = "cmd-c", createdAtEpochMs = 300, state = "ACKED", lastErrorCode = "Timeout")
            .toV3OutgoingRow(sequence = 3)

        assertEquals("pending", pending.state)
        assertNull(pending.expiresAtEpochMs)
        assertNull(pending.leaseUntilEpochMs)
        assertNull(pending.terminalAtEpochMs)
        assertEquals(1L, pending.sequence)

        assertEquals("dispatching", sent.state)
        assertEquals(0L, sent.leaseUntilEpochMs)
        assertNull(sent.terminalAtEpochMs)
        assertEquals(3, sent.retryCount)

        assertEquals("acked", acked.state)
        assertEquals(1_000L, acked.terminalAtEpochMs)
        assertEquals("Timeout", acked.lastErrorCode)
        assertEquals("detail", acked.lastErrorMessage)

        assertEquals("cmd-a", pending.commandId)
        assertEquals("clock.command.v1", pending.topic)
        assertEquals("{\"op\":\"start\"}", pending.payloadJson)
        assertEquals("peer-a", pending.targetPeerId)
        assertEquals(100L, pending.createdAtEpochMs)
        assertTrue(pending.contentSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(pending.contentSha256, sent.contentSha256)
    }

    @Test
    fun v1ToCurrent_deliveryEventsGainSequenceTopicAndAttempt() {
        val row = LegacyDeliveryEventMigrationRow(
            id = 7,
            commandId = "cmd-a",
            peerId = "peer-a",
            state = "SENT",
            occurredAtEpochMs = 1_234L,
            errorCode = "Timeout",
            detail = "retry",
        ).toV3DeliveryEventRow(sequence = 4)

        assertEquals(7L, row.id)
        assertEquals("cmd-a", row.commandId)
        assertEquals("peer-a", row.peerId)
        assertEquals("dispatching", row.state)
        assertEquals(1_234L, row.occurredAtEpochMs)
        assertEquals("Timeout", row.errorCode)
        assertEquals("retry", row.detail)
        assertEquals(4L, row.sequence)
        assertEquals("clock.command.v1", row.topic)
        assertEquals(0, row.attempt)
    }

    @Test(expected = IllegalStateException::class)
    fun migrationRejectsUnknownOutgoingState() {
        legacyOutgoing(state = "UNKNOWN").toV3OutgoingRow(sequence = 1)
    }

    private fun legacyOutgoing(
        commandId: String = "cmd-a",
        createdAtEpochMs: Long = 100L,
        state: String = "PENDING",
        retryCount: Int = 0,
        expiresAtEpochMs: Long? = 10_000L,
        lastErrorCode: String? = null,
    ): LegacyOutgoingMigrationRow = LegacyOutgoingMigrationRow(
        commandId = commandId,
        topic = "clock.command.v1",
        payloadJson = "{\"op\":\"start\"}",
        targetPeerId = "peer-a",
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        state = state,
        retryCount = retryCount,
        nextRetryAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = 1_000L,
        lastErrorCode = lastErrorCode,
        lastErrorMessage = lastErrorCode?.let { "detail" },
    )
}
