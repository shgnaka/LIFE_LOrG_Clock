package com.example.orgclock.sync

import io.github.shgnaka.synccore.api.DeliveryErrorCode
import io.github.shgnaka.synccore.api.SyncCommand
import io.github.shgnaka.synccore.api.SyncResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
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
    private val json: Json = Json,
) : SyncTransportGateway {
    override suspend fun dispatch(command: SyncCommand): TransportDispatchResult {
        val endpoint = endpointResolver.resolve(command.targetPeerId).getOrElse { error ->
            return TransportDispatchResult.Rejected(
                errorCode = DeliveryErrorCode.PEER_UNAVAILABLE,
                errorMessage = error.message ?: "invalid target endpoint",
            )
        }

        return runCatching {
            val connection = (URI.create(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("topic", command.topic)
                    put("commandId", command.commandId)
                    put("payloadJson", command.payloadJson)
                    put("createdAtEpochMs", command.createdAtEpochMs)
                    put("targetPeerId", command.targetPeerId)
                },
            )
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
            logger.fine("sync.transport.http.failed commandId=${command.commandId} reason=${error.message ?: "unknown"}")
            TransportDispatchResult.RetryableFailure(
                errorCode = DeliveryErrorCode.NETWORK_UNREACHABLE,
                errorMessage = error.message ?: "network request failed",
            )
        }
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
