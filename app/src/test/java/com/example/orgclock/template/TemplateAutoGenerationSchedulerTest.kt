package com.example.orgclock.template

import android.net.Uri
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateAutoGenerationSchedulerTest {
    @Test
    fun sync_disabledCancelsUniqueWork() = runTest {
        val workScheduler = FakeWorkScheduler()
        val scheduler = scheduler(workScheduler = workScheduler)
        val rootUri = mockUri("content://root")

        scheduler.sync(
            rootUri,
            RootScheduleConfig(rootUri = "content://root", enabled = false),
        )

        assertEquals(1, workScheduler.cancelled.size)
    }

    @Test
    fun sync_dueGeneratesTodayAndSchedulesNextRun() = runTest {
        val repository = FakeTemplateAutoGenerationRepository()
        val workScheduler = FakeWorkScheduler()
        val now = ZonedDateTime.of(2026, 3, 12, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
        val scheduler = scheduler(
            repository = repository,
            workScheduler = workScheduler,
            nowProvider = { now },
        )
        val rootUri = mockUri("content://root")

        scheduler.sync(
            rootUri,
            RootScheduleConfig(
                rootUri = "content://root",
                enabled = true,
                hour = 9,
                minute = 0,
            ),
        )

        assertEquals(listOf("content://root"), repository.openedRoots)
        assertEquals(listOf(LocalDate(2026, 3, 12)), repository.generatedDates)
        assertTrue(workScheduler.enqueued.single().delayMs > 0L)
    }

    @Test
    fun runWorker_whenNotDueSkipsGeneration() = runTest {
        val repository = FakeTemplateAutoGenerationRepository()
        val workScheduler = FakeWorkScheduler()
        val store = RootScheduleStore(InMemorySharedPreferences()).also {
            it.save(
                RootScheduleConfig(
                    rootUri = "content://root",
                    enabled = true,
                    hour = 23,
                    minute = 0,
                ),
            )
        }
        val scheduler = TemplateAutoGenerationScheduler(
            appContext = org.mockito.Mockito.mock(android.content.Context::class.java),
            scheduleStore = store,
            repositoryFactory = { repository },
            workScheduler = workScheduler,
            nowProvider = {
                ZonedDateTime.of(2026, 3, 12, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
            },
        )

        scheduler.runWorker(mockUri("content://root"))

        assertTrue(repository.generatedDates.isEmpty())
        assertEquals(1, workScheduler.enqueued.size)
    }

    private fun scheduler(
        repository: FakeTemplateAutoGenerationRepository = FakeTemplateAutoGenerationRepository(),
        workScheduler: FakeWorkScheduler = FakeWorkScheduler(),
        nowProvider: () -> ZonedDateTime = {
            ZonedDateTime.of(2026, 3, 12, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
        },
    ): TemplateAutoGenerationScheduler {
        return TemplateAutoGenerationScheduler(
            appContext = org.mockito.Mockito.mock(android.content.Context::class.java),
            scheduleStore = RootScheduleStore(InMemorySharedPreferences()),
            repositoryFactory = { repository },
            workScheduler = workScheduler,
            nowProvider = nowProvider,
        )
    }

    private fun mockUri(value: String): Uri {
        return org.mockito.Mockito.mock(Uri::class.java).also {
            org.mockito.Mockito.`when`(it.toString()).thenReturn(value)
        }
    }
}

internal class FakeTemplateAutoGenerationRepository : com.example.orgclock.template.TemplateAutoGenerationRepository {
    val openedRoots = mutableListOf<String>()
    val generatedDates = mutableListOf<LocalDate>()

    override suspend fun openRoot(uri: Uri): Result<Unit> {
        openedRoots += uri.toString()
        return Result.success(Unit)
    }

    override suspend fun createDailyFromTemplateIfMissing(date: LocalDate): Result<Boolean> {
        generatedDates += date
        return Result.success(true)
    }
}

internal class FakeWorkScheduler : TemplateAutoGenerationWorkScheduler {
    data class Enqueued(val name: String, val rootUri: String, val delayMs: Long)

    val enqueued = mutableListOf<Enqueued>()
    val cancelled = mutableListOf<String>()

    override fun enqueue(uniqueWorkName: String, rootUri: String, delayMs: Long) {
        enqueued += Enqueued(uniqueWorkName, rootUri, delayMs)
    }

    override fun cancel(uniqueWorkName: String) {
        cancelled += uniqueWorkName
    }
}
