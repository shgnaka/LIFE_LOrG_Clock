package com.example.orgclock.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val pairingJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
data class SyncPairingInvitationV2(
    val token: String,
    val hostPeerId: String,
    val hostDeviceId: String,
    val hostDisplayName: String,
    val hostSigningPublicKeyBase64: String,
    val certificateSha256: String,
    val endpoint: String,
    val expiresAtEpochMs: Long,
    val hostSigningAlg: String = DEFAULT_SYNC_SIGNING_ALG,
    val capabilities: List<String> = emptyList(),
) {
    init {
        require(token.isNotBlank()) { "Pairing token cannot be blank." }
        require(hostPeerId.isNotBlank()) { "Host peer ID cannot be blank." }
        require(hostDeviceId.isNotBlank()) { "Host device ID cannot be blank." }
        require(hostDisplayName.isNotBlank()) { "Host display name cannot be blank." }
        require(hostSigningPublicKeyBase64.isNotBlank()) { "Host signing public key cannot be blank." }
        require(hostSigningAlg.isNotBlank()) { "Host signing algorithm cannot be blank." }
        require(certificateSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Invalid certificate fingerprint." }
        require(endpoint.startsWith("https://")) { "Endpoint must use HTTPS." }
        require(expiresAtEpochMs > 0) { "Pairing expiry must be positive." }
    }
}

data class SyncPairingExchangeRequestV2(
    val invitationToken: String,
    val requesterPeerId: String,
    val requesterDeviceId: String,
    val requesterDisplayName: String,
    val requesterSigningPublicKeyBase64: String,
    val requestedRole: PeerTrustRole = PeerTrustRole.Full,
    val requesterSigningAlg: String = DEFAULT_SYNC_SIGNING_ALG,
    val capabilities: List<String> = emptyList(),
) {
    init {
        require(invitationToken.isNotBlank()) { "Invitation token cannot be blank." }
        require(requesterPeerId.isNotBlank()) { "Requester peer ID cannot be blank." }
        require(requesterDeviceId.isNotBlank()) { "Requester device ID cannot be blank." }
        require(requesterDisplayName.isNotBlank()) { "Requester display name cannot be blank." }
        require(requesterSigningPublicKeyBase64.isNotBlank()) { "Requester signing public key cannot be blank." }
        require(requesterSigningAlg.isNotBlank()) { "Requester signing algorithm cannot be blank." }
    }
}

data class SyncPairingExchangeResponseV2(
    val hostPeerId: String,
    val hostDeviceId: String,
    val hostDisplayName: String,
    val hostSigningPublicKeyBase64: String,
    val grantedRole: PeerTrustRole,
    val encodedTransportCredential: String,
    val certificateSha256: String,
    val hostSigningAlg: String = DEFAULT_SYNC_SIGNING_ALG,
    val capabilities: List<String> = emptyList(),
) {
    init {
        require(hostPeerId.isNotBlank()) { "Host peer ID cannot be blank." }
        require(hostDeviceId.isNotBlank()) { "Host device ID cannot be blank." }
        require(hostDisplayName.isNotBlank()) { "Host display name cannot be blank." }
        require(hostSigningPublicKeyBase64.isNotBlank()) { "Host signing public key cannot be blank." }
        require(hostSigningAlg.isNotBlank()) { "Host signing algorithm cannot be blank." }
        require(SyncTransportCredentialCodec.decode(encodedTransportCredential).isSuccess) { "Invalid transport credential." }
        require(certificateSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Invalid certificate fingerprint." }
    }
}

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


object SyncPairingInvitationV2Codec {
    fun encode(value: SyncPairingInvitationV2): String = pairingJson.encodeToString(
        InvitationV2Wire.serializer(),
        InvitationV2Wire(
            token = value.token,
            hostPeerId = value.hostPeerId,
            hostDeviceId = value.hostDeviceId,
            hostDisplayName = value.hostDisplayName,
            hostSigningPublicKeyBase64 = value.hostSigningPublicKeyBase64,
            hostSigningAlg = value.hostSigningAlg,
            certificateSha256 = value.certificateSha256.lowercase(),
            endpoint = value.endpoint.trimEnd('/'),
            expiresAtEpochMs = value.expiresAtEpochMs,
            capabilities = value.capabilities,
        ),
    )

    fun decode(raw: String): Result<SyncPairingInvitationV2> = runCatching {
        val wire = pairingJson.decodeFromString(InvitationV2Wire.serializer(), raw)
        require(wire.schema == ORG_CLOCK_INVITE_V2_SCHEMA) { "Unsupported pairing invitation schema: ${wire.schema}" }
        SyncPairingInvitationV2(
            token = wire.token.trim(),
            hostPeerId = wire.hostPeerId.trim(),
            hostDeviceId = wire.hostDeviceId.trim(),
            hostDisplayName = wire.hostDisplayName.trim(),
            hostSigningPublicKeyBase64 = wire.hostSigningPublicKeyBase64.trim(),
            hostSigningAlg = wire.hostSigningAlg.trim(),
            certificateSha256 = wire.certificateSha256.trim().lowercase(),
            endpoint = wire.endpoint.trim().trimEnd('/'),
            expiresAtEpochMs = wire.expiresAtEpochMs,
            capabilities = wire.capabilities,
        )
    }
}

object SyncPairingExchangeV2JsonCodec {
    fun encodeRequest(value: SyncPairingExchangeRequestV2): String = pairingJson.encodeToString(
        RequestV2Wire.serializer(),
        RequestV2Wire(
            invitationToken = value.invitationToken,
            requesterPeerId = value.requesterPeerId,
            requesterDeviceId = value.requesterDeviceId,
            requesterDisplayName = value.requesterDisplayName,
            requesterSigningPublicKeyBase64 = value.requesterSigningPublicKeyBase64,
            requesterSigningAlg = value.requesterSigningAlg,
            requestedRole = value.requestedRole.name.lowercase(),
            capabilities = value.capabilities,
        ),
    )

    fun decodeRequest(raw: String): SyncPairingExchangeRequestV2 {
        val wire = pairingJson.decodeFromString(RequestV2Wire.serializer(), raw)
        require(wire.schema == ORG_CLOCK_PAIRING_EXCHANGE_V2_SCHEMA) { "Unsupported pairing exchange schema: ${wire.schema}" }
        return SyncPairingExchangeRequestV2(
            invitationToken = wire.invitationToken.trim(),
            requesterPeerId = wire.requesterPeerId.trim(),
            requesterDeviceId = wire.requesterDeviceId.trim(),
            requesterDisplayName = wire.requesterDisplayName.trim(),
            requesterSigningPublicKeyBase64 = wire.requesterSigningPublicKeyBase64.trim(),
            requesterSigningAlg = wire.requesterSigningAlg.trim(),
            requestedRole = decodeRole(wire.requestedRole),
            capabilities = wire.capabilities,
        )
    }

    fun encodeResponse(value: SyncPairingExchangeResponseV2): String = pairingJson.encodeToString(
        ResponseV2Wire.serializer(),
        ResponseV2Wire(
            hostPeerId = value.hostPeerId,
            hostDeviceId = value.hostDeviceId,
            hostDisplayName = value.hostDisplayName,
            hostSigningPublicKeyBase64 = value.hostSigningPublicKeyBase64,
            hostSigningAlg = value.hostSigningAlg,
            grantedRole = value.grantedRole.name.lowercase(),
            encodedTransportCredential = value.encodedTransportCredential,
            certificateSha256 = value.certificateSha256.lowercase(),
            capabilities = value.capabilities,
        ),
    )

    fun decodeResponse(raw: String): SyncPairingExchangeResponseV2 {
        val wire = pairingJson.decodeFromString(ResponseV2Wire.serializer(), raw)
        require(wire.schema == ORG_CLOCK_PAIRING_EXCHANGE_RESULT_V2_SCHEMA) { "Unsupported pairing response schema: ${wire.schema}" }
        return SyncPairingExchangeResponseV2(
            hostPeerId = wire.hostPeerId.trim(),
            hostDeviceId = wire.hostDeviceId.trim(),
            hostDisplayName = wire.hostDisplayName.trim(),
            hostSigningPublicKeyBase64 = wire.hostSigningPublicKeyBase64.trim(),
            hostSigningAlg = wire.hostSigningAlg.trim(),
            grantedRole = decodeRole(wire.grantedRole),
            encodedTransportCredential = wire.encodedTransportCredential.trim(),
            certificateSha256 = wire.certificateSha256.trim().lowercase(),
            capabilities = wire.capabilities,
        )
    }

    private fun decodeRole(raw: String): PeerTrustRole = when (raw.trim().lowercase()) {
        "full" -> PeerTrustRole.Full
        "viewer" -> PeerTrustRole.Viewer
        else -> error("Unsupported peer role: $raw")
    }
}
object SyncPairingExchangeJsonCodec {
    fun encodeRequest(value: SyncPairingExchangeRequest): String = pairingJson.encodeToString(RequestWire.serializer(), RequestWire(value.invitationToken, value.deviceId, value.displayName))
    fun decodeRequest(raw: String): SyncPairingExchangeRequest = pairingJson.decodeFromString(RequestWire.serializer(), raw).let { SyncPairingExchangeRequest(it.invitationToken, it.deviceId, it.displayName) }
    fun encodeResponse(value: SyncPairingExchangeResponse): String = pairingJson.encodeToString(ResponseWire.serializer(), ResponseWire(value.encodedTransportCredential))
    fun decodeResponse(raw: String): SyncPairingExchangeResponse = pairingJson.decodeFromString(ResponseWire.serializer(), raw).let { SyncPairingExchangeResponse(it.encodedTransportCredential) }
}

private const val ORG_CLOCK_INVITE_V2_SCHEMA = "orgclock.invite.v2"
private const val ORG_CLOCK_PAIRING_EXCHANGE_V2_SCHEMA = "orgclock.pairing.exchange.v2"
private const val ORG_CLOCK_PAIRING_EXCHANGE_RESULT_V2_SCHEMA = "orgclock.pairing.exchange.result.v2"
@Serializable private data class RequestWire(val invitationToken: String, val deviceId: String, val displayName: String)
@Serializable private data class ResponseWire(val encodedTransportCredential: String)

@Serializable
private data class InvitationV2Wire(
    val schema: String = ORG_CLOCK_INVITE_V2_SCHEMA,
    val token: String,
    val hostPeerId: String,
    val hostDeviceId: String,
    val hostDisplayName: String,
    val hostSigningPublicKeyBase64: String,
    val hostSigningAlg: String = DEFAULT_SYNC_SIGNING_ALG,
    val certificateSha256: String,
    val endpoint: String,
    val expiresAtEpochMs: Long,
    val capabilities: List<String> = emptyList(),
)

@Serializable
private data class RequestV2Wire(
    val schema: String = ORG_CLOCK_PAIRING_EXCHANGE_V2_SCHEMA,
    val invitationToken: String,
    val requesterPeerId: String,
    val requesterDeviceId: String,
    val requesterDisplayName: String,
    val requesterSigningPublicKeyBase64: String,
    val requesterSigningAlg: String = DEFAULT_SYNC_SIGNING_ALG,
    val requestedRole: String,
    val capabilities: List<String> = emptyList(),
)

@Serializable
private data class ResponseV2Wire(
    val schema: String = ORG_CLOCK_PAIRING_EXCHANGE_RESULT_V2_SCHEMA,
    val hostPeerId: String,
    val hostDeviceId: String,
    val hostDisplayName: String,
    val hostSigningPublicKeyBase64: String,
    val hostSigningAlg: String = DEFAULT_SYNC_SIGNING_ALG,
    val grantedRole: String,
    val encodedTransportCredential: String,
    val certificateSha256: String,
    val capabilities: List<String> = emptyList(),
)
