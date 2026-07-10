package com.example.orgclock.desktop

import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingState
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncStoreOutgoingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class DesktopFileSyncStore(
    private val path: Path,
    private val json: Json = Json { prettyPrint = true },
) : SyncStore {
    override suspend fun load(): SyncStoreLoadResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!Files.exists(path)) return@runCatching SyncStoreSnapshot()
            json.parseToJsonElement(Files.readString(path)).jsonObject.toSyncStoreSnapshot()
        }.fold(
            onSuccess = { SyncStoreLoadResult.Loaded(it) },
            onFailure = { SyncStoreLoadResult.Failed(SyncError(SyncErrorCode.StoreReadFailed, it.message)) },
        )
    }

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult = withContext(Dispatchers.IO) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), snapshot.toJsonObject()))
            runCatching {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }.fold(
            onSuccess = { SyncStoreSaveResult.Saved },
            onFailure = { SyncStoreSaveResult.Failed(SyncError(SyncErrorCode.StoreWriteFailed, it.message)) },
        )
    }

    override suspend fun healthCheck(): StoreHealth = withContext(Dispatchers.IO) {
        runCatching {
            path.parent?.let(Files::createDirectories)
        }.fold(
            onSuccess = { StoreHealth.Healthy },
            onFailure = { StoreHealth.Unavailable(SyncError(SyncErrorCode.StoreUnavailable, it.message)) },
        )
    }
}

private fun SyncStoreSnapshot.toJsonObject(): JsonObject = buildJsonObject {
    put("schemaVersion", 1)
    put("nextRecordSequence", nextRecordSequence)
    put("nextEventSequence", nextEventSequence)
    put("nextReceiptSequence", nextReceiptSequence)
    put("outgoing", JsonArray(outgoing.map { it.toJsonObject() }))
    put("incoming", JsonArray(incoming.map { it.toJsonObject() }))
    put("deliveryEvents", JsonArray(deliveryEvents.map { it.toJsonObject() }))
    put("metrics", metrics.toJsonObject())
}

private fun JsonObject.toSyncStoreSnapshot(): SyncStoreSnapshot = SyncStoreSnapshot(
    outgoing = array("outgoing").map { it.jsonObject.toOutgoingRecord() },
    incoming = array("incoming").map { it.jsonObject.toIncomingRecord() },
    deliveryEvents = array("deliveryEvents").map { it.jsonObject.toDeliveryEvent() },
    metrics = objectOrNull("metrics")?.toMetrics() ?: SyncStoreMetrics(),
    nextRecordSequence = long("nextRecordSequence") ?: 1L,
    nextEventSequence = long("nextEventSequence") ?: 1L,
    nextReceiptSequence = long("nextReceiptSequence") ?: 1L,
)

private fun SyncStoreOutgoingRecord.toJsonObject(): JsonObject = buildJsonObject {
    put("sequence", sequence)
    put("message", message.toJsonObject())
    put("state", state.name)
    put("attempt", attempt)
    put("nextAttemptAtEpochMs", nextAttemptAtEpochMs)
    put("lastError", lastError?.toJsonObject() ?: JsonNull)
    put("leaseUntilEpochMs", leaseUntilEpochMs)
    put("terminalAtEpochMs", terminalAtEpochMs)
}

private fun JsonObject.toOutgoingRecord(): SyncStoreOutgoingRecord = SyncStoreOutgoingRecord(
    sequence = requiredLong("sequence"),
    message = requiredObject("message").toSyncMessage(),
    state = DeliveryState.valueOf(requiredString("state")),
    attempt = int("attempt") ?: 0,
    nextAttemptAtEpochMs = long("nextAttemptAtEpochMs"),
    lastError = objectOrNull("lastError")?.toSyncError(),
    leaseUntilEpochMs = long("leaseUntilEpochMs"),
    terminalAtEpochMs = long("terminalAtEpochMs"),
)

private fun SyncStoreIncomingRecord.toJsonObject(): JsonObject = buildJsonObject {
    put("receiptId", receiptId)
    put("senderPeerId", senderPeerId.value)
    put("targetPeerId", targetPeerId.value)
    put("messageId", messageId.value)
    put("topic", topic.value)
    put("payloadJson", payloadJson)
    put("payloadSha256", payloadSha256)
    put("receivedAtEpochMs", receivedAtEpochMs)
    put("state", state.name)
    put("availableAtEpochMs", availableAtEpochMs)
    put("deliveryCount", deliveryCount)
    put("lastError", lastError?.toJsonObject() ?: JsonNull)
    put("processedAtEpochMs", processedAtEpochMs)
}

private fun JsonObject.toIncomingRecord(): SyncStoreIncomingRecord = SyncStoreIncomingRecord(
    receiptId = requiredString("receiptId"),
    senderPeerId = PeerId(requiredString("senderPeerId")),
    targetPeerId = PeerId(requiredString("targetPeerId")),
    messageId = MessageId(requiredString("messageId")),
    topic = Topic(requiredString("topic")),
    payloadJson = requiredString("payloadJson"),
    payloadSha256 = requiredString("payloadSha256"),
    receivedAtEpochMs = requiredLong("receivedAtEpochMs"),
    state = SyncStoreIncomingState.valueOf(requiredString("state")),
    availableAtEpochMs = long("availableAtEpochMs"),
    deliveryCount = int("deliveryCount") ?: 0,
    lastError = objectOrNull("lastError")?.toSyncError(),
    processedAtEpochMs = long("processedAtEpochMs"),
)

private fun DeliveryEvent.toJsonObject(): JsonObject = buildJsonObject {
    put("sequence", sequence)
    put("messageId", messageId.value)
    put("peerId", peerId.value)
    put("topic", topic.value)
    put("state", state.name)
    put("attempt", attempt)
    put("occurredAtEpochMs", occurredAtEpochMs)
    put("error", error?.toJsonObject() ?: JsonNull)
}

private fun JsonObject.toDeliveryEvent(): DeliveryEvent = DeliveryEvent(
    sequence = requiredLong("sequence"),
    messageId = MessageId(requiredString("messageId")),
    peerId = PeerId(requiredString("peerId")),
    topic = Topic(requiredString("topic")),
    state = DeliveryState.valueOf(requiredString("state")),
    attempt = int("attempt") ?: 0,
    occurredAtEpochMs = requiredLong("occurredAtEpochMs"),
    error = objectOrNull("error")?.toSyncError(),
)

private fun SyncMessage.toJsonObject(): JsonObject = buildJsonObject {
    put("messageId", messageId.value)
    put("topic", topic.value)
    put("payloadJson", payloadJson)
    put("targetPeerId", targetPeerId.value)
    put("createdAtEpochMs", createdAtEpochMs)
    put("expiresAtEpochMs", expiresAtEpochMs)
}

private fun JsonObject.toSyncMessage(): SyncMessage = SyncMessage(
    messageId = MessageId(requiredString("messageId")),
    topic = Topic(requiredString("topic")),
    payloadJson = requiredString("payloadJson"),
    targetPeerId = PeerId(requiredString("targetPeerId")),
    createdAtEpochMs = requiredLong("createdAtEpochMs"),
    expiresAtEpochMs = long("expiresAtEpochMs"),
)

private fun SyncStoreMetrics.toJsonObject(): JsonObject = buildJsonObject {
    put("submittedTotal", submittedTotal)
    put("acceptedTotal", acceptedTotal)
    put("rejectedTotal", rejectedTotal)
    put("retryAttemptsTotal", retryAttemptsTotal)
    put("incomingRejectedTotal", incomingRejectedTotal)
    put("expiredLeaseRecoveryTotal", expiredLeaseRecoveryTotal)
    put("persistenceErrorTotal", persistenceErrorTotal)
    put(
        "lastSuccessfulDispatchByPeer",
        JsonArray(
            lastSuccessfulDispatchByPeer.map { (peerId, epochMs) ->
                buildJsonObject {
                    put("peerId", peerId.value)
                    put("epochMs", epochMs)
                }
            },
        ),
    )
}

private fun JsonObject.toMetrics(): SyncStoreMetrics = SyncStoreMetrics(
    submittedTotal = long("submittedTotal") ?: 0L,
    acceptedTotal = long("acceptedTotal") ?: 0L,
    rejectedTotal = long("rejectedTotal") ?: 0L,
    retryAttemptsTotal = long("retryAttemptsTotal") ?: 0L,
    incomingRejectedTotal = long("incomingRejectedTotal") ?: 0L,
    expiredLeaseRecoveryTotal = long("expiredLeaseRecoveryTotal") ?: 0L,
    persistenceErrorTotal = long("persistenceErrorTotal") ?: 0L,
    lastSuccessfulDispatchByPeer = array("lastSuccessfulDispatchByPeer")
        .associate { element ->
            val entry = element.jsonObject
            PeerId(entry.requiredString("peerId")) to entry.requiredLong("epochMs")
        },
)

private fun SyncError.toJsonObject(): JsonObject = buildJsonObject {
    put("code", code.name)
    put("detail", detail)
    put("retryAtEpochMs", retryAtEpochMs)
}

private fun JsonObject.toSyncError(): SyncError = SyncError(
    code = SyncErrorCode.valueOf(requiredString("code")),
    detail = string("detail"),
    retryAtEpochMs = long("retryAtEpochMs"),
)

private fun JsonObject.array(name: String): List<JsonElement> =
    (this[name] as? JsonArray)?.toList().orEmpty()

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    this[name]?.takeUnless { it is kotlinx.serialization.json.JsonNull } as? JsonObject

private fun JsonObject.requiredObject(name: String): JsonObject =
    objectOrNull(name) ?: error("Missing object: $name")

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: error("Missing string: $name")

private fun JsonObject.requiredLong(name: String): Long =
    long(name) ?: error("Missing long: $name")

private fun JsonObject.string(name: String): String? =
    this[name]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long? =
    this[name]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.longOrNull

private fun JsonObject.int(name: String): Int? =
    this[name]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.intOrNull
