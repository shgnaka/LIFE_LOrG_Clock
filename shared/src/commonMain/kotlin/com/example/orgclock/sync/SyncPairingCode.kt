package com.example.orgclock.sync

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val ORG_CLOCK_PAIRING_SCHEMA_V1 = "orgclock.pairing.v1"
const val ORG_CLOCK_PAIRING_URI_PREFIX = "orgclock://pair?data="

data class SyncPairingCode(
    val peerId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val endpoint: String,
    val role: PeerTrustRole = PeerTrustRole.Full,
) {
    init {
        require(peerId.isNotBlank()) { "Peer ID cannot be blank." }
        require(deviceId.isNotBlank()) { "Device ID cannot be blank." }
        require(displayName.isNotBlank()) { "Display name cannot be blank." }
        require(publicKeyBase64.isNotBlank()) { "Public key cannot be blank." }
        require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            "Endpoint must use HTTP or HTTPS."
        }
    }

    fun toRegistrationRequest(requestedAt: Instant): PeerRegistrationRequest =
        PeerRegistrationRequest(
            peerId = peerId,
            deviceId = deviceId,
            displayName = displayName,
            publicKeyBase64 = publicKeyBase64,
            role = role,
            endpoint = endpoint,
            requestedAt = requestedAt,
        )
}

object SyncPairingCodeCodec {
    fun encode(code: SyncPairingCode): String {
        val wire = PairingWire(
            peerId = code.peerId,
            deviceId = code.deviceId,
            displayName = code.displayName,
            publicKeyBase64 = code.publicKeyBase64,
            endpoint = code.endpoint,
            role = code.role.name.lowercase(),
        )
        return ORG_CLOCK_PAIRING_URI_PREFIX + percentEncode(json.encodeToString(PairingWire.serializer(), wire))
    }

    fun decode(raw: String): Result<SyncPairingCode> = runCatching {
        require(raw.startsWith(ORG_CLOCK_PAIRING_URI_PREFIX)) { "Unsupported pairing code." }
        val payload = percentDecode(raw.removePrefix(ORG_CLOCK_PAIRING_URI_PREFIX))
        val wire = json.decodeFromString(PairingWire.serializer(), payload)
        require(wire.schema == ORG_CLOCK_PAIRING_SCHEMA_V1) { "Unsupported pairing schema: ${wire.schema}" }
        SyncPairingCode(
            peerId = wire.peerId.trim(),
            deviceId = wire.deviceId.trim(),
            displayName = wire.displayName.trim(),
            publicKeyBase64 = wire.publicKeyBase64.trim(),
            endpoint = wire.endpoint.trim().trimEnd('/'),
            role = when (wire.role.lowercase()) {
                "full" -> PeerTrustRole.Full
                "viewer" -> PeerTrustRole.Viewer
                else -> error("Unsupported peer role: ${wire.role}")
            },
        )
    }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class PairingWire(
    val schema: String = ORG_CLOCK_PAIRING_SCHEMA_V1,
    val peerId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val endpoint: String,
    val role: String,
)

private fun percentEncode(raw: String): String = buildString {
    raw.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        if (value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code || value == '-'.code || value == '_'.code ||
            value == '.'.code || value == '~'.code
        ) {
            append(value.toChar())
        } else {
            append('%')
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

private fun percentDecode(raw: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < raw.length) {
        if (raw[index] == '%') {
            require(index + 2 < raw.length) { "Invalid percent encoding." }
            bytes += raw.substring(index + 1, index + 3).toInt(16).toByte()
            index += 3
        } else {
            bytes += raw[index].code.toByte()
            index++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"
