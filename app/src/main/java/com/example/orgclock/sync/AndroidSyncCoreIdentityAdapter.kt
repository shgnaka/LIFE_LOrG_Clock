package com.example.orgclock.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import java.security.KeyFactory
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidTrustedPeerResolver(
    private val peerTrustStore: PeerTrustStore,
) : TrustedPeerResolver {
    override suspend fun resolve(peerId: PeerId): TrustedPeer? {
        val record = peerTrustStore.getTrustRecord(peerId.value) ?: return null
        val signingPublicKey = record.resolveSyncCoreSigningPublicKeyBase64() ?: return null
        return TrustedPeer(
            peerId = PeerId(record.peerId),
            deviceId = record.deviceId,
            displayName = record.displayName,
            signingPublicKeyBase64 = signingPublicKey,
            role = record.role.toSyncCoreRole(),
            endpoint = record.endpoint,
            active = record.isActive,
            signingAlg = record.resolveSyncCoreSigningAlg(signingPublicKey),
        )
    }
}

interface SyncCoreTransportCredentialStore {
    fun put(peerId: String, credential: SyncTransportCredential)
    fun get(peerId: String): SyncTransportCredential?
    fun delete(peerId: String)
}

internal class AndroidKeystoreSyncCoreTransportCredentialStore(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SyncCoreTransportCredentialStore {
    override fun put(peerId: String, credential: SyncTransportCredential) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return
        val encodedCredential = SyncTransportCredentialCodec.encode(credential)
        val encrypted = encrypt(encodedCredential.encodeToByteArray())
        preferences.edit()
            .putString(credentialKey(normalizedPeerId), encrypted)
            .apply()
    }

    override fun get(peerId: String): SyncTransportCredential? {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return null
        val encrypted = preferences.getString(credentialKey(normalizedPeerId), null) ?: return null
        val plaintext = runCatching { decrypt(encrypted).decodeToString() }.getOrNull() ?: return null
        return SyncTransportCredentialCodec.decode(plaintext).getOrNull()
    }

    override fun delete(peerId: String) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return
        preferences.edit().remove(credentialKey(normalizedPeerId)).apply()
    }

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = requireNotNull(cipher.iv) { "Android Keystore did not return an AES-GCM IV." }
        require(iv.size == GCM_IV_BYTES) { "Unexpected AES-GCM IV length: ${iv.size}" }
        return listOf(iv, ciphertext).joinToString(SEALED_PART_SEPARATOR) { bytes ->
            Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun decrypt(sealedValue: String): ByteArray {
        val parts = sealedValue.split(SEALED_PART_SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid sealed credential." }
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun credentialKey(peerId: String): String = "$KEY_CREDENTIAL_PREFIX${sanitize(peerId)}"

    private fun sanitize(peerId: String): String = peerId.trim()
        .replace("%", "%25")
        .replace(":", "%3A")
        .replace("/", "%2F")
        .replace(" ", "%20")

    private companion object {
        const val PREFS_NAME = "orgclock_sync_core_secrets"
        const val DEFAULT_KEY_ALIAS = "orgclock.sync_core.transport_credentials.v1"
        const val KEY_CREDENTIAL_PREFIX = "transport_credential_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val SEALED_PART_SEPARATOR = ":"
    }
}

internal class InMemorySyncCoreTransportCredentialStore : SyncCoreTransportCredentialStore {
    private val credentials = linkedMapOf<String, SyncTransportCredential>()

    override fun put(peerId: String, credential: SyncTransportCredential) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isNotBlank()) {
            credentials[normalizedPeerId] = credential
        }
    }

    override fun get(peerId: String): SyncTransportCredential? = credentials[peerId.trim()]

    override fun delete(peerId: String) {
        credentials.remove(peerId.trim())
    }
}


internal data class SyncCoreIdentityMigrationSummary(
    val transportCredentialsMigrated: Int,
    val signingKeysDiscovered: Int,
    val incompleteRecords: Int,
)

internal class AndroidSyncCoreIdentityMigration(
    private val peerTrustStore: PeerTrustStore,
    private val credentialStore: SyncCoreTransportCredentialStore,
) {
    fun migrateLegacyTrustRecords(): SyncCoreIdentityMigrationSummary {
        var transportCredentialsMigrated = 0
        var signingKeysDiscovered = 0
        var incompleteRecords = 0
        peerTrustStore.listTrustRecords().forEach { record ->
            when (val classification = LegacyPeerTrustClassifier.classify(record.publicKeyBase64)) {
                is LegacyPeerTrustClassification.TransportCredentialOnly -> {
                    if (credentialStore.get(record.peerId) == null) {
                        credentialStore.put(record.peerId, classification.credential)
                        transportCredentialsMigrated += 1
                    }
                    peerTrustStore.trust(
                        record.copy(
                            publicKeyBase64 = LEGACY_TRANSPORT_CREDENTIAL_REDACTED,
                            transportCredentialRef = record.peerId,
                            certificateSha256 = classification.credential.certificateSha256,
                        ),
                    )
                }
                is LegacyPeerTrustClassification.SigningKeyOnly -> {
                    peerTrustStore.trust(
                        record.copy(
                            signingPublicKeyBase64 = classification.signingPublicKeyBase64,
                            signingAlg = classification.signingAlg,
                        ),
                    )
                    signingKeysDiscovered += 1
                }
                LegacyPeerTrustClassification.Incomplete -> incompleteRecords += 1
            }
        }
        return SyncCoreIdentityMigrationSummary(
            transportCredentialsMigrated = transportCredentialsMigrated,
            signingKeysDiscovered = signingKeysDiscovered,
            incompleteRecords = incompleteRecords,
        )
    }
}

internal const val LEGACY_TRANSPORT_CREDENTIAL_REDACTED = "orgclock-https-v1:migrated-to-protected-storage"
private fun PeerTrustRole.toSyncCoreRole(): PeerRole = when (this) {
    PeerTrustRole.Full -> PeerRole.Full
    PeerTrustRole.Viewer -> PeerRole.Viewer
}

internal sealed interface LegacyPeerTrustClassification {
    data class TransportCredentialOnly(val credential: SyncTransportCredential) : LegacyPeerTrustClassification
    data class SigningKeyOnly(val signingPublicKeyBase64: String, val signingAlg: String) : LegacyPeerTrustClassification
    data object Incomplete : LegacyPeerTrustClassification
}

internal object LegacyPeerTrustClassifier {
    fun classify(value: String): LegacyPeerTrustClassification {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return LegacyPeerTrustClassification.Incomplete
        SyncTransportCredentialCodec.decode(trimmed).getOrNull()?.let { credential ->
            return LegacyPeerTrustClassification.TransportCredentialOnly(credential)
        }
        if (isValidEs256X509PublicKey(trimmed)) return LegacyPeerTrustClassification.SigningKeyOnly(trimmed, DEFAULT_SYNC_SIGNING_ALG)
        if (isValidEd25519X509PublicKey(trimmed)) return LegacyPeerTrustClassification.SigningKeyOnly(trimmed, OPTIONAL_SYNC_SIGNING_ALG_ED25519)
        return LegacyPeerTrustClassification.Incomplete
    }

    fun isValidEd25519X509PublicKey(value: String): Boolean = runCatching {
        KeyFactory.getInstance(ED25519_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value.trim())))
        true
    }.getOrDefault(false)

    fun isValidEs256X509PublicKey(value: String): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value.trim()))) as ECPublicKey
        publicKey.params.curve.field.fieldSize == 256
    }.getOrDefault(false)

    private const val ED25519_ALGORITHM = "Ed25519"
    private const val EC_ALGORITHM = "EC"
}

internal fun PeerTrustRecord.resolveSyncCoreSigningPublicKeyBase64(): String? {
    val explicit = signingPublicKeyBase64?.trim()?.takeIf { it.isNotBlank() }
    if (
        explicit != null &&
        (LegacyPeerTrustClassifier.isValidEs256X509PublicKey(explicit) ||
            LegacyPeerTrustClassifier.isValidEd25519X509PublicKey(explicit))
    ) {
        return explicit
    }
    return when (val classification = LegacyPeerTrustClassifier.classify(publicKeyBase64)) {
        is LegacyPeerTrustClassification.SigningKeyOnly -> classification.signingPublicKeyBase64
        is LegacyPeerTrustClassification.TransportCredentialOnly,
        LegacyPeerTrustClassification.Incomplete -> null
    }
}

internal fun PeerTrustRecord.resolveSyncCoreSigningAlg(signingPublicKeyBase64: String): String {
    if (signingAlg != DEFAULT_SYNC_SIGNING_ALG) return signingAlg
    return if (
        LegacyPeerTrustClassifier.isValidEd25519X509PublicKey(signingPublicKeyBase64) &&
        !LegacyPeerTrustClassifier.isValidEs256X509PublicKey(signingPublicKeyBase64)
    ) {
        OPTIONAL_SYNC_SIGNING_ALG_ED25519
    } else {
        DEFAULT_SYNC_SIGNING_ALG
    }
}
