package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncTransportCredential
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

interface DesktopSyncCoreTransportCredentialStore {
    fun put(peerId: String, credential: SyncTransportCredential)
    fun get(peerId: String): SyncTransportCredential?
    fun delete(peerId: String)
}

internal class DesktopEncryptedSyncCoreTransportCredentialStore(
    private val rootPath: Path,
    private val storePath: Path = rootPath.resolve(".orgclock").resolve(STORE_FILE),
) : DesktopSyncCoreTransportCredentialStore {
    override fun put(peerId: String, credential: SyncTransportCredential) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val properties = loadProperties()
        properties.setProperty(credentialKey(normalized), seal(SyncTransportCredentialCodec.encode(credential)))
        saveProperties(properties)
    }

    override fun get(peerId: String): SyncTransportCredential? {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return null
        val sealed = loadProperties().getProperty(credentialKey(normalized)) ?: return null
        val encoded = runCatching { open(sealed) }.getOrNull() ?: return null
        return SyncTransportCredentialCodec.decode(encoded).getOrNull()
    }

    override fun delete(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val properties = loadProperties()
        properties.remove(credentialKey(normalized))
        saveProperties(properties)
    }

    private fun loadProperties(): Properties {
        val properties = Properties()
        if (Files.exists(storePath)) {
            Files.newInputStream(storePath).use(properties::load)
        }
        return properties
    }

    private fun saveProperties(properties: Properties) {
        Files.createDirectories(storePath.parent)
        setOwnerOnlyPermissions(storePath.parent, isDirectory = true)
        Files.newOutputStream(
            storePath,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { output -> properties.store(output, "Org Clock sync-core transport credentials") }
        setOwnerOnlyPermissions(storePath, isDirectory = false)
    }

    private fun seal(plaintext: String): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, storageKey(salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return listOf(
            FORMAT_VERSION,
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(ciphertext),
        ).joinToString(PART_SEPARATOR)
    }

    private fun open(sealed: String): String {
        val parts = sealed.split(PART_SEPARATOR)
        require(parts.size == 4 && parts[0] == FORMAT_VERSION) { "unsupported credential storage format" }
        val salt = Base64.getDecoder().decode(parts[1])
        val iv = Base64.getDecoder().decode(parts[2])
        val ciphertext = Base64.getDecoder().decode(parts[3])
        val plaintext = try {
            Cipher.getInstance(CIPHER).apply {
                init(Cipher.DECRYPT_MODE, storageKey(salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("sync-core transport credential could not be decrypted", error)
        }
        return plaintext.decodeToString()
    }

    private fun storageKey(salt: ByteArray): SecretKeySpec {
        val material = listOf(
            "org-clock-sync-core-transport-credential-v1",
            stableRootKey(rootPath),
            System.getProperty("user.name").orEmpty(),
            System.getProperty("user.home").orEmpty(),
        ).joinToString("|").toCharArray()
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(material, salt, PBKDF2_ITERATIONS, KEY_BITS))
            .encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun credentialKey(peerId: String): String = "peer.${sanitize(peerId)}"

    private fun sanitize(peerId: String): String = peerId.trim()
        .replace("%", "%25")
        .replace(":", "%3A")
        .replace("/", "%2F")
        .replace(" ", "%20")

    private fun setOwnerOnlyPermissions(path: Path, isDirectory: Boolean) {
        runCatching {
            val permissions = if (isDirectory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private companion object {
        const val STORE_FILE = "sync-core-transport-credentials.properties"
        const val FORMAT_VERSION = "v1"
        const val PART_SEPARATOR = ":"
        const val CIPHER = "AES/GCM/NoPadding"
        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 120_000
        const val KEY_BITS = 256
    }
}
