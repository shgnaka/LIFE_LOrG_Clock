package com.example.orgclock.sync

import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidSyncCoreIdentityAdapterTest {
    @Test
    fun trustedPeerResolverMapsActiveFullPeer() = runTest {
        val store = AndroidAdapterRecordingPeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Desktop A",
                publicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                role = PeerTrustRole.Full,
                endpoint = "https://peer-a.local:8787/v1/messages",
                registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            ),
        )

        val peer = AndroidTrustedPeerResolver(store).resolve(PeerId("peer-a"))

        requireNotNull(peer)
        assertEquals(PeerId("peer-a"), peer.peerId)
        assertEquals("device-a", peer.deviceId)
        assertEquals("Desktop A", peer.displayName)
        assertEquals(ED25519_PUBLIC_KEY_BASE64, peer.signingPublicKeyBase64)
        assertEquals(OPTIONAL_SYNC_SIGNING_ALG_ED25519, peer.signingAlg)
        assertEquals(PeerRole.Full, peer.role)
        assertEquals("https://peer-a.local:8787/v1/messages", peer.endpoint)
        assertEquals(true, peer.active)
    }

    @Test
    fun trustedPeerResolverPreservesViewerRoleAndRevokedState() = runTest {
        val store = AndroidAdapterRecordingPeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-viewer",
                deviceId = "device-viewer",
                displayName = "Viewer",
                publicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                role = PeerTrustRole.Viewer,
                registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                activeTrust = false,
            ),
        )

        val peer = AndroidTrustedPeerResolver(store).resolve(PeerId("peer-viewer"))

        requireNotNull(peer)
        assertEquals(PeerRole.Viewer, peer.role)
        assertEquals(false, peer.active)
        assertEquals(OPTIONAL_SYNC_SIGNING_ALG_ED25519, peer.signingAlg)
    }

    @Test
    fun trustedPeerResolverReturnsNullForUnknownPeer() = runTest {
        val peer = AndroidTrustedPeerResolver(AndroidAdapterRecordingPeerTrustStore()).resolve(PeerId("missing"))

        assertNull(peer)
    }

    @Test
    fun trustedPeerResolverReturnsNullForLegacyTransportCredentialOnlyPeer() = runTest {
        val record = PeerTrustRecord(
            peerId = "peer-transport-only",
            deviceId = "device-transport-only",
            displayName = "Transport Only",
            publicKeyBase64 = SyncTransportCredentialCodec.encode(
                SyncTransportCredential("secret-a", "ab".repeat(32)),
            ),
            registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )

        val peer = AndroidTrustedPeerResolver(AndroidAdapterRecordingPeerTrustStore(record))
            .resolve(PeerId("peer-transport-only"))

        assertNull(peer)
    }

    @Test
    fun explicitSigningKeyTakesPrecedenceOverLegacyTransportCredentialField() = runTest {
        val record = PeerTrustRecord(
            peerId = "peer-ready",
            deviceId = "device-ready",
            displayName = "Ready Peer",
            publicKeyBase64 = SyncTransportCredentialCodec.encode(
                SyncTransportCredential("secret-a", "ab".repeat(32)),
            ),
            signingPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
            registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        )

        val peer = AndroidTrustedPeerResolver(AndroidAdapterRecordingPeerTrustStore(record))
            .resolve(PeerId("peer-ready"))

        requireNotNull(peer)
        assertEquals(ED25519_PUBLIC_KEY_BASE64, peer.signingPublicKeyBase64)
        assertEquals(OPTIONAL_SYNC_SIGNING_ALG_ED25519, peer.signingAlg)
    }

    @Test
    fun legacyIdentityMigrationMovesTransportCredentialIntoCredentialStore() {
        val credential = SyncTransportCredential("secret-a", "ab".repeat(32))
        val trustStore = AndroidAdapterRecordingPeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-transport-only",
                deviceId = "device-transport-only",
                displayName = "Transport Only",
                publicKeyBase64 = SyncTransportCredentialCodec.encode(credential),
                registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            ),
            PeerTrustRecord(
                peerId = "peer-signing-only",
                deviceId = "device-signing-only",
                displayName = "Signing Only",
                publicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            ),
        )
        val credentialStore = InMemorySyncCoreTransportCredentialStore()

        val summary = AndroidSyncCoreIdentityMigration(trustStore, credentialStore).migrateLegacyTrustRecords()

        assertEquals(credential, credentialStore.get("peer-transport-only"))
        assertNull(credentialStore.get("peer-signing-only"))
        assertEquals(OPTIONAL_SYNC_SIGNING_ALG_ED25519, trustStore.getTrustRecord("peer-signing-only")?.signingAlg)
        assertEquals(SyncCoreIdentityMigrationSummary(1, 1, 0), summary)
    }
    @Test
    fun transportCredentialStoreRoundTripsAndDeletesByPeer() {
        val store = InMemorySyncCoreTransportCredentialStore()
        val credential = SyncTransportCredential(
            pairingSecret = "secret-a",
            certificateSha256 = "ab".repeat(32),
        )

        store.put(" peer-a ", credential)

        assertEquals(credential, store.get("peer-a"))
        assertNull(store.get("peer-b"))

        store.delete("peer-a")

        assertNull(store.get("peer-a"))
    }
}

private class AndroidAdapterRecordingPeerTrustStore(
    vararg records: PeerTrustRecord,
) : PeerTrustStore {
    private val recordsByPeer = records.associateBy { it.peerId }.toMutableMap()

    override fun isTrusted(peerId: String): Boolean = recordsByPeer[peerId]?.isActive == true
    override fun listTrusted(): List<String> = recordsByPeer.values.filter { it.isActive }.map { it.peerId }
    override fun trust(peerId: String) {}
    override fun trust(peerId: String, publicKeyBase64: String) {}
    override fun trust(record: PeerTrustRecord) {
        recordsByPeer[record.peerId] = record
    }
    override fun getTrustRecord(peerId: String): PeerTrustRecord? = recordsByPeer[peerId]
    override fun listTrustRecords(): List<PeerTrustRecord> = recordsByPeer.values.toList()
    override fun revoke(peerId: String) {}
    override fun repair(peerId: String) {}
    override fun getTrustedPublicKey(peerId: String): String? = getTrustRecord(peerId)?.publicKeyBase64
}


private const val ED25519_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
