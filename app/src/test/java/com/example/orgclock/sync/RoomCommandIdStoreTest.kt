package com.example.orgclock.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCommandIdStoreTest {
    @Test
    fun markProcessed_prunesByRetentionAndKeepsRecentRows() {
        runBlocking {
            val dao = FakeProcessedCommandIdDao()
            var now = 10_000L
            val store = RoomCommandIdStore(
                dao = dao,
                nowEpochMs = { now },
                retentionMs = 1_000L,
                maxRows = 3L,
            )

            store.markProcessed("cmd-1")
            now += 2_000L
            store.markProcessed("cmd-2")
            now += 100L
            store.markProcessed("cmd-3")
            now += 100L
            store.markProcessed("cmd-4")

            assertFalse(store.contains("cmd-1"))
            assertTrue(store.contains("cmd-2"))
            assertTrue(store.contains("cmd-3"))
            assertTrue(store.contains("cmd-4"))
        }
    }
}

private class FakeProcessedCommandIdDao : ProcessedCommandIdDao {
    private val entries = linkedMapOf<String, ProcessedCommandIdEntity>()

    override fun exists(commandId: String): Boolean = entries.containsKey(commandId)

    override fun insert(entity: ProcessedCommandIdEntity) {
        if (!entries.containsKey(entity.commandId)) {
            entries[entity.commandId] = entity
        }
    }

    override fun deleteOlderThan(epochMs: Long): Int {
        val keys = entries.values
            .filter { it.processedAtEpochMs < epochMs }
            .map { it.commandId }
        keys.forEach { entries.remove(it) }
        return keys.size
    }

    override fun count(): Long = entries.size.toLong()

    override fun deleteOldest(limit: Int): Int {
        val keys = entries.values
            .sortedBy { it.processedAtEpochMs }
            .take(limit)
            .map { it.commandId }
        keys.forEach { entries.remove(it) }
        return keys.size
    }
}
