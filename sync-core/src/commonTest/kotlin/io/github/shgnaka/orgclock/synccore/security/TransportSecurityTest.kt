package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TransportSecurityTest {
    @Test
    fun cleartextEndpointRejectedBeforeExchangeInvocation() {
        var invoked = false

        val result = SecureEndpointExchange().exchangeAfterTlsHandshake(
            endpoint = "http://peer.example/v1/messages",
            peerCertificateDer = "certificate".encodeToByteArray(),
            expectedCertificateSha256Hex = sha256Hex("certificate".encodeToByteArray()),
        ) {
            invoked = true
            "connected"
        }

        val rejected = assertIs<SecureEndpointExchangeResult.Rejected>(result)
        assertEquals(SyncErrorCode.ProtocolError, rejected.error.code)
        assertFalse(invoked)
    }

    @Test
    fun endpointPolicyAcceptsOnlyHttpsEndpoints() {
        assertIs<EndpointPolicyResult.Accepted>(EndpointPolicy().validate("https://peer.example/v1/messages"))

        val missingScheme = assertIs<EndpointPolicyResult.Rejected>(EndpointPolicy().validate("peer.example:8787"))
        assertEquals(SyncErrorCode.ProtocolError, missingScheme.error.code)

        val unsupported = assertIs<EndpointPolicyResult.Rejected>(EndpointPolicy().validate("ws://peer.example"))
        assertEquals(SyncErrorCode.ProtocolError, unsupported.error.code)
    }

    @Test
    fun certificatePinMatchAllowsExchange() {
        var invokedEndpoint: String? = null
        val certificateDer = "loopback-cert-a".encodeToByteArray()

        val result = SecureEndpointExchange().exchangeAfterTlsHandshake(
            endpoint = "https://127.0.0.1:8787/v1/messages",
            peerCertificateDer = certificateDer,
            expectedCertificateSha256Hex = sha256Hex(certificateDer),
        ) { endpoint ->
            invokedEndpoint = endpoint
            "connected"
        }

        val accepted = assertIs<SecureEndpointExchangeResult.Accepted<String>>(result)
        assertEquals("connected", accepted.value)
        assertEquals("https://127.0.0.1:8787/v1/messages", invokedEndpoint)
    }

    @Test
    fun certificatePinMismatchIsTerminalAndSkipsExchange() {
        var invoked = false
        val certificateDer = "loopback-cert-a".encodeToByteArray()
        val differentCertificateDer = "loopback-cert-b".encodeToByteArray()

        val result = SecureEndpointExchange().exchangeAfterTlsHandshake(
            endpoint = "https://127.0.0.1:8787/v1/messages",
            peerCertificateDer = certificateDer,
            expectedCertificateSha256Hex = sha256Hex(differentCertificateDer),
        ) {
            invoked = true
            "connected"
        }

        val rejected = assertIs<SecureEndpointExchangeResult.Rejected>(result)
        assertEquals(SyncErrorCode.CertificatePinMismatch, rejected.error.code)
        assertFalse(invoked)
    }

    @Test
    fun certificatePinVerifierAcceptsUppercaseExpectedPin() {
        val certificateDer = "loopback-cert-a".encodeToByteArray()

        val result = CertificatePinVerifier().verifyDerCertificateSha256(
            certificateDer = certificateDer,
            expectedSha256Hex = sha256Hex(certificateDer).uppercase(),
        )

        assertIs<CertificatePinVerification.Match>(result)
    }

    @Test
    fun malformedExpectedPinIsMismatch() {
        val result = CertificatePinVerifier().verifyDerCertificateSha256(
            certificateDer = "loopback-cert-a".encodeToByteArray(),
            expectedSha256Hex = "not-a-sha256",
        )

        val mismatch = assertIs<CertificatePinVerification.Mismatch>(result)
        assertEquals(SyncErrorCode.CertificatePinMismatch, mismatch.error.code)
    }
}
