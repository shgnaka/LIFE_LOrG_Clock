package com.example.orgclock.sync

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockService
import kotlinx.datetime.TimeZone

fun interface RemoteClockEventApplier {
    suspend fun apply(event: ClockEvent): Result<Unit>
}

class RepositoryRemoteClockEventApplier(
    private val repository: ClockRepository,
    private val clockService: ClockService,
    private val timeZoneProvider: () -> TimeZone = TimeZone::currentSystemDefault,
) : RemoteClockEventApplier {
    override suspend fun apply(event: ClockEvent): Result<Unit> {
        val file = repository.listOrgFiles()
            .getOrElse { return Result.failure(it) }
            .firstOrNull { it.displayName == event.fileName }
            ?: return Result.failure(IllegalStateException("Target file not found: ${event.fileName}"))

        return when (event.eventType) {
            ClockEventType.Started -> clockService.startClockInFile(
                fileId = file.fileId,
                headingPath = event.headingPath,
                now = event.createdAt,
                timeZone = timeZoneProvider(),
            ).map { Unit }

            ClockEventType.Stopped -> clockService.stopClockInFile(
                fileId = file.fileId,
                headingPath = event.headingPath,
                now = event.createdAt,
                timeZone = timeZoneProvider(),
            ).map { Unit }

            ClockEventType.Cancelled -> clockService.cancelClockInFile(
                fileId = file.fileId,
                headingPath = event.headingPath,
            ).map { Unit }
        }
    }
}
