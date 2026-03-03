package com.example.orgclock.sync

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockOperationCode
import com.example.orgclock.domain.ClockOperationException
import com.example.orgclock.domain.ClockService
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.SystemClockEnvironment
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

interface ClockCommandExecutor {
    suspend fun execute(rawPayload: String): ClockResultPayload
}

class DefaultClockCommandExecutor(
    private val repository: ClockRepository,
    private val clockService: ClockService,
    private val commandIdStore: CommandIdStore,
    private val clockEnvironment: ClockEnvironment = SystemClockEnvironment,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        val parsed = parseCommand(rawPayload).getOrElse { parseError ->
            return rejectedResult(
                commandId = UNKNOWN_COMMAND_ID,
                message = parseError.message ?: "Invalid command payload",
            )
        }

        if (commandIdStore.contains(parsed.commandId)) {
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
                return failedResult(
                    command.commandId,
                    ClockErrorCode.IO_FAILURE,
                    error.message ?: "Failed to list org files",
                    now,
                )
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
                return failedResult(
                    command.commandId,
                    ClockErrorCode.IO_FAILURE,
                    error.message ?: "Failed to list headings",
                    now,
                )
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

        val execution = when (command.kind) {
            ClockCommandKind.Start -> {
                clockService.startClockInFile(
                    fileId = fileEntry.fileId,
                    headingLineIndex = heading.node.lineIndex,
                    now = now,
                    timeZone = timeZone,
                )
            }
            ClockCommandKind.Stop -> {
                clockService.stopClockInFile(
                    fileId = fileEntry.fileId,
                    headingLineIndex = heading.node.lineIndex,
                    now = now,
                    timeZone = timeZone,
                )
            }
            ClockCommandKind.Cancel -> {
                clockService.cancelClockInFile(
                    fileId = fileEntry.fileId,
                    headingLineIndex = heading.node.lineIndex,
                )
            }
        }

        return execution.fold(
            onSuccess = {
                ClockResultPayload(
                    commandId = command.commandId,
                    status = ClockResultStatus.Applied,
                    appliedAt = now,
                    byDeviceId = LOCAL_DEVICE_ID,
                )
            },
            onFailure = { error ->
                val mapped = mapExecutionError(error)
                failedResult(
                    command.commandId,
                    mapped.first,
                    mapped.second,
                    now,
                    LOCAL_DEVICE_ID,
                )
            },
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
            byDeviceId = LOCAL_DEVICE_ID,
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
            byDeviceId = LOCAL_DEVICE_ID,
        )
    }

    private fun failedResult(
        commandId: String,
        errorCode: ClockErrorCode,
        message: String,
        appliedAt: Instant,
        byDeviceId: String = LOCAL_DEVICE_ID,
    ): ClockResultPayload {
        return ClockResultPayload(
            commandId = commandId,
            status = ClockResultStatus.Failed,
            errorCode = errorCode,
            errorMessage = message,
            appliedAt = appliedAt,
            byDeviceId = byDeviceId.ifBlank { LOCAL_DEVICE_ID },
        )
    }

    private fun parseCommand(rawPayload: String): Result<ClockCommandPayload> {
        val root = runCatching { json.parseToJsonElement(rawPayload).jsonObject }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid JSON payload")) }

        val schema = root.requiredString("schema")
            ?: return Result.failure(IllegalArgumentException("Missing schema"))
        if (schema != CLOCK_COMMAND_SCHEMA_V1) {
            return Result.failure(IllegalArgumentException("Unsupported schema: $schema"))
        }

        val commandId = root.requiredString("command_id")
            ?: return Result.failure(IllegalArgumentException("Missing command_id"))
        val kindRaw = root.requiredString("kind")
            ?: return Result.failure(IllegalArgumentException("Missing kind"))
        val kind = ClockCommandKind.fromWireValue(kindRaw)
            ?: return Result.failure(IllegalArgumentException("Unknown kind: $kindRaw"))
        val targetObject = root.requiredObject("target")
            ?: return Result.failure(IllegalArgumentException("Missing target"))
        val fileName = targetObject.requiredString("file_name")
            ?: return Result.failure(IllegalArgumentException("Missing target.file_name"))
        val headingPath = targetObject.requiredString("heading_path")
            ?: return Result.failure(IllegalArgumentException("Missing target.heading_path"))
        val requestedAtRaw = root.requiredString("requested_at")
            ?: return Result.failure(IllegalArgumentException("Missing requested_at"))
        val requestedAt = runCatching { Instant.parse(requestedAtRaw) }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid requested_at")) }
        val fromDeviceId = root.requiredString("from_device_id")
            ?: return Result.failure(IllegalArgumentException("Missing from_device_id"))
        val requestId = root.optionalString("request_id")

        return Result.success(
            ClockCommandPayload(
                schema = schema,
                commandId = commandId,
                kind = kind,
                target = ClockCommandTarget(fileName = fileName, headingPath = headingPath),
                requestedAt = requestedAt,
                fromDeviceId = fromDeviceId,
                requestId = requestId,
            ),
        )
    }

    private fun mapExecutionError(error: Throwable): Pair<ClockErrorCode, String> {
        if (error is ClockOperationException) {
            return when (error.code) {
                ClockOperationCode.InvalidHeadingLevel -> ClockErrorCode.INVALID_HEADING_LEVEL to error.message.orEmpty()
                ClockOperationCode.AlreadyRunning -> ClockErrorCode.ALREADY_RUNNING to error.message.orEmpty()
                ClockOperationCode.ValidationFailed -> ClockErrorCode.VALIDATION_FAILED to error.message.orEmpty()
                ClockOperationCode.IoFailed -> ClockErrorCode.IO_FAILURE to error.message.orEmpty()
                ClockOperationCode.Conflict -> ClockErrorCode.CONFLICT_RETRY_EXHAUSTED to error.message.orEmpty()
            }
        }

        val message = error.message ?: "Unexpected execution error"
        if (error is IllegalStateException && message.contains("No open CLOCK")) {
            return ClockErrorCode.NO_OPEN_CLOCK to message
        }
        return ClockErrorCode.IO_FAILURE to message
    }

    private fun JsonObject.requiredString(name: String): String? =
        this[name]
            ?.asString()
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.optionalString(name: String): String? =
        this[name]
            ?.asString()
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredObject(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val UNKNOWN_COMMAND_ID = "unknown"
        const val LOCAL_DEVICE_ID = "local-device"
    }
}
