package com.example.orgclock.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SyncTransportCredential(
    val pairingSecret: String,
    val certificateSha256: String,
) {
    init {
        require(pairingSecret.isNotBlank()) { "Pairing secret cannot be blank." }
        require(certificateSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "Certificate fingerprint must be a SHA-256 hex string."
        }
    }
}

data class SyncPairingInvitation(
    val token: String,
    val certificateSha256: String,
    val expiresAtEpochMs: Long,
) {
    init {
        require(token.isNotBlank()) { "Pairing token cannot be blank." }
        require(certificateSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Invalid certificate fingerprint." }
        require(expiresAtEpochMs > 0) { "Pairing expiry must be positive." }
    }
}

data class SyncPairingExchangeRequest(
    val invitationToken: String,
    val deviceId: String,
    val displayName: String,
)

data class SyncPairingExchangeResponse(val encodedTransportCredential: String)

object SyncTransportCredentialCodec {
    private const val PREFIX = "orgclock-https-v1"
    fun encode(value: SyncTransportCredential): String =
        "$PREFIX:${value.pairingSecret}:${value.certificateSha256.lowercase()}"
    fun decode(raw: String): Result<SyncTransportCredential> = runCatching {
        val parts = raw.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == PREFIX) { "Unsupported sync credential." }
        SyncTransportCredential(parts[1], parts[2].lowercase())
    }
}

object SyncPairingInvitationCodec {
    private const val PREFIX = "orgclock-invite-v1"
    fun encode(value: SyncPairingInvitation): String =
        "$PREFIX:${value.token}:${value.certificateSha256.lowercase()}:${value.expiresAtEpochMs}"
    fun decode(raw: String): Result<SyncPairingInvitation> = runCatching {
        val parts = raw.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == PREFIX) { "Unsupported pairing invitation." }
        SyncPairingInvitation(parts[1], parts[2], parts[3].toLong())
    }
}

object SyncPairingExchangeJsonCodec {
    fun encodeRequest(value: SyncPairingExchangeRequest): String = json.encodeToString(RequestWire.serializer(), RequestWire(value.invitationToken, value.deviceId, value.displayName))
    fun decodeRequest(raw: String): SyncPairingExchangeRequest = json.decodeFromString(RequestWire.serializer(), raw).let { SyncPairingExchangeRequest(it.invitationToken, it.deviceId, it.displayName) }
    fun encodeResponse(value: SyncPairingExchangeResponse): String = json.encodeToString(ResponseWire.serializer(), ResponseWire(value.encodedTransportCredential))
    fun decodeResponse(raw: String): SyncPairingExchangeResponse = json.decodeFromString(ResponseWire.serializer(), raw).let { SyncPairingExchangeResponse(it.encodedTransportCredential) }
    private val json = Json { ignoreUnknownKeys = true }
}

@Serializable private data class RequestWire(val invitationToken: String, val deviceId: String, val displayName: String)
@Serializable private data class ResponseWire(val encodedTransportCredential: String)
