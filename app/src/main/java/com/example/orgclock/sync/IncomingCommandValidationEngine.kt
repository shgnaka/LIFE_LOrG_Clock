package com.example.orgclock.sync

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal interface IncomingEnvelopeVerifierPort {
    fun verifyCommandEnvelope(rawEnvelopeJson: String): Result<IncomingCommandPayloadContext>
}

internal interface ReplayRegistryPort {
    suspend fun register(senderDeviceId: String, commandId: String): Boolean
}

internal data class IncomingCommandPayloadContext(
    val payloadJson: String,
    val verifiedPeerId: String?,
    val signatureKeyId: String?,
    val expectedSenderDeviceId: String?,
    val verificationMethod: String,
)

internal class IncomingCommandValidationEngine(
    private val allowedSkewSeconds: Long,
    private val replayRegistry: ReplayRegistryPort,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun extractClockCommandPayload(
        uri: String,
        body: String,
        incomingEnvelopeVerifier: IncomingEnvelopeVerifierPort,
    ): Result<VerifiedIncomingCommand> {
        val commandContext = when (uri) {
            PATH_INCOMING_COMMAND -> IncomingCommandPayloadContext(
                payloadJson = body,
                verifiedPeerId = null,
                signatureKeyId = null,
                expectedSenderDeviceId = null,
                verificationMethod = "legacy-debug+schema+sender+timestamp",
            )
            PATH_MESSAGES -> incomingEnvelopeVerifier.verifyCommandEnvelope(body)
                .getOrElse { return Result.failure(it) }
            else -> return Result.failure(IllegalArgumentException("Unsupported endpoint: $uri"))
        }
        return validateClockCommandPayload(uri, commandContext)
    }

    private suspend fun validateClockCommandPayload(
        path: String,
        context: IncomingCommandPayloadContext,
    ): Result<VerifiedIncomingCommand> {
        val payload = context.payloadJson
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { return Result.failure(IllegalArgumentException("Clock command payload must be valid JSON object")) }

        val schema = root.optionalString("schema")
            ?: return Result.failure(IllegalArgumentException("Missing schema"))
        if (schema != CLOCK_COMMAND_SCHEMA_V1) {
            return Result.failure(IllegalArgumentException("Unsupported schema: $schema"))
        }
        val commandId = root.optionalString("command_id")
            ?: return Result.failure(IllegalArgumentException("Missing command_id"))
        val senderDeviceId = root.optionalString("from_device_id")
            ?: return Result.failure(IllegalArgumentException("Missing from_device_id"))
        val expectedSenderDeviceId = context.expectedSenderDeviceId
        if (!expectedSenderDeviceId.isNullOrBlank() && senderDeviceId != expectedSenderDeviceId) {
            return Result.failure(IllegalArgumentException("sender_device_id mismatch with signed envelope"))
        }
        val requestedAtRaw = root.optionalString("requested_at")
            ?: return Result.failure(IllegalArgumentException("Missing requested_at"))
        val requestedAt = runCatching { Instant.parse(requestedAtRaw) }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid requested_at")) }
        val now = Clock.System.now()
        val skew = kotlin.math.abs(now.epochSeconds - requestedAt.epochSeconds)
        if (skew > allowedSkewSeconds) {
            return Result.failure(IllegalArgumentException("requested_at outside allowed skew"))
        }

        val replayPassed = if (path == PATH_MESSAGES) {
            runCatching { replayRegistry.register(senderDeviceId, commandId) }
                .getOrElse { return Result.failure(IllegalArgumentException("replay registry unavailable")) }
        } else {
            true
        }
        if (!replayPassed) {
            return Result.failure(IllegalArgumentException("duplicate command_id replay"))
        }

        val peerId = root.optionalString("peer_id")
        val verifiedPeerId = context.verifiedPeerId ?: peerId ?: senderDeviceId
        return Result.success(
            VerifiedIncomingCommand(
                payloadJson = payload,
                commandId = commandId,
                senderDeviceId = senderDeviceId,
                peerId = peerId ?: senderDeviceId,
                verifiedPeerId = verifiedPeerId,
                verificationState = IncomingVerificationState.Verified,
                verificationMethod = context.verificationMethod,
                signatureKeyId = context.signatureKeyId ?: root.optionalString("signature_key_id"),
                replayCheckPassed = replayPassed,
                receivedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun JsonObject.optionalString(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val PATH_MESSAGES = "/v1/messages"
        const val PATH_INCOMING_COMMAND = "/v1/incoming-command"
    }
}
