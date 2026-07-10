package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode

internal class EndpointPolicy {
    fun validate(endpoint: String): EndpointPolicyResult {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) {
            return EndpointPolicyResult.Rejected(SyncError(SyncErrorCode.ProtocolError, "endpoint is empty"))
        }
        if (trimmed != endpoint) {
            return EndpointPolicyResult.Rejected(SyncError(SyncErrorCode.ProtocolError, "endpoint must be trimmed"))
        }
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("https://") -> EndpointPolicyResult.Accepted(trimmed)
            lower.startsWith("http://") -> EndpointPolicyResult.Rejected(
                SyncError(SyncErrorCode.ProtocolError, "cleartext HTTP endpoint is not allowed"),
            )
            "://" in trimmed -> EndpointPolicyResult.Rejected(
                SyncError(SyncErrorCode.ProtocolError, "unsupported endpoint scheme"),
            )
            else -> EndpointPolicyResult.Rejected(
                SyncError(SyncErrorCode.ProtocolError, "endpoint must use https scheme"),
            )
        }
    }
}

internal sealed interface EndpointPolicyResult {
    data class Accepted(val endpoint: String) : EndpointPolicyResult
    data class Rejected(val error: SyncError) : EndpointPolicyResult
}

internal class CertificatePinVerifier {
    fun verifyDerCertificateSha256(
        certificateDer: ByteArray,
        expectedSha256Hex: String,
    ): CertificatePinVerification {
        val normalizedExpected = expectedSha256Hex.lowercase()
        if (!normalizedExpected.isSha256Hex()) {
            return CertificatePinVerification.Mismatch(
                SyncError(SyncErrorCode.CertificatePinMismatch, "invalid certificate pin"),
            )
        }
        val actual = sha256Hex(certificateDer)
        return if (constantTimeEquals(actual, normalizedExpected)) {
            CertificatePinVerification.Match
        } else {
            CertificatePinVerification.Mismatch(
                SyncError(SyncErrorCode.CertificatePinMismatch, "certificate pin mismatch"),
            )
        }
    }
}

internal sealed interface CertificatePinVerification {
    data object Match : CertificatePinVerification
    data class Mismatch(val error: SyncError) : CertificatePinVerification
}

internal class SecureEndpointExchange(
    private val endpointPolicy: EndpointPolicy = EndpointPolicy(),
    private val certificatePinVerifier: CertificatePinVerifier = CertificatePinVerifier(),
) {
    fun <T> exchangeAfterTlsHandshake(
        endpoint: String,
        peerCertificateDer: ByteArray,
        expectedCertificateSha256Hex: String,
        exchange: (String) -> T,
    ): SecureEndpointExchangeResult<T> {
        val acceptedEndpoint = when (val endpointResult = endpointPolicy.validate(endpoint)) {
            is EndpointPolicyResult.Accepted -> endpointResult.endpoint
            is EndpointPolicyResult.Rejected -> return SecureEndpointExchangeResult.Rejected(endpointResult.error)
        }
        when (val pinResult = certificatePinVerifier.verifyDerCertificateSha256(peerCertificateDer, expectedCertificateSha256Hex)) {
            CertificatePinVerification.Match -> Unit
            is CertificatePinVerification.Mismatch -> return SecureEndpointExchangeResult.Rejected(pinResult.error)
        }
        return SecureEndpointExchangeResult.Accepted(exchange(acceptedEndpoint))
    }
}

internal sealed interface SecureEndpointExchangeResult<out T> {
    data class Accepted<T>(val value: T) : SecureEndpointExchangeResult<T>
    data class Rejected(val error: SyncError) : SecureEndpointExchangeResult<Nothing>
}

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun constantTimeEquals(left: String, right: String): Boolean {
    var diff = left.length xor right.length
    val max = maxOf(left.length, right.length)
    for (index in 0 until max) {
        val leftCode = left.getOrNull(index)?.code ?: 0
        val rightCode = right.getOrNull(index)?.code ?: 0
        diff = diff or (leftCode xor rightCode)
    }
    return diff == 0
}
