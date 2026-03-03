package com.example.orgclock.sync

import io.github.shgnaka.synccore.api.DeliveryErrorCode
import io.github.shgnaka.synccore.api.Envelope
import io.github.shgnaka.synccore.api.MessageType
import io.github.shgnaka.synccore.api.ResultStatus
import io.github.shgnaka.synccore.api.SyncCommand
import io.github.shgnaka.synccore.api.SyncResult
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.logging.Logger

interface SyncTransportGateway {
    suspend fun dispatch(command: SyncCommand): TransportDispatchResult
}

sealed interface TransportDispatchResult {
    data class Accepted(val result: SyncResult? = null) : TransportDispatchResult
    data class RetryableFailure(
        val errorCode: DeliveryErrorCode,
        val errorMessage: String? = null,
    ) : TransportDispatchResult

    data class Rejected(
        val errorCode: DeliveryErrorCode,
        val errorMessage: String? = null,
    ) : TransportDispatchResult
}

class NoOpSyncTransportGateway : SyncTransportGateway {
    override suspend fun dispatch(command: SyncCommand): TransportDispatchResult {
        return TransportDispatchResult.Rejected(
            errorCode = DeliveryErrorCode.NETWORK_UNREACHABLE,
            errorMessage = "sync transport is not configured",
        )
    }
}

class HttpSyncTransportGateway(
    private val endpointResolver: SyncEndpointResolver = DefaultSyncEndpointResolver(),
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
    private val localDeviceIdProvider: (() -> String)? = null,
    private val resultEnvelopeSigner: ResultEnvelopeSigner? = null,
    private val json: Json = Json,
) : SyncTransportGateway {
    override suspend fun dispatch(command: SyncCommand): TransportDispatchResult {
        val endpoint = endpointResolver.resolve(command.targetPeerId).getOrElse { error ->
            return TransportDispatchResult.Rejected(
                errorCode = DeliveryErrorCode.PEER_UNAVAILABLE,
                errorMessage = error.message ?: "invalid target endpoint",
            )
        }

        val payload = if (command.topic == CLOCK_RESULT_SCHEMA_V1) {
            buildSignedResultEnvelopePayload(command).getOrElse { error ->
                logger.fine("sync.transport.result.envelope_failed commandId=${command.commandId} reason=${error.message ?: "unknown"}")
                return TransportDispatchResult.Rejected(
                    errorCode = DeliveryErrorCode.INVALID_PAYLOAD,
                    errorMessage = error.message ?: "result envelope signing failed",
                )
            }
        } else {
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("topic", command.topic)
                    put("commandId", command.commandId)
                    put("payloadJson", command.payloadJson)
                    put("createdAtEpochMs", command.createdAtEpochMs)
                    put("targetPeerId", command.targetPeerId)
                },
            )
        }

        return postPayload(endpoint, payload, command.commandId)
    }

    private fun postPayload(
        endpoint: String,
        payload: String,
        commandId: String,
    ): TransportDispatchResult {
        return runCatching {
            val connection = (URI.create(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
            }
            val status = connection.responseCode
            when (status) {
                in 200..299 -> TransportDispatchResult.Accepted()

                400, 401, 403, 404, 422 -> TransportDispatchResult.Rejected(
                    errorCode = DeliveryErrorCode.INVALID_PAYLOAD,
                    errorMessage = "http_status=$status",
                )

                408, 429, 500, 502, 503, 504 -> TransportDispatchResult.RetryableFailure(
                    errorCode = DeliveryErrorCode.NETWORK_UNREACHABLE,
                    errorMessage = "http_status=$status",
                )

                else -> TransportDispatchResult.RetryableFailure(
                    errorCode = DeliveryErrorCode.NETWORK_UNREACHABLE,
                    errorMessage = "http_status=$status",
                )
            }
        }.getOrElse { error ->
            logger.fine("sync.transport.http.failed commandId=$commandId reason=${error.message ?: "unknown"}")
            TransportDispatchResult.RetryableFailure(
                errorCode = DeliveryErrorCode.NETWORK_UNREACHABLE,
                errorMessage = error.message ?: "network request failed",
            )
        }
    }

    private fun buildSignedResultEnvelopePayload(command: SyncCommand): Result<String> = runCatching {
        val senderDeviceId = requireNotNull(localDeviceIdProvider?.invoke()?.takeIf { it.isNotBlank() }) {
            "localDeviceId is required for result envelope dispatch"
        }
        val signer = requireNotNull(resultEnvelopeSigner) {
            "result envelope signer is required"
        }
        val result = parseResultPayload(command)
        val unsigned = Envelope(
            schemaVersion = 1,
            messageType = MessageType.RESULT,
            messageId = UUID.randomUUID().toString(),
            senderDeviceId = senderDeviceId,
            sentAtEpochMs = System.currentTimeMillis(),
            nonce = UUID.randomUUID().toString(),
            payloadJson = json.encodeToString(SyncResult.serializer(), result),
            signatureBase64 = "",
        )
        val signature = signer.signCanonical(canonicalizeEnvelope(unsigned)).getOrElse { throw it }
        json.encodeToString(Envelope.serializer(), unsigned.copy(signatureBase64 = signature))
    }

    private fun parseResultPayload(command: SyncCommand): SyncResult {
        runCatching { json.decodeFromString(SyncResult.serializer(), command.payloadJson) }
            .getOrNull()
            ?.let { parsed ->
                return parsed.copy(commandId = command.commandId)
            }
        val root = runCatching { json.parseToJsonElement(command.payloadJson) }
            .getOrNull()
            ?.let { it as? JsonObject }
            ?: throw IllegalArgumentException("result payload is not valid JSON object")
        val status = when (root["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
            "applied" -> ResultStatus.APPLIED
            "failed" -> ResultStatus.FAILED
            "duplicate" -> ResultStatus.DUPLICATE
            "rejected" -> ResultStatus.REJECTED
            else -> throw IllegalArgumentException("unsupported result status")
        }
        val appliedAtEpochMs = root["applied_at"]?.jsonPrimitive?.contentOrNull
            ?.let { raw ->
                runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
            }
        return SyncResult(
            commandId = root["command_id"]?.jsonPrimitive?.contentOrNull ?: command.commandId,
            status = status,
            errorCode = root["error_code"]?.jsonPrimitive?.contentOrNull,
            errorMessage = root["error_message"]?.jsonPrimitive?.contentOrNull,
            appliedAtEpochMs = appliedAtEpochMs,
        )
    }

    private fun canonicalizeEnvelope(envelope: Envelope): String {
        return listOf(
            envelope.schemaVersion.toString(),
            envelope.messageType.name,
            envelope.messageId,
            envelope.senderDeviceId,
            envelope.sentAtEpochMs.toString(),
            envelope.nonce,
            envelope.payloadJson,
        ).joinToString("\n")
    }

    private companion object {
        private val logger: Logger = Logger.getLogger(HttpSyncTransportGateway::class.java.name)
    }
}

interface SyncEndpointResolver {
    fun resolve(targetPeerId: String): Result<String>
}

class DefaultSyncEndpointResolver(
    private val defaultPort: Int = 39091,
) : SyncEndpointResolver {
    override fun resolve(targetPeerId: String): Result<String> {
        val trimmed = targetPeerId.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("target peer id is empty"))
        }
        if (trimmed.startsWith("https://")) {
            return Result.success("$trimmed/v1/messages")
        }
        if (trimmed.startsWith("http://")) {
            return Result.failure(IllegalArgumentException("cleartext endpoint is not allowed: $trimmed"))
        }
        return Result.success("https://$trimmed:$defaultPort/v1/messages")
    }
}
