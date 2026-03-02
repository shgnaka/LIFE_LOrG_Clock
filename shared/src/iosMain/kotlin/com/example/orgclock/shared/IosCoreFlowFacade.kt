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
class IosCoreFlowFacade(
    private val repository: IosFileOrgRepository = IosFileOrgRepository(),
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val clockService = ClockService(repository)

    fun listFilesSummary(): String {
        val files = runSuspend { repository.listOrgFiles() }
            .getOrElse { return "listFiles=error:${it.message ?: "unknown"}" }
        return "listFiles=count=${files.size}"
    }

    fun verifyDailyReadWriteRoundTrip(): String {
        val today = Clock.System.now().toLocalDateTime(timeZone).date
        val before = runSuspend { repository.loadDaily(today) }
            .getOrElse { return "daily=loadError:${it.message ?: "unknown"}" }

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

    fun listHeadingsSummaryForFirstFile(): String {
        val files = runSuspend { repository.listOrgFiles() }
            .getOrElse { return "headings=error:${it.message ?: "unknown"}" }
        val first = files.firstOrNull() ?: return "headings=no-files"
        val headings = runSuspend { clockService.listHeadings(first.fileId, timeZone) }
            .getOrElse { return "headings=error:${it.message ?: "unknown"}" }
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
