package com.example.orgclock.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCommandBufferTest {
    @Test
    fun drainAll_returnsAddedPayloadsAndClearsBuffer() {
        val buffer = IncomingCommandBuffer(maxSize = 10)
        buffer.add(command("cmd-1"))
        buffer.add(command("cmd-2"))

        val drained = buffer.drainAll()
        val drainedAgain = buffer.drainAll()

        assertEquals(2, drained.size)
        assertEquals("cmd-1", drained[0].commandId)
        assertEquals("cmd-2", drained[1].commandId)
        assertTrue(drainedAgain.isEmpty())
    }

    @Test
    fun add_overCapacityDropsOldest() {
        val buffer = IncomingCommandBuffer(maxSize = 2)
        buffer.add(command("cmd-1"))
        buffer.add(command("cmd-2"))

        val overflowed = buffer.add(command("cmd-3"))
        val drained = buffer.drainAll()

        assertTrue(overflowed)
        assertEquals(2, drained.size)
        assertEquals("cmd-2", drained[0].commandId)
        assertEquals("cmd-3", drained[1].commandId)
    }

    private fun command(commandId: String): VerifiedIncomingCommand {
        return VerifiedIncomingCommand(
            payloadJson = """{"schema":"$CLOCK_COMMAND_SCHEMA_V1","command_id":"$commandId","from_device_id":"dev-a"}""",
            commandId = commandId,
            senderDeviceId = "dev-a",
            peerId = null,
            verificationState = IncomingVerificationState.Verified,
            verificationReason = null,
            receivedAtEpochMs = 1L,
        )
    }
}
