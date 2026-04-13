package com.example.orgclock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCommandIdStoreMainThreadTest {

    @Test
    fun storeOperations_succeedWhenCalledFromMainThread() = runBlocking(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val database = Room.inMemoryDatabaseBuilder(context, ProcessedCommandIdDatabase::class.java).build()
        try {
            val store = RoomCommandIdStore(
                dao = database.dao(),
                nowEpochMs = { 10_000L },
                retentionMs = 1_000L,
                maxRows = 3L,
            )

            assertFalse(store.contains("cmd-1"))
            store.markProcessed("cmd-1")
            assertTrue(store.contains("cmd-1"))
            assertTrue(store.size() >= 1L)

            store.markProcessed("cmd-2")
            store.markProcessed("cmd-3")
            store.markProcessed("cmd-4")

            assertFalse(store.contains("cmd-1"))
            assertTrue(store.contains("cmd-2"))
            assertTrue(store.contains("cmd-3"))
            assertTrue(store.contains("cmd-4"))
        } finally {
            database.close()
        }
    }
}
