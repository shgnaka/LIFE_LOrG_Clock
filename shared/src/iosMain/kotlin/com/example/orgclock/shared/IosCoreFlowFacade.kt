package com.example.orgclock.shared

import com.example.orgclock.data.IosFileOrgRepository
import com.example.orgclock.data.SaveResult
import com.example.orgclock.domain.ClockService
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Minimal M4 facade that proves iOS file adapter and shared core flow integration.
 */
actual class IosCoreFlowFacade actual constructor() {
    private val repository: IosFileOrgRepository = IosFileOrgRepository()
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
    private val clockService = ClockService(repository)

    actual fun listFilesSummary(): String {
        val filesResult = runSuspend { repository.listOrgFiles() }
        if (filesResult.isFailure) {
            val message = filesResult.exceptionOrNull()?.message ?: "unknown"
            return "listFiles=error:$message"
        }
        val files = filesResult.getOrThrow()
        return "listFiles=count=${files.size}"
    }

    actual fun verifyDailyReadWriteRoundTrip(): String {
        val today = Clock.System.now().toLocalDateTime(timeZone).date
        val beforeResult = runSuspend { repository.loadDaily(today) }
        if (beforeResult.isFailure) {
            val message = beforeResult.exceptionOrNull()?.message ?: "unknown"
            return "daily=loadError:$message"
        }
        val before = beforeResult.getOrThrow()

        val nextLines = if (before.lines.isEmpty()) {
            listOf("* Inbox", "** iOS M4 Probe")
        } else {
            before.lines
        }
        val saveResult = runSuspend { repository.saveDaily(today, nextLines, before.hash) }
        if (saveResult !is SaveResult.Success) {
            return "daily=saveFailed:${saveResult::class.simpleName}"
        }

        val staleAttempt = runSuspend { repository.saveDaily(today, nextLines, before.hash) }
        val staleCode = staleAttempt::class.simpleName ?: "Unknown"
        return "daily=ok, stale=$staleCode"
    }

    actual fun listHeadingsSummaryForFirstFile(): String {
        val filesResult = runSuspend { repository.listOrgFiles() }
        if (filesResult.isFailure) {
            val message = filesResult.exceptionOrNull()?.message ?: "unknown"
            return "headings=error:$message"
        }
        val files = filesResult.getOrThrow()
        val first = files.firstOrNull() ?: return "headings=no-files"
        val headingsResult = runSuspend { clockService.listHeadings(first.fileId, timeZone) }
        if (headingsResult.isFailure) {
            val message = headingsResult.exceptionOrNull()?.message ?: "unknown"
            return "headings=error:$message"
        }
        val headings = headingsResult.getOrThrow()
        val level2Count = headings.count { it.node.level == 2 }
        return "headings=count=${headings.size}, level2=$level2Count"
    }

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
