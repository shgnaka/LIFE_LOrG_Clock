package com.example.orgclock.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.orgclock.model.HeadingPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomClockEventStoreMainThreadTest {

    @Test
    fun storeOperations_succeedWhenCalledFromMainThread() = runBlocking(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val database = Room.inMemoryDatabaseBuilder(context, ClockEventDatabase::class.java).build()
        try {
            val store = RoomClockEventStore(database.dao())
            val event = sampleEvent("evt-1", 1L)

            val appendResult = store.append(event)
            assertTrue(appendResult is AppendClockEventResult.Appended)
            val appendedCursor = (appendResult as AppendClockEventResult.Appended).cursor
            assertEquals(ClockEventCursor(1L), appendedCursor)

            val duplicateResult = store.append(event)
            assertTrue(duplicateResult is AppendClockEventResult.Duplicate)
            assertEquals(appendedCursor, (duplicateResult as AppendClockEventResult.Duplicate).cursor)

            assertTrue(store.contains("evt-1"))
            assertEquals(listOf("evt-1"), store.readAllForReplay().map { it.event.eventId })
            assertEquals(1, store.readSnapshot().pendingSyncCount)
            assertEquals(listOf("evt-1"), store.listSince(null).map { it.event.eventId })

            store.updateSyncCheckpoint(appendedCursor)
            assertEquals(appendedCursor, store.readSnapshot().lastSyncedCursor)
        } finally {
            database.close()
        }
    }

    private fun sampleEvent(eventId: String, counter: Long): ClockEvent = ClockEvent(
        eventId = eventId,
        eventType = ClockEventType.Started,
        deviceId = "device-local",
        createdAt = Instant.parse("2026-03-18T09:00:00Z"),
        logicalDay = LocalDate.parse("2026-03-18"),
        fileName = "2026-03-18.org",
        headingPath = HeadingPath.parse("Work/Project A"),
        causalOrder = ClockEventCausalOrder(counter = counter),
    )
}
