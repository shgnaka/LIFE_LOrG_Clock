package com.example.orgclock.sync

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeerTrustModelsTest {
    @Test
    fun registrationToRecordCarriesIdentityAndRole() {
        val request = PeerRegistrationRequest(
            peerId = "peer-a",
            deviceId = "device-a",
            displayName = "Desktop Host",
            publicKeyBase64 = "pk-a",
            role = PeerTrustRole.Viewer,
            endpoint = "host:1234",
            requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
        )

        val record = request.toPeerTrustRecord()

        assertEquals("peer-a", record.peerId)
        assertEquals("device-a", record.deviceId)
        assertEquals("Desktop Host", record.displayName)
        assertEquals("pk-a", record.publicKeyBase64)
        assertEquals(PeerTrustRole.Viewer, record.role)
        assertEquals("host:1234", record.endpoint)
        assertEquals(request.requestedAt, record.registeredAt)
        assertEquals(request.requestedAt, record.lastSeenAt)
        assertTrue(record.isActive)
        assertTrue(record.activeTrust)
    }

    @Test
    fun pairingDraftMapsToRegistrationRequestWithViewerRole() {
        val requestedAt = Instant.parse("2026-03-10T09:00:00Z")

        val request = PeerPairingDraft(
            peerId = "peer-a",
            displayName = "Viewer Monitor",
            publicKeyBase64 = "pk-a",
            endpoint = "  host:39091  ",
            viewerModeEnabled = true,
        ).toRegistrationRequest(requestedAt)

        assertEquals("peer-a", request.peerId)
        assertEquals("peer-a", request.deviceId)
        assertEquals("Viewer Monitor", request.displayName)
        assertEquals("pk-a", request.publicKeyBase64)
        assertEquals(PeerTrustRole.Viewer, request.role)
        assertEquals("host:39091", request.endpoint)
        assertEquals(requestedAt, request.requestedAt)
    }

    @Test
    fun recordRevokeAndRepairUpdateLifecycleState() {
        val record = PeerTrustRecord(
            peerId = "peer-a",
            deviceId = "device-a",
            displayName = "Android",
            publicKeyBase64 = "pk-a",
            registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
        )
        val revoked = record.revoke(Instant.parse("2026-03-11T09:00:00Z"))
        val repaired = revoked.repair(Instant.parse("2026-03-12T09:00:00Z"))

        assertTrue(revoked.isRevoked)
        assertFalse(revoked.isActive)
        assertEquals(Instant.parse("2026-03-11T09:00:00Z"), revoked.revokedAt)
        assertFalse(repaired.isRevoked)
        assertTrue(repaired.isActive)
        assertEquals(Instant.parse("2026-03-12T09:00:00Z"), repaired.lastSeenAt)
        assertEquals(revoked.revokedAt, repaired.revokedAt)
        assertTrue(repaired.activeTrust)
    }

    @Test
    fun blankIdentityFieldsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PeerRegistrationRequest(
                peerId = "",
                deviceId = "device-a",
                displayName = "Host",
                publicKeyBase64 = "pk-a",
                requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
            )
        }
    }
}
