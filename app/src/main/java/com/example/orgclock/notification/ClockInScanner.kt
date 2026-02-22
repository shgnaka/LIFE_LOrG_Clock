package com.example.orgclock.notification

import com.example.orgclock.data.OrgRepository
import com.example.orgclock.parser.OrgParser
import java.time.ZoneId
import java.time.ZonedDateTime

data class ClockInEntry(
    val fileId: String,
    val fileName: String,
    val headingTitle: String,
    val l1Title: String?,
    val startedAt: ZonedDateTime,
    val headingLineIndex: Int,
)

class ClockInScanner(
    private val repository: OrgRepository,
    private val parser: OrgParser = OrgParser(),
) {
    suspend fun scan(zoneId: ZoneId): Result<List<ClockInEntry>> {
        val files = repository.listOrgFiles().getOrElse { return Result.failure(it) }
        val entries = mutableListOf<ClockInEntry>()

        for (file in files) {
            val doc = repository.loadFile(file.fileId).getOrElse { return Result.failure(it) }
            val opened = parser.parseHeadingsWithOpenClock(doc.lines, zoneId)
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
        return Result.success(entries)
    }
}
