package com.example.orgclock.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.OrgDocument
import com.example.orgclock.sync.ClockEventDatabase
import com.example.orgclock.sync.ClockEventType
import com.example.orgclock.sync.RoomClockEventStore
import com.example.orgclock.sync.StoreBackedClockEventRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClockServiceRecorderMainThreadTest {

    @Test
    fun startStopCancel_recordEventsSuccessfullyFromMainThread() = runBlocking(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        fun newStore(): Pair<ClockEventDatabase, RoomClockEventStore> {
            val database = Room.inMemoryDatabaseBuilder(context, ClockEventDatabase::class.java).build()
            return database to RoomClockEventStore(database.dao())
        }

        val (startDb, startStore) = newStore()
        try {
            val startService = ClockService(
                repository = singleFileRepo(
                    initialLines = listOf("* Work", "** Project A", ":LOGBOOK:", ":END:"),
                ),
                clockEventRecorder = StoreBackedClockEventRecorder(
                    store = startStore,
                    deviceIdProvider = { "device-local" },
                ),
            )

            val startResult = startService.startClockInFile(
                fileId = "f1",
                headingPath = HeadingPath.parse("Work/Project A"),
                now = Instant.parse("2026-03-18T09:00:00Z"),
            )
            assertTrue(startResult.isSuccess)
            assertEquals(listOf(ClockEventType.Started), startStore.readAllForReplay().map { it.event.eventType })
        } finally {
            startDb.close()
        }

        val (stopDb, stopStore) = newStore()
        try {
            val stopService = ClockService(
                repository = singleFileRepo(
                    initialLines = listOf(
                        "* Work",
                        "** Project A",
                        ":LOGBOOK:",
                        "CLOCK: [2026-03-18 Tue 09:00:00]",
                        ":END:",
                    ),
                ),
                clockEventRecorder = StoreBackedClockEventRecorder(
                    store = stopStore,
                    deviceIdProvider = { "device-local" },
                ),
            )

            val stopResult = stopService.stopClockInFile(
                fileId = "f1",
                headingPath = HeadingPath.parse("Work/Project A"),
                now = Instant.parse("2026-03-18T10:00:00Z"),
            )
            assertTrue(stopResult.isSuccess)
            assertEquals(listOf(ClockEventType.Stopped), stopStore.readAllForReplay().map { it.event.eventType })
        } finally {
            stopDb.close()
        }

        val (cancelDb, cancelStore) = newStore()
        try {
            val cancelService = ClockService(
                repository = singleFileRepo(
                    initialLines = listOf(
                        "* Work",
                        "** Project A",
                        ":LOGBOOK:",
                        "CLOCK: [2026-03-18 Tue 09:00:00]",
                        ":END:",
                    ),
                ),
                clockEventRecorder = StoreBackedClockEventRecorder(
                    store = cancelStore,
                    deviceIdProvider = { "device-local" },
                ),
            )

            val cancelResult = cancelService.cancelClockInFile(
                fileId = "f1",
                headingPath = HeadingPath.parse("Work/Project A"),
            )
            assertTrue(cancelResult.isSuccess)
            assertEquals(listOf(ClockEventType.Cancelled), cancelStore.readAllForReplay().map { it.event.eventType })
        } finally {
            cancelDb.close()
        }
    }

    private fun singleFileRepo(initialLines: List<String>): ClockRepository = object : ClockRepository {
        private var currentLines = initialLines

        override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> =
            Result.success(listOf(OrgFileEntry("f1", "f1.org", null)))

        override suspend fun loadFile(fileId: String): Result<OrgDocument> =
            Result.success(OrgDocument(LocalDate(2026, 3, 18), currentLines, "hash"))

        override suspend fun saveFile(
            fileId: String,
            lines: List<String>,
            expectedHash: String,
            writeIntent: FileWriteIntent,
        ): SaveResult {
            currentLines = lines
            return SaveResult.Success
        }

        override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> =
            Result.failure(UnsupportedOperationException())

        override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult =
            SaveResult.ValidationError("unsupported")
    }
}
