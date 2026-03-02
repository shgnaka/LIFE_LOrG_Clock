package com.example.orgclock.data

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class IosFileOrgRepositoryTest {
    private val repository = IosFileOrgRepository()

    @Test
    fun loadDaily_initiallyReturnsDocument() {
        val date = uniqueDate(offsetDays = 30)
        val result = runSuspend { repository.loadDaily(date) }
        assertTrue(result.isSuccess)
        val doc = result.getOrThrow()
        assertEquals(date, doc.date)
        assertIs<String>(doc.hash)
    }

    @Test
    fun saveDaily_thenLoadDaily_roundTrips() {
        val date = uniqueDate(offsetDays = 31)
        val before = runSuspend { repository.loadDaily(date) }.getOrThrow()
        val lines = listOf("* M4", "** RoundTrip")

        val save = runSuspend { repository.saveDaily(date, lines, before.hash) }
        assertEquals(SaveResult.Success, save)

        val after = runSuspend { repository.loadDaily(date) }.getOrThrow()
        assertEquals(lines, after.lines)
    }

    @Test
    fun saveDaily_withStaleHash_returnsConflict() {
        val date = uniqueDate(offsetDays = 32)
        val before = runSuspend { repository.loadDaily(date) }.getOrThrow()
        val lines = listOf("* M4", "** Conflict")

        val save = runSuspend { repository.saveDaily(date, lines, before.hash) }
        assertEquals(SaveResult.Success, save)

        val stale = runSuspend { repository.saveDaily(date, lines, before.hash) }
        assertIs<SaveResult.Conflict>(stale)
    }

    private fun uniqueDate(offsetDays: Int) =
        Clock.System.now().toLocalDateTime(TimeZone.UTC).date.plus(DatePeriod(days = offsetDays))

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    completion = result
                }
            },
        )
        return completion?.getOrThrow() ?: error("Suspend function did not complete synchronously")
    }
}
