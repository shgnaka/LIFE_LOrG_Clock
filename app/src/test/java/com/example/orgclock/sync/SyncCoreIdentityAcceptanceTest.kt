package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyTrustMigrationTest {
    @Test
    fun transportCredentialOnly() = runTest {
        val credential = SyncTransportCredential("legacy-secret", "ab".repeat(32))
        val trustStore = MutablePeerTrustStore(
            PeerTrustRecord(
                peerId = "legacy-transport",
                deviceId = "legacy-transport",
                displayName = "Legacy Transport",
                publicKeyBase64 = SyncTransportCredentialCodec.encode(credential),
                registeredAt = TEST_INSTANT,
            ),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()

        val summary = AndroidSyncCoreIdentityMigration(trustStore, credentialStore).migrateLegacyTrustRecords()

        assertEquals(SyncCoreIdentityMigrationSummary(1, 0, 0), summary)
        assertEquals(credential, credentialStore.get("legacy-transport"))
        val migrated = trustStore.getTrustRecord("legacy-transport")
        assertEquals(LEGACY_TRANSPORT_CREDENTIAL_REDACTED, migrated?.publicKeyBase64)
        assertNull(migrated?.signingPublicKeyBase64)
        assertNull(AndroidTrustedPeerResolver(trustStore).resolveForTest("legacy-transport"))
    }

    @Test
    fun signingKeyOnly() = runTest {
        val trustStore = MutablePeerTrustStore(
            PeerTrustRecord(
                peerId = "legacy-signing",
                deviceId = "legacy-signing",
                displayName = "Legacy Signing",
                publicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                registeredAt = TEST_INSTANT,
            ),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()

        val summary = AndroidSyncCoreIdentityMigration(trustStore, credentialStore).migrateLegacyTrustRecords()

        assertEquals(SyncCoreIdentityMigrationSummary(0, 1, 0), summary)
        assertEquals(ED25519_PUBLIC_KEY_BASE64, trustStore.getTrustRecord("legacy-signing")?.publicKeyBase64)
        assertNull(credentialStore.get("legacy-signing"))
        val peer = AndroidTrustedPeerResolver(trustStore).resolveForTest("legacy-signing")
        assertEquals(ED25519_PUBLIC_KEY_BASE64, peer?.signingPublicKeyBase64)
    }

    @Test
    fun incompleteValues() = runTest {
        val invitation = SyncPairingInvitationCodec.encode(
            SyncPairingInvitation(
                token = "invite-token",
                certificateSha256 = "cd".repeat(32),
                expiresAtEpochMs = TEST_INSTANT.toEpochMilliseconds() + 60_000,
            ),
        )
        val trustStore = MutablePeerTrustStore(
            incompleteRecord("invite-prefix", invitation),
            incompleteRecord("malformed", "not-base64"),
            incompleteRecord("wrong-key", Base64.getEncoder().encodeToString("not an ed25519 key".encodeToByteArray())),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()

        val summary = AndroidSyncCoreIdentityMigration(trustStore, credentialStore).migrateLegacyTrustRecords()

        assertEquals(SyncCoreIdentityMigrationSummary(0, 0, 3), summary)
        assertNull(AndroidTrustedPeerResolver(trustStore).resolveForTest("invite-prefix"))
        assertNull(AndroidTrustedPeerResolver(trustStore).resolveForTest("malformed"))
        assertNull(AndroidTrustedPeerResolver(trustStore).resolveForTest("wrong-key"))
    }
}

class PeerKeyChangeTest {
    @Test
    fun requiresRepair() = runTest {
        val oldSigningKey = generatedEd25519PublicKey()
        val changedSigningKey = generatedEd25519PublicKey()
        val originalRecord = PeerTrustRecord(
            peerId = "desktop-peer",
            deviceId = "desktop-device",
            displayName = "Desktop",
            publicKeyBase64 = SyncTransportCredentialCodec.encode(SyncTransportCredential("old-secret", "ab".repeat(32))),
            signingPublicKeyBase64 = oldSigningKey,
            endpoint = "https://desktop.local:8787",
            registeredAt = TEST_INSTANT,
        )
        val trustStore = MutablePeerTrustStore(originalRecord)
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledIdentityFeatureFlag,
            syncCoreClient = NoOpIdentitySyncCoreClient(),
            commandExecutor = NoOpIdentityClockCommandExecutor(),
            deviceIdProvider = StaticIdentityDeviceIdProvider,
            runtimePrefs = TestIdentityRuntimePrefs(),
            peerTrustStore = trustStore,
            pairingInvitationExchange = { _, _, _, _, _ ->
                Result.success(
                    SyncPairingExchangeOutcome(
                        encodedTransportCredential = SyncTransportCredentialCodec.encode(
                            SyncTransportCredential("new-secret", "cd".repeat(32)),
                        ),
                        hostSigningPublicKeyBase64 = changedSigningKey,
                    ),
                )
            },
            localSigningPublicKeyProvider = { Result.success(generatedEd25519PublicKey()) },
            syncCoreTransportCredentialStore = InMemorySyncCoreTransportCredentialStore(),
        )

        val result = service.pairTrustedPeer(
            PeerRegistrationRequest(
                peerId = "desktop-peer",
                deviceId = "desktop-device",
                displayName = "Desktop",
                publicKeyBase64 = validInvitationV2(oldSigningKey),
                role = PeerTrustRole.Full,
                endpoint = "https://desktop.local:8787",
                requestedAt = TEST_INSTANT,
            ),
        )

        assertFalse(result.reachable)
        assertTrue(result.reason?.contains("explicit re-pair required") == true)
        assertEquals(originalRecord, trustStore.getTrustRecord("desktop-peer"))
    }
}

class PairingV2ContractTest {
    @Test
    fun roleReduction() = runTest {
        val trustStore = MutablePeerTrustStore(
            PeerTrustRecord(
                peerId = "viewer-peer",
                deviceId = "viewer-device",
                displayName = "Viewer",
                publicKeyBase64 = SyncTransportCredentialCodec.encode(SyncTransportCredential("secret-a", "ab".repeat(32))),
                signingPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                role = PeerTrustRole.Viewer,
                registeredAt = TEST_INSTANT,
            ),
        )
        val syncCoreClient = RecordingIdentitySyncCoreClient()
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledIdentityFeatureFlag,
            syncCoreClient = syncCoreClient,
            commandExecutor = NoOpIdentityClockCommandExecutor(),
            deviceIdProvider = StaticIdentityDeviceIdProvider,
            runtimePrefs = TestIdentityRuntimePrefs(),
            peerTrustStore = trustStore,
        )

        val result = service.submitOutgoingCommand(
            OutgoingClockCommand(
                commandId = "cmd-1",
                payloadJson = "{}",
                targetPeerId = "viewer-peer",
            ),
        )

        assertTrue(result is SubmitResult.Rejected)
        assertTrue((result as SubmitResult.Rejected).reason.contains("viewer-only"))
        assertEquals(0, syncCoreClient.submittedCommands)
    }
}

class PeerRevocationTest {
    @Test
    fun disablesBothCredentials() = runTest {
        val trustStore = MutablePeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Peer A",
                publicKeyBase64 = SyncTransportCredentialCodec.encode(SyncTransportCredential("secret-a", "ab".repeat(32))),
                signingPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                registeredAt = TEST_INSTANT,
            ),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()
        credentialStore.put("peer-a", SyncTransportCredential("secret-a", "ab".repeat(32)))
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledIdentityFeatureFlag,
            syncCoreClient = NoOpIdentitySyncCoreClient(),
            commandExecutor = NoOpIdentityClockCommandExecutor(),
            deviceIdProvider = StaticIdentityDeviceIdProvider,
            runtimePrefs = TestIdentityRuntimePrefs(),
            peerTrustStore = trustStore,
            syncCoreTransportCredentialStore = credentialStore,
        )

        service.revokePeer("peer-a")

        assertFalse(trustStore.isTrusted("peer-a"))
        assertNull(credentialStore.get("peer-a"))
        assertFalse(AndroidTrustedPeerResolver(trustStore).resolveForTest("peer-a")?.active ?: true)
    }
}

class CredentialStorageMigrationTest {
    @Test
    fun removesPlaintextDuplicate() {
        val credential = SyncTransportCredential("legacy-secret", "ef".repeat(32))
        val trustStore = MutablePeerTrustStore(
            PeerTrustRecord(
                peerId = "legacy-peer",
                deviceId = "legacy-peer",
                displayName = "Legacy Peer",
                publicKeyBase64 = SyncTransportCredentialCodec.encode(credential),
                registeredAt = TEST_INSTANT,
            ),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()

        AndroidSyncCoreIdentityMigration(trustStore, credentialStore).migrateLegacyTrustRecords()

        assertEquals(credential, credentialStore.get("legacy-peer"))
        val migrated = trustStore.getTrustRecord("legacy-peer")
        assertEquals(LEGACY_TRANSPORT_CREDENTIAL_REDACTED, migrated?.publicKeyBase64)
        assertTrue(SyncTransportCredentialCodec.decode(migrated!!.publicKeyBase64).isFailure)
    }
}

private class MutablePeerTrustStore(
    vararg initialRecords: PeerTrustRecord,
) : PeerTrustStore {
    private val records = initialRecords.associateBy { it.peerId }.toMutableMap()

    override fun isTrusted(peerId: String): Boolean = records[peerId.trim()]?.isActive == true
    override fun listTrusted(): List<String> = records.values.filter { it.isActive }.map { it.peerId }.sorted()
    override fun trust(peerId: String) {}
    override fun trust(peerId: String, publicKeyBase64: String) {}
    override fun trust(record: PeerTrustRecord) {
        records[record.peerId] = record
    }
    override fun getTrustRecord(peerId: String): PeerTrustRecord? = records[peerId.trim()]
    override fun listTrustRecords(): List<PeerTrustRecord> = records.values.sortedBy { it.peerId }
    override fun revoke(peerId: String) {
        records[peerId.trim()] = records.getValue(peerId.trim()).revoke(TEST_INSTANT)
    }
    override fun getTrustedPublicKey(peerId: String): String? = records[peerId.trim()]?.publicKeyBase64
}

private suspend fun AndroidTrustedPeerResolver.resolveForTest(peerId: String) =
    resolve(io.github.shgnaka.orgclock.synccore.api.PeerId(peerId))

private fun incompleteRecord(peerId: String, value: String): PeerTrustRecord =
    PeerTrustRecord(
        peerId = peerId,
        deviceId = peerId,
        displayName = peerId,
        publicKeyBase64 = value,
        registeredAt = TEST_INSTANT,
    )

private fun validInvitationV2(signingPublicKeyBase64: String): String = SyncPairingInvitationV2Codec.encode(
    SyncPairingInvitationV2(
        token = "one-time-token-v2",
        hostPeerId = "desktop-peer",
        hostDeviceId = "desktop-device",
        hostDisplayName = "Desktop",
        hostSigningPublicKeyBase64 = signingPublicKeyBase64,
        certificateSha256 = "cd".repeat(32),
        endpoint = "https://desktop.local:8787",
        expiresAtEpochMs = System.currentTimeMillis() + 60_000,
    ),
)

private fun generatedEd25519PublicKey(): String {
    val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    return Base64.getEncoder().encodeToString(keyPair.public.encoded)
}

private class NoOpIdentityClockCommandExecutor : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        return ClockResultPayload(
            commandId = "noop",
            status = ClockResultStatus.Rejected,
            errorCode = ClockErrorCode.VALIDATION_FAILED,
            errorMessage = "not used",
            appliedAt = TEST_INSTANT,
            byDeviceId = "device-a",
        )
    }
}

private class NoOpIdentitySyncCoreClient : OrgSyncCoreClient {
    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun flushNow() {}
    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult = SubmitResult.Submitted
    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = emptyList()
    override suspend fun reportResult(result: ClockResultPayload) {}
    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()
    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
    override suspend fun revokePeer(peerId: String) {}
}

private class RecordingIdentitySyncCoreClient : OrgSyncCoreClient {
    var submittedCommands = 0
        private set

    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun flushNow() {}
    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult {
        submittedCommands += 1
        return SubmitResult.Submitted
    }
    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = emptyList()
    override suspend fun reportResult(result: ClockResultPayload) {}
    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()
    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
    override suspend fun revokePeer(peerId: String) {}
}

private object AlwaysEnabledIdentityFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}

private object StaticIdentityDeviceIdProvider : DeviceIdProvider {
    override fun getOrCreate(): String = "device-a"
}

private class TestIdentityRuntimePrefs : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = SyncRuntimeMode.Standard
    override fun setSelectedMode(mode: SyncRuntimeMode) {}
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}

private val TEST_INSTANT = Instant.parse("2026-03-10T09:00:00Z")
private const val ED25519_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
