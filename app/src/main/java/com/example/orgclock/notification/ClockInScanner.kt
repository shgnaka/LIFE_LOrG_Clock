package com.example.orgclock.notification

import com.example.orgclock.data.OrgRepository
import com.example.orgclock.parser.OrgParser
import java.time.ZoneId
import java.time.ZonedDateTime

data class FileScanFailure(
    val fileId: String,
    val fileName: String,
    val reason: String,
)

data class ClockInEntry(
    val fileId: String,
    val fileName: String,
    val headingTitle: String,
    val l1Title: String?,
    val startedAt: ZonedDateTime,
    val headingLineIndex: Int,
)

data class ClockInScanResult(
    val entries: List<ClockInEntry>,
    val failedFiles: List<FileScanFailure>,
)

class ClockInScanner(
    private val repository: OrgRepository,
    private val parser: OrgParser = OrgParser(),
) {
    suspend fun scan(zoneId: ZoneId): Result<ClockInScanResult> {
        val files = repository.listOrgFiles().getOrElse { return Result.failure(it) }
        val entries = mutableListOf<ClockInEntry>()
        val failedFiles = mutableListOf<FileScanFailure>()

        for (file in files) {
            val docResult = repository.loadFile(file.fileId)
            if (docResult.isFailure) {
                failedFiles += FileScanFailure(
                    fileId = file.fileId,
                    fileName = file.displayName,
                    reason = docResult.exceptionOrNull()?.message ?: "unknown",
                )
                continue
            }
            val doc = docResult.getOrThrow()
            val parsedResult = runCatching { parser.parseHeadingsWithOpenClock(doc.lines, zoneId) }
            if (parsedResult.isFailure) {
                failedFiles += FileScanFailure(
                    fileId = file.fileId,
                    fileName = file.displayName,
                    reason = parsedResult.exceptionOrNull()?.message ?: "unknown",
                )
                continue
            }
            val opened = parsedResult
                .getOrThrow()
                .asSequence()
                .filter { it.node.level == 2 && it.openClock != null }
                .map { heading ->
                    ClockInEntry(
                        fileId = file.fileId,
                        fileName = file.displayName,
                        headingTitle = heading.node.title,
                        l1Title = heading.node.parentL1,
                        startedAt = heading.openClock!!,
                        headingLineIndex = heading.node.lineIndex,
                    )
                }
            entries += opened
        }

        entries.sortByDescending { it.startedAt }
        return Result.success(
            ClockInScanResult(
                entries = entries,
                failedFiles = failedFiles,
            ),
        )
    }
}
