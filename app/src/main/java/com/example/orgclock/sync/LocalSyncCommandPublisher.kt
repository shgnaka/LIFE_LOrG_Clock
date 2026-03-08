package com.example.orgclock.sync

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.model.HeadingPath
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class ResolvedClockTarget(
    val fileName: String,
    val headingPath: String,
)

class ClockTargetResolver(
    private val repository: ClockRepository,
) {
    suspend fun resolve(fileId: String, headingPath: HeadingPath): Result<ResolvedClockTarget> {
        val fileEntry = repository.listOrgFiles().getOrElse {
            return Result.failure(IllegalStateException("Failed to list org files: ${it.message}"))
        }.firstOrNull { it.fileId == fileId }
            ?: return Result.failure(IllegalArgumentException("Unknown file id: $fileId"))

        return Result.success(
            ResolvedClockTarget(
                fileName = fileEntry.displayName,
                headingPath = headingPath.toString(),
            ),
        )
    }
}

class LocalClockOperationPublisher(
    private val syncIntegrationService: SyncIntegrationService,
    private val targetResolver: ClockTargetResolver,
    private val deviceIdProvider: DeviceIdProvider,
    private val runtimePrefs: SyncRuntimePrefs,
    private val json: Json = Json,
) {
    suspend fun publish(kind: ClockCommandKind, fileId: String, headingPath: HeadingPath) {
        val targetPeerId = runtimePrefs.defaultPeerId() ?: return
        val target = targetResolver.resolve(fileId, headingPath).getOrElse {
            syncIntegrationService.markSyncError("target resolve failed: ${it.message ?: "unknown"}")
            return
        }
        val commandId = "cmd-${UUID.randomUUID()}"
        val payloadJson = buildPayload(commandId = commandId, kind = kind, target = target)
        val result = syncIntegrationService.submitOutgoingCommand(
            OutgoingClockCommand(
                commandId = commandId,
                payloadJson = payloadJson,
                targetPeerId = targetPeerId,
            ),
        )
        if (result !is SubmitResult.Submitted) {
            syncIntegrationService.markSyncError("submit failed: $result")
        }
    }

    private fun buildPayload(commandId: String, kind: ClockCommandKind, target: ResolvedClockTarget): String {
        return json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("schema", CLOCK_COMMAND_SCHEMA_V1)
                put("command_id", commandId)
                put("kind", kind.wireValue)
                put(
                    "target",
                    buildJsonObject {
                        put("file_name", target.fileName)
                        put("heading_path", target.headingPath)
                    },
                )
                put("requested_at", Clock.System.now().toString())
                put("from_device_id", deviceIdProvider.getOrCreate())
                put("request_id", commandId)
            },
        )
    }
}
