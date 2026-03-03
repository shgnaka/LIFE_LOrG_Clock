package com.example.orgclock.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCommandBufferTest {
    @Test
    fun drainAll_returnsAddedPayloadsAndClearsBuffer() {
        val buffer = IncomingCommandBuffer(maxSize = 10)
        buffer.add("""{"command_id":"cmd-1"}""")
        buffer.add("""{"command_id":"cmd-2"}""")

        val drained = buffer.drainAll()
        val drainedAgain = buffer.drainAll()

        assertEquals(2, drained.size)
        assertTrue(drained[0].contains("cmd-1"))
        assertTrue(drained[1].contains("cmd-2"))
        assertTrue(drainedAgain.isEmpty())
    }

    @Test
    fun add_overCapacityDropsOldest() {
        val buffer = IncomingCommandBuffer(maxSize = 2)
        buffer.add("""{"command_id":"cmd-1"}""")
        buffer.add("""{"command_id":"cmd-2"}""")

        val overflowed = buffer.add("""{"command_id":"cmd-3"}""")
        val drained = buffer.drainAll()

        assertTrue(overflowed)
        assertEquals(2, drained.size)
        assertTrue(drained[0].contains("cmd-2"))
        assertTrue(drained[1].contains("cmd-3"))
    }
}
