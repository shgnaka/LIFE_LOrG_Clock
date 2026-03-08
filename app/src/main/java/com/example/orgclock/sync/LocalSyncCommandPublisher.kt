package com.example.orgclock.sync

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class ClockPublishTarget(
    val fileName: String,
    val headingPath: String,
)

class LocalClockOperationPublisher(
    private val syncIntegrationService: SyncIntegrationService,
    private val deviceIdProvider: DeviceIdProvider,
    private val runtimePrefs: SyncRuntimePrefs,
    private val json: Json = Json,
) {
    suspend fun publish(kind: ClockCommandKind, fileName: String, headingPath: String) {
        val targetPeerId = runtimePrefs.defaultPeerId() ?: return
        val target = ClockPublishTarget(fileName = fileName, headingPath = headingPath)
        val commandId = "cmd-${UUID.randomUUID()}"
        val payloadJson = buildClockCommandPayloadJson(
            commandId = commandId,
            kind = kind,
            target = target,
            deviceId = deviceIdProvider.getOrCreate(),
            json = json,
        )
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
}

internal fun buildClockCommandPayloadJson(
    commandId: String,
    kind: ClockCommandKind,
    target: ClockPublishTarget,
    deviceId: String,
    json: Json = Json,
): String {
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
            put("from_device_id", deviceId)
            put("request_id", commandId)
        },
    )
}
