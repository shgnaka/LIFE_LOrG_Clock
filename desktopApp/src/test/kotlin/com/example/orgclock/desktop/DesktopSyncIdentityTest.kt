package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncPairingCodeCodec
import com.example.orgclock.sync.SyncPairingInvitationV2
import com.example.orgclock.sync.SyncPairingInvitationV2Codec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSyncIdentityTest {
    private val tempRoots = mutableListOf<Path>()
    private val tlsStoreRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tlsStoreRoots.asReversed().forEach { root ->
            if (!root.exists()) return@forEach
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tlsStoreRoots.clear()
        tempRoots.asReversed().forEach { root ->
            if (!root.exists()) return@forEach
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempRoots.clear()
    }

    @Test
    fun tlsIdentityStoreUsesRootDerivedPasswordAndOwnerOnlyWhenSupported() {
        val root = tempRoot()
        val storePath = DesktopTlsIdentity.storePathForRoot(root)
        tlsStoreRoots.add(storePath.parent)

        val identity = DesktopTlsIdentity.loadOrCreate(root)
        val reloaded = DesktopTlsIdentity.loadOrCreate(root)

        assertEquals(identity.certificateSha256, reloaded.certificateSha256)
        assertTrue(identity.certificateSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(Files.exists(storePath))
        assertFailsWith<Exception> {
            KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(storePath).use { load(it, LEGACY_TLS_STORE_PASSWORD) }
            }
        }
        Files.getFileAttributeView(storePath, PosixFileAttributeView::class.java)
            ?.readAttributes()
            ?.permissions()
            ?.let { permissions ->
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions,
                )
            }
    }

    @Test
    fun signingPublicKeyIsPersistedPerRoot() {
        val root = tempRoot()
        val identity = DesktopSyncIdentity()

        val first = identity.signingPublicKeyBase64(root)
        val second = identity.signingPublicKeyBase64(root)

        assertTrue(first.isNotBlank())
        assertEquals(first, second)
    }

    @Test
    fun syncCoreEnvelopeSignatureVerifiesWithPersistedPublicKey() {
        val root = tempRoot()
        val identity = DesktopSyncIdentity()
        val canonicalInput = "schemaVersion=1\nmessageId=cmd-1"

        val signatureBase64 = identity.signSyncCoreEnvelope(root, canonicalInput).getOrThrow()
        val publicKey = KeyFactory.getInstance(EC_ALGORITHM).generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(identity.signingPublicKeyBase64(root))),
        )
        val verifier = Signature.getInstance(ES256_SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(canonicalInput.toByteArray(Charsets.UTF_8))

        assertTrue(verifier.verify(es256RawToDer(Base64.getDecoder().decode(signatureBase64))))
    }

    @Test
    fun signingPrivateKeyIsEncryptedAtRestAndOwnerOnlyWhenSupported() {
        val root = tempRoot()
        val identity = DesktopSyncIdentity()

        val publicKeyBase64 = identity.signingPublicKeyBase64(root)
        val encryptedPrivatePath = root.resolve(".orgclock").resolve("sync-core-signing-es256-v1.pk8.enc")
        val legacyPrivatePath = root.resolve(".orgclock").resolve("sync-core-signing-es256-v1.pk8")

        assertTrue(publicKeyBase64.isNotBlank())
        assertFalse(Files.exists(legacyPrivatePath))
        assertTrue(Files.exists(encryptedPrivatePath))
        val lines = Files.readAllLines(encryptedPrivatePath, Charsets.UTF_8)
        assertEquals("org-clock-sync-core-signing-key-v1", lines.first())
        assertEquals(4, lines.size)

        Files.getFileAttributeView(encryptedPrivatePath, PosixFileAttributeView::class.java)
            ?.readAttributes()
            ?.permissions()
            ?.let { permissions ->
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions,
                )
            }
    }

    @Test
    fun legacyPlaintextSigningPrivateKeyIsMigratedToEncryptedStorage() {
        val root = tempRoot()
        val keyDir = root.resolve(".orgclock")
        Files.createDirectories(keyDir)
        val legacyPrivatePath = keyDir.resolve("sync-core-signing-es256-v1.pk8")
        val encryptedPrivatePath = keyDir.resolve("sync-core-signing-es256-v1.pk8.enc")
        val publicPath = keyDir.resolve("sync-core-signing-es256-v1.pub")
        val keyPair = KeyPairGenerator.getInstance(EC_ALGORITHM).apply {
            initialize(ECGenParameterSpec(P256_CURVE), SecureRandom())
        }.generateKeyPair()
        val legacyPrivateBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val publicBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        Files.writeString(legacyPrivatePath, legacyPrivateBase64, Charsets.UTF_8)
        Files.writeString(publicPath, publicBase64, Charsets.UTF_8)
        val identity = DesktopSyncIdentity()

        assertEquals(publicBase64, identity.signingPublicKeyBase64(root))

        assertFalse(Files.exists(legacyPrivatePath))
        assertTrue(Files.exists(encryptedPrivatePath))
        assertFalse(Files.readString(encryptedPrivatePath).contains(legacyPrivateBase64))
        val canonicalInput = "schemaVersion=1\nmessageId=cmd-legacy"
        val signatureBase64 = identity.signSyncCoreEnvelope(root, canonicalInput).getOrThrow()
        val verifier = Signature.getInstance(ES256_SIGNATURE_ALGORITHM)
        verifier.initVerify(keyPair.public)
        verifier.update(canonicalInput.toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(es256RawToDer(Base64.getDecoder().decode(signatureBase64))))
    }

    @Test
    fun corruptedEncryptedSigningPrivateKeyDoesNotRotateIdentitySilently() {
        val root = tempRoot()
        val identity = DesktopSyncIdentity()
        identity.signingPublicKeyBase64(root)
        val encryptedPrivatePath = root.resolve(".orgclock").resolve("sync-core-signing-es256-v1.pk8.enc")
        Files.writeString(
            encryptedPrivatePath,
            "org-clock-sync-core-signing-key-v1\nnot-base64\nnot-base64\nnot-base64",
            Charsets.UTF_8,
        )

        assertFailsWith<IllegalArgumentException> {
            identity.signingPublicKeyBase64(root)
        }
    }

    @Test
    fun pairingCodeCanCarryV2Invitation() {
        val root = tempRoot()
        val identity = DesktopSyncIdentity()
        val invitation = SyncPairingInvitationV2(
            token = "token-v2",
            hostPeerId = "desktop-peer",
            hostDeviceId = "desktop-device",
            hostDisplayName = "Desktop Host",
            hostSigningPublicKeyBase64 = identity.signingPublicKeyBase64(root),
            certificateSha256 = "ab".repeat(32),
            endpoint = "https://desktop.local:8787",
            expiresAtEpochMs = 123456L,
            capabilities = listOf("sync-core.envelope.v1"),
        )

        val decodedCode = SyncPairingCodeCodec.decode(identity.pairingCode(root, invitation)).getOrThrow()
        val decodedInvitation = SyncPairingInvitationV2Codec.decode(decodedCode.publicKeyBase64).getOrThrow()

        assertEquals("desktop-peer", decodedCode.peerId)
        assertEquals("desktop-device", decodedCode.deviceId)
        assertEquals("https://desktop.local:8787", decodedCode.endpoint)
        assertEquals(invitation, decodedInvitation)
    }

    private fun tempRoot(): Path = createTempDirectory("desktop-sync-identity-test").also(tempRoots::add)

    private companion object {
        const val EC_ALGORITHM = "EC"
        const val ES256_SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val P256_CURVE = "secp256r1"
        val LEGACY_TLS_STORE_PASSWORD = "org-clock-local-sync".toCharArray()
    }
}

private fun es256RawToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64) { "ES256 signature must be 64 bytes" }
    val r = derInteger(raw.copyOfRange(0, 32))
    val s = derInteger(raw.copyOfRange(32, 64))
    return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
}

private fun derInteger(value: ByteArray): ByteArray {
    val strippedBytes = value.dropWhile { it == 0.toByte() }.toByteArray()
    val stripped = if (strippedBytes.isEmpty()) byteArrayOf(0) else strippedBytes
    val positive = if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
    return byteArrayOf(0x02, positive.size.toByte()) + positive
}
