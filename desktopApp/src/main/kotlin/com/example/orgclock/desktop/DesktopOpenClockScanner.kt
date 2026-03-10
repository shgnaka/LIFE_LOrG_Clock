package com.example.orgclock.desktop

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.parser.OrgParser
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toJavaZoneId
import java.time.ZonedDateTime

data class DesktopFileScanFailure(
    val fileId: String,
    val fileName: String,
    val reason: String,
)

data class DesktopClockInEntry(
    val fileId: String,
    val fileName: String,
    val headingTitle: String,
    val l1Title: String?,
    val headingPath: HeadingPath,
    val startedAt: ZonedDateTime,
)

data class DesktopOpenClockScanResult(
    val entries: List<DesktopClockInEntry>,
    val failedFiles: List<DesktopFileScanFailure>,
)

class DesktopOpenClockScanner(
    private val repository: ClockRepository,
    private val parser: OrgParser = OrgParser(),
) {
    suspend fun scan(timeZone: TimeZone): Result<DesktopOpenClockScanResult> {
        val files = repository.listOrgFiles().getOrElse { return Result.failure(it) }
        val entries = mutableListOf<DesktopClockInEntry>()
        val failures = mutableListOf<DesktopFileScanFailure>()

        for (file in files) {
            val docResult = repository.loadFile(file.fileId)
            if (docResult.isFailure) {
                failures += DesktopFileScanFailure(
                    fileId = file.fileId,
                    fileName = file.displayName,
                    reason = readableFailureReason(docResult.exceptionOrNull()),
                )
                continue
            }
            val parsed = runCatching { parser.parseHeadingsWithOpenClock(docResult.getOrThrow().lines, timeZone) }
            if (parsed.isFailure) {
                failures += DesktopFileScanFailure(
                    fileId = file.fileId,
                    fileName = file.displayName,
                    reason = readableFailureReason(parsed.exceptionOrNull()),
                )
                continue
            }

            entries += parsed.getOrThrow()
                .asSequence()
                .filter { it.node.level == 2 && it.openClock != null }
                .map { heading ->
                    DesktopClockInEntry(
                        fileId = file.fileId,
                        fileName = file.displayName,
                        headingTitle = heading.node.title,
                        l1Title = heading.node.parentL1,
                        headingPath = heading.node.path,
                        startedAt = heading.openClock!!.toDesktopZonedDateTime(timeZone),
                    )
                }
        }

        return Result.success(
            DesktopOpenClockScanResult(
                entries = entries.sortedByDescending { it.startedAt },
                failedFiles = failures,
            ),
        )
    }

    private fun readableFailureReason(error: Throwable?): String {
        val message = error?.message?.trim()
        return if (!message.isNullOrEmpty()) {
            message
        } else {
            error?.javaClass?.simpleName ?: "unknown error"
        }
    }

    private fun kotlinx.datetime.Instant.toDesktopZonedDateTime(timeZone: TimeZone): ZonedDateTime =
        toJavaInstant().atZone(timeZone.toJavaZoneId())
}
