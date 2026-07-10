package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncIntegrationServicePairingTest {
    @Test
    fun pairTrustedPeer_persistsTrustRecordAndRefreshesSnapshot() = runTest {
        val store = RecordingPeerTrustStore()
        val credentialStore = InMemorySyncCoreTransportCredentialStore()
        val encodedCredential = SyncTransportCredentialCodec.encode(SyncTransportCredential("secret-a", "ab".repeat(32)))
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-a"
            },
            runtimePrefs = TestSyncRuntimePrefs(),
            peerTrustStore = store,
            pairingInvitationExchange = { _, _, _, _, _ -> Result.success(SyncPairingExchangeOutcome(encodedCredential)) },
            syncCoreTransportCredentialStore = credentialStore,
            securePeerProbe = { _, _ -> Result.success(Unit) },
        )

        val result = service.pairTrustedPeer(
            PeerRegistrationRequest(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Desktop Host",
                publicKeyBase64 = validInvitation(),
                role = PeerTrustRole.Viewer,
                endpoint = "https://desktop.local:8787",
                requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )

        assertTrue(result.reachable)
        assertEquals(listOf("peer-a"), store.listTrusted())
        val record = store.getTrustRecord("peer-a")
        assertEquals("Desktop Host", record?.displayName)
        assertEquals(PeerTrustRole.Viewer, record?.role)
        assertEquals(encodedCredential, record?.publicKeyBase64)
        assertEquals("ab".repeat(32), record?.certificateSha256)
        assertEquals("peer-a", record?.transportCredentialRef)
        assertEquals(SyncTransportCredential("secret-a", "ab".repeat(32)), credentialStore.get("peer-a"))
        assertEquals("peer-a", service.snapshot.value.trustedPeers.single())
        val peerState = service.snapshot.value.peerStates.single()
        assertEquals("Desktop Host", peerState.displayName)
        assertEquals(PeerTrustRole.Viewer, peerState.role)
        assertTrue(peerState.publicKeyRegistered)
        assertTrue(peerState.reachable == true)
    }

    @Test
    fun pairTrustedPeer_stillPersistsTrustRecordWhenPeerIsOffline() = runTest {
        val store = RecordingPeerTrustStore()
        val credentialStore = InMemorySyncCoreTransportCredentialStore()
        val encodedCredential = SyncTransportCredentialCodec.encode(SyncTransportCredential("secret-a", "ab".repeat(32)))
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-a"
            },
            runtimePrefs = TestSyncRuntimePrefs(),
            peerTrustStore = store,
            pairingInvitationExchange = { _, _, _, _, _ -> Result.success(SyncPairingExchangeOutcome(encodedCredential)) },
            syncCoreTransportCredentialStore = credentialStore,
            securePeerProbe = { _, _ -> Result.failure(IllegalStateException("unreachable")) },
        )

        val result = service.pairTrustedPeer(
            PeerRegistrationRequest(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Desktop Host",
                publicKeyBase64 = validInvitation(),
                role = PeerTrustRole.Viewer,
                endpoint = "https://desktop.local:8787",
                requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )

        assertTrue(!result.reachable)
        assertEquals(listOf("peer-a"), store.listTrusted())
        assertEquals(PeerTrustRole.Viewer, store.getTrustRecord("peer-a")?.role)
        assertEquals(SyncTransportCredential("secret-a", "ab".repeat(32)), credentialStore.get("peer-a"))
    }
    @Test
    fun pairTrustedPeerV2StoresHostSigningKeyAndTransportCredential() = runTest {
        val store = RecordingPeerTrustStore()
        val credentialStore = InMemorySyncCoreTransportCredentialStore()
        val encodedCredential = SyncTransportCredentialCodec.encode(SyncTransportCredential("secret-v2", "cd".repeat(32)))
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "android-peer"
            },
            runtimePrefs = TestSyncRuntimePrefs(),
            peerTrustStore = store,
            pairingInvitationExchange = { _, _, localDeviceId, localSigningKey, requestedRole ->
                assertEquals("android-peer", localDeviceId)
                assertEquals(ED25519_PUBLIC_KEY_BASE64, localSigningKey)
                assertEquals(PeerTrustRole.Viewer, requestedRole)
                Result.success(
                    SyncPairingExchangeOutcome(
                        encodedTransportCredential = encodedCredential,
                        hostPeerId = "desktop-peer",
                        hostDeviceId = "desktop-device",
                        hostDisplayName = "Desktop Host V2",
                        hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                        grantedRole = PeerTrustRole.Viewer,
                        certificateSha256 = "cd".repeat(32),
                    ),
                )
            },
            localSigningPublicKeyProvider = { Result.success(ED25519_PUBLIC_KEY_BASE64) },
            syncCoreTransportCredentialStore = credentialStore,
            securePeerProbe = { _, _ -> Result.success(Unit) },
        )

        val result = service.pairTrustedPeer(
            PeerRegistrationRequest(
                peerId = "desktop-peer",
                deviceId = "desktop-device",
                displayName = "Desktop Host V2",
                publicKeyBase64 = validInvitationV2(),
                role = PeerTrustRole.Viewer,
                endpoint = "https://desktop.local:8787",
                requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )

        assertTrue(result.reachable)
        val record = store.getTrustRecord("desktop-peer")
        assertEquals("desktop-device", record?.deviceId)
        assertEquals("Desktop Host V2", record?.displayName)
        assertEquals(encodedCredential, record?.publicKeyBase64)
        assertEquals(ED25519_PUBLIC_KEY_BASE64, record?.signingPublicKeyBase64)
        assertEquals("cd".repeat(32), record?.certificateSha256)
        assertEquals("desktop-peer", record?.transportCredentialRef)
        assertEquals(PeerTrustRole.Viewer, record?.role)
        assertEquals(SyncTransportCredential("secret-v2", "cd".repeat(32)), credentialStore.get("desktop-peer"))
    }

    @Test
    fun revokePeerDisablesCredentialAndDelegatesToSyncCoreClient() = runTest {
        val store = RecordingPeerTrustStore()
        store.trust(
            PeerTrustRecord(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Peer A",
                publicKeyBase64 = validInvitation(),
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()
        credentialStore.put("peer-a", SyncTransportCredential("secret-a", "ab".repeat(32)))
        val syncCoreClient = RecordingOrgSyncCoreClient()
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = syncCoreClient,
            commandExecutor = NoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-a"
            },
            runtimePrefs = TestSyncRuntimePrefs(),
            peerTrustStore = store,
            syncCoreTransportCredentialStore = credentialStore,
        )

        service.revokePeer("peer-a")

        assertEquals(emptyList(), store.listTrusted())
        assertNull(credentialStore.get("peer-a"))
        assertEquals(listOf("peer-a"), syncCoreClient.revokedPeers)
    }
}

private fun validInvitation(): String = SyncPairingInvitationCodec.encode(
    SyncPairingInvitation(
        token = "one-time-token",
        certificateSha256 = "ab".repeat(32),
        expiresAtEpochMs = System.currentTimeMillis() + 60_000,
    ),
)

private fun validInvitationV2(): String = SyncPairingInvitationV2Codec.encode(
    SyncPairingInvitationV2(
        token = "one-time-token-v2",
        hostPeerId = "desktop-peer",
        hostDeviceId = "desktop-device",
        hostDisplayName = "Desktop Host V2",
        hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
        certificateSha256 = "cd".repeat(32),
        endpoint = "https://desktop.local:8787",
        expiresAtEpochMs = System.currentTimeMillis() + 60_000,
    ),
)
private class RecordingPeerTrustStore : PeerTrustStore {
    private val records = linkedMapOf<String, PeerTrustRecord>()
    private val legacyTrusted = linkedSetOf<String>()

    override fun isTrusted(peerId: String): Boolean {
        return legacyTrusted.contains(peerId) || records[peerId]?.isActive == true
    }

    override fun listTrusted(): List<String> {
        return (legacyTrusted + records.values.filter { it.isActive }.map { it.peerId }).sorted()
    }

    override fun trust(peerId: String) {
        legacyTrusted.add(peerId)
    }

    override fun trust(peerId: String, publicKeyBase64: String) {
        trust(
            PeerTrustRecord(
                peerId = peerId,
                deviceId = peerId,
                displayName = peerId,
                publicKeyBase64 = publicKeyBase64,
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
    }

    override fun trust(record: PeerTrustRecord) {
        records[record.peerId] = record
    }

    override fun getTrustRecord(peerId: String): PeerTrustRecord? = records[peerId]

    override fun listTrustRecords(): List<PeerTrustRecord> = records.values.toList()

    override fun revoke(peerId: String) {
        legacyTrusted.remove(peerId)
        records.remove(peerId)
    }

    override fun getTrustedPublicKey(peerId: String): String? = records[peerId]?.publicKeyBase64
}

private class NoOpClockCommandExecutor : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        return ClockResultPayload(
            commandId = "noop",
            status = ClockResultStatus.Rejected,
            errorCode = ClockErrorCode.VALIDATION_FAILED,
            errorMessage = "not used",
            appliedAt = Instant.parse("2026-03-10T09:00:00Z"),
            byDeviceId = "device-a",
        )
    }
}

private class RecordingOrgSyncCoreClient : OrgSyncCoreClient {
    val revokedPeers = mutableListOf<String>()

    override suspend fun start() {}

    override suspend fun stop() {}

    override suspend fun flushNow() {}

    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult = SubmitResult.Submitted

    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = emptyList()

    override suspend fun reportResult(result: ClockResultPayload) {}

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()

    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()

    override suspend fun revokePeer(peerId: String) {
        revokedPeers += peerId
    }
}

private object AlwaysEnabledSyncIntegrationFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}

private class TestSyncRuntimePrefs : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = SyncRuntimeMode.Standard
    override fun setSelectedMode(mode: SyncRuntimeMode) {}
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}

private const val ED25519_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
