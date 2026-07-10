package com.example.orgclock.desktop

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockOperationCode
import com.example.orgclock.domain.ClockOperationException
import com.example.orgclock.domain.ClockService
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.sync.CLOCK_COMMAND_SCHEMA_V1
import com.example.orgclock.sync.ClockCommandKind
import com.example.orgclock.sync.ClockCommandPayload
import com.example.orgclock.sync.ClockCommandTarget
import com.example.orgclock.sync.ClockErrorCode
import com.example.orgclock.sync.ClockResultPayload
import com.example.orgclock.sync.ClockResultStatus
import com.example.orgclock.time.ClockEnvironment
import java.util.prefs.Preferences
import java.util.logging.Logger
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

fun interface DesktopClockCommandExecutor {
    suspend fun execute(rawPayload: String): ClockResultPayload
}

interface DesktopCommandIdStore {
    suspend fun contains(commandId: String): Boolean
    suspend fun markProcessed(commandId: String)
}

class DesktopProcessedCommandIdStore(
    private val preferences: Preferences,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) : DesktopCommandIdStore {
    override suspend fun contains(commandId: String): Boolean =
        entries().any { it.commandId == commandId }

    override suspend fun markProcessed(commandId: String) {
        val normalized = commandId.trim()
        if (normalized.isBlank()) return
        val now = nowEpochMs()
        val updated = entries()
            .filterNot { it.commandId == normalized }
            .filter { it.processedAtEpochMs >= now - retentionMs }
            .plus(ProcessedCommandId(normalized, now))
            .sortedBy { it.processedAtEpochMs }
            .takeLast(maxRows)
        preferences.put(KEY_PROCESSED_COMMAND_IDS, updated.joinToString("\n") { it.encode() })
        preferences.flush()
    }

    private fun entries(): List<ProcessedCommandId> = preferences.get(KEY_PROCESSED_COMMAND_IDS, null)
        .orEmpty()
        .lineSequence()
        .mapNotNull(ProcessedCommandId::decode)
        .toList()

    private data class ProcessedCommandId(
        val commandId: String,
        val processedAtEpochMs: Long,
    ) {
        fun encode(): String = "$processedAtEpochMs\t$commandId"

        companion object {
            fun decode(raw: String): ProcessedCommandId? {
                val parts = raw.split('\t', limit = 2)
                if (parts.size != 2) return null
                val processedAt = parts[0].toLongOrNull() ?: return null
                val commandId = parts[1].trim().takeIf { it.isNotBlank() } ?: return null
                return ProcessedCommandId(commandId, processedAt)
            }
        }
    }

    private companion object {
        const val KEY_PROCESSED_COMMAND_IDS = "sync_core_processed_command_ids"
        const val DEFAULT_RETENTION_MS = 90L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAX_ROWS = 20_000
    }
}

class DefaultDesktopClockCommandExecutor(
    private val repository: ClockRepository,
    private val clockService: ClockService,
    private val commandIdStore: DesktopCommandIdStore,
    private val deviceIdProvider: () -> String,
    private val clockEnvironment: ClockEnvironment,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DesktopClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        val parsed = parseCommand(rawPayload).getOrElse { parseError ->
            logger.fine("sync.desktop.command.rejected reason=${parseError.message ?: "invalid payload"}")
            return rejectedResult(
                commandId = UNKNOWN_COMMAND_ID,
                message = parseError.message ?: "Invalid command payload",
            )
        }

        if (commandIdStore.contains(parsed.commandId)) {
            logger.fine("sync.desktop.command.duplicate commandId=${parsed.commandId}")
            return duplicateResult(parsed.commandId)
        }

        val result = executeParsed(parsed)
        commandIdStore.markProcessed(parsed.commandId)
        return result
    }

    private suspend fun executeParsed(command: ClockCommandPayload): ClockResultPayload {
        val now = clockEnvironment.now()
        val timeZone = clockEnvironment.currentTimeZone()
        val fileEntry = repository.listOrgFiles()
            .getOrElse { error ->
                return failedResult(command.commandId, ClockErrorCode.IO_FAILURE, error.message ?: "Failed to list org files", now)
            }
            .firstOrNull { it.displayName == command.target.fileName }
            ?: return failedResult(
                command.commandId,
                ClockErrorCode.TARGET_FILE_NOT_FOUND,
                "Target file not found: ${command.target.fileName}",
                now,
            )
        val heading = clockService.listHeadings(fileEntry.fileId, timeZone)
            .getOrElse { error ->
                return failedResult(command.commandId, ClockErrorCode.IO_FAILURE, error.message ?: "Failed to list headings", now)
            }
            .firstOrNull { it.node.path.toString() == command.target.headingPath }
            ?: return failedResult(
                command.commandId,
                ClockErrorCode.TARGET_HEADING_NOT_FOUND,
                "Target heading not found: ${command.target.headingPath}",
                now,
            )
        if (heading.node.level != 2) {
            return failedResult(
                command.commandId,
                ClockErrorCode.INVALID_HEADING_LEVEL,
                "Clock operation is only allowed on level-2 headings",
                now,
            )
        }

        val headingPath = HeadingPath.parse(command.target.headingPath)
        val execution = when (command.kind) {
            ClockCommandKind.Start -> clockService.startClockInFile(fileEntry.fileId, headingPath, now, timeZone)
            ClockCommandKind.Stop -> clockService.stopClockInFile(fileEntry.fileId, headingPath, now, timeZone)
            ClockCommandKind.Cancel -> clockService.cancelClockInFile(fileEntry.fileId, headingPath)
        }
        return execution.fold(
            onSuccess = {
                ClockResultPayload(
                    commandId = command.commandId,
                    status = ClockResultStatus.Applied,
                    appliedAt = now,
                    byDeviceId = deviceIdProvider(),
                )
            },
            onFailure = { error ->
                val mapped = mapExecutionError(error)
                failedResult(command.commandId, mapped.first, mapped.second, now)
            },
        )
    }

    private fun parseCommand(rawPayload: String): Result<ClockCommandPayload> {
        val root = runCatching { json.parseToJsonElement(rawPayload).jsonObject }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid JSON payload")) }
        val schema = root.requiredString("schema") ?: return Result.failure(IllegalArgumentException("Missing schema"))
        if (schema != CLOCK_COMMAND_SCHEMA_V1) {
            return Result.failure(IllegalArgumentException("Unsupported schema: $schema"))
        }
        val commandId = root.requiredString("command_id")
            ?: return Result.failure(IllegalArgumentException("Missing command_id"))
        val kindRaw = root.requiredString("kind")
            ?: return Result.failure(IllegalArgumentException("Missing kind"))
        val kind = ClockCommandKind.fromWireValue(kindRaw)
            ?: return Result.failure(IllegalArgumentException("Unknown kind: $kindRaw"))
        val target = root.requiredObject("target")
            ?: return Result.failure(IllegalArgumentException("Missing target"))
        val fileName = target.requiredString("file_name")
            ?: return Result.failure(IllegalArgumentException("Missing target.file_name"))
        val headingPath = target.requiredString("heading_path")
            ?: return Result.failure(IllegalArgumentException("Missing target.heading_path"))
        val requestedAtRaw = root.requiredString("requested_at")
            ?: return Result.failure(IllegalArgumentException("Missing requested_at"))
        val requestedAt = runCatching { Instant.parse(requestedAtRaw) }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid requested_at")) }
        val fromDeviceId = root.requiredString("from_device_id")
            ?: return Result.failure(IllegalArgumentException("Missing from_device_id"))
        return Result.success(
            ClockCommandPayload(
                schema = schema,
                commandId = commandId,
                kind = kind,
                target = ClockCommandTarget(fileName = fileName, headingPath = headingPath),
                requestedAt = requestedAt,
                fromDeviceId = fromDeviceId,
                requestId = root.optionalString("request_id"),
            ),
        )
    }

    private fun rejectedResult(commandId: String, message: String): ClockResultPayload {
        val now = clockEnvironment.now()
        return ClockResultPayload(
            commandId = commandId,
            status = ClockResultStatus.Rejected,
            errorCode = ClockErrorCode.VALIDATION_FAILED,
            errorMessage = message,
            appliedAt = now,
            byDeviceId = deviceIdProvider(),
        )
    }

    private fun duplicateResult(commandId: String): ClockResultPayload {
        val now = clockEnvironment.now()
        return ClockResultPayload(
            commandId = commandId,
            status = ClockResultStatus.Duplicate,
            errorCode = ClockErrorCode.DUPLICATE_COMMAND,
            errorMessage = "Duplicate command id: $commandId",
            appliedAt = now,
            byDeviceId = deviceIdProvider(),
        )
    }

    private fun failedResult(
        commandId: String,
        errorCode: ClockErrorCode,
        message: String,
        appliedAt: Instant,
    ): ClockResultPayload = ClockResultPayload(
        commandId = commandId,
        status = ClockResultStatus.Failed,
        errorCode = errorCode,
        errorMessage = message,
        appliedAt = appliedAt,
        byDeviceId = deviceIdProvider(),
    )

    private fun mapExecutionError(error: Throwable): Pair<ClockErrorCode, String> {
        if (error is ClockOperationException) {
            return when (error.code) {
                ClockOperationCode.InvalidHeadingLevel -> ClockErrorCode.INVALID_HEADING_LEVEL to error.message.orEmpty()
                ClockOperationCode.AlreadyRunning -> ClockErrorCode.ALREADY_RUNNING to error.message.orEmpty()
                ClockOperationCode.ValidationFailed -> ClockErrorCode.VALIDATION_FAILED to error.message.orEmpty()
                ClockOperationCode.IoFailed -> ClockErrorCode.IO_FAILURE to error.message.orEmpty()
                ClockOperationCode.Conflict -> ClockErrorCode.CONFLICT_RETRY_EXHAUSTED to error.message.orEmpty()
                ClockOperationCode.SaveRoundTripMismatch -> ClockErrorCode.SAVE_ROUND_TRIP_MISMATCH to error.message.orEmpty()
            }
        }
        val message = error.message ?: "Unexpected execution error"
        if (error is IllegalStateException && message.contains("No open CLOCK")) {
            return ClockErrorCode.NO_OPEN_CLOCK to message
        }
        return ClockErrorCode.IO_FAILURE to message
    }

    private fun JsonObject.requiredString(name: String): String? =
        this[name]?.asString()?.takeIf { it.isNotBlank() }

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.asString()?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredObject(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        private val logger: Logger = Logger.getLogger(DefaultDesktopClockCommandExecutor::class.java.name)
        const val UNKNOWN_COMMAND_ID = "unknown"
    }
}
