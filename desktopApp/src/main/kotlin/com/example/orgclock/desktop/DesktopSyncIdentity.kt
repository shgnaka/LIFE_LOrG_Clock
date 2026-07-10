package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncPairingCode
import com.example.orgclock.sync.SyncPairingCodeCodec
import com.example.orgclock.sync.SyncPairingInvitation
import com.example.orgclock.sync.SyncPairingInvitationCodec
import com.example.orgclock.sync.SyncPairingInvitationV2
import com.example.orgclock.sync.SyncPairingInvitationV2Codec
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DesktopSyncIdentity(
    private val port: Int = DEFAULT_PORT,
) {
    fun pairingCode(rootPath: Path, invitation: SyncPairingInvitation): String? {
        val endpoint = pairingEndpoint() ?: return null
        val credential = SyncPairingInvitationCodec.encode(invitation)
        return SyncPairingCodeCodec.encode(
            SyncPairingCode(
                peerId = deviceId(rootPath),
                deviceId = deviceId(rootPath),
                displayName = displayName(),
                publicKeyBase64 = credential,
                endpoint = endpoint,
            ),
        )
    }

    fun pairingCode(rootPath: Path, invitation: SyncPairingInvitationV2): String =
        SyncPairingCodeCodec.encode(
            SyncPairingCode(
                peerId = invitation.hostPeerId,
                deviceId = invitation.hostDeviceId,
                displayName = invitation.hostDisplayName,
                publicKeyBase64 = SyncPairingInvitationV2Codec.encode(invitation),
                endpoint = invitation.endpoint,
            ),
        )

    fun pairingEndpoint(): String? = findLanAddress()?.let { host -> "https://$host:$port" }

    fun displayName(): String = System.getProperty("user.name")
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it desktop" }
        ?: "Org Clock Desktop"

    fun deviceId(rootPath: Path): String = "desktop-${stableRootKey(rootPath).hashCode()}"
    fun tlsIdentity(rootPath: Path): DesktopTlsIdentity = DesktopTlsIdentity.loadOrCreate(rootPath)

    fun signingPublicKeyBase64(rootPath: Path): String = Base64.getEncoder()
        .encodeToString(loadOrCreateSigningKeyPair(rootPath).public.encoded)

    fun signSyncCoreEnvelope(rootPath: Path, canonicalInput: String): Result<String> = runCatching {
        val privateKey = loadOrCreateSigningKeyPair(rootPath).private
        val signature = Signature.getInstance(ES256_SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(canonicalInput.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(ecdsaDerToJoseRaw(signature.sign()))
    }

    private fun loadOrCreateSigningKeyPair(rootPath: Path): KeyPair {
        val keyDir = rootPath.resolve(SIGNING_KEY_DIRECTORY)
        val legacyPrivatePath = keyDir.resolve(SIGNING_PRIVATE_KEY_FILE)
        val encryptedPrivatePath = keyDir.resolve(SIGNING_ENCRYPTED_PRIVATE_KEY_FILE)
        val publicPath = keyDir.resolve(SIGNING_PUBLIC_KEY_FILE)
        if (Files.exists(encryptedPrivatePath) && Files.exists(publicPath)) {
            return loadSigningKeyPair(encryptedPrivatePath, publicPath, rootPath)
        }
        if (Files.exists(legacyPrivatePath) && Files.exists(publicPath)) {
            val keyPair = loadLegacySigningKeyPair(legacyPrivatePath, publicPath)
            Files.createDirectories(keyDir)
            writeEncryptedSigningPrivateKey(encryptedPrivatePath, keyPair.private.encoded, rootPath)
            setOwnerOnlyPermissions(encryptedPrivatePath, isDirectory = false)
            Files.deleteIfExists(legacyPrivatePath)
            return keyPair
        }

        Files.createDirectories(keyDir)
        setOwnerOnlyPermissions(keyDir, isDirectory = true)
        val keyPair = KeyPairGenerator.getInstance(EC_ALGORITHM).apply {
            initialize(ECGenParameterSpec(P256_CURVE), SecureRandom())
        }.generateKeyPair()
        writeEncryptedSigningPrivateKey(encryptedPrivatePath, keyPair.private.encoded, rootPath)
        setOwnerOnlyPermissions(encryptedPrivatePath, isDirectory = false)
        Files.writeString(
            publicPath,
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        setOwnerOnlyPermissions(publicPath, isDirectory = false)
        return keyPair
    }

    private fun loadSigningKeyPair(privatePath: Path, publicPath: Path, rootPath: Path): KeyPair {
        val factory = KeyFactory.getInstance(EC_ALGORITHM)
        val privateKey = factory.generatePrivate(
            PKCS8EncodedKeySpec(readEncryptedSigningPrivateKey(privatePath, rootPath)),
        )
        val publicKey = factory.generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(publicPath).trim())),
        )
        return KeyPair(publicKey, privateKey)
    }

    private fun loadLegacySigningKeyPair(privatePath: Path, publicPath: Path): KeyPair {
        val factory = KeyFactory.getInstance(EC_ALGORITHM)
        val privateKey = factory.generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privatePath).trim())),
        )
        val publicKey = factory.generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(publicPath).trim())),
        )
        return KeyPair(publicKey, privateKey)
    }

    private fun writeEncryptedSigningPrivateKey(path: Path, privateKeyBytes: ByteArray, rootPath: Path) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(SIGNING_KEY_CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, deriveSigningStorageKey(rootPath, salt), GCMParameterSpec(128, iv))
        }
        val ciphertext = cipher.doFinal(privateKeyBytes)
        Files.writeString(
            path,
            listOf(
                SIGNING_KEY_STORAGE_VERSION,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(ciphertext),
            ).joinToString("\n"),
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun readEncryptedSigningPrivateKey(path: Path, rootPath: Path): ByteArray {
        val lines = Files.readAllLines(path, Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size == 4 && lines[0] == SIGNING_KEY_STORAGE_VERSION) { "unsupported signing key storage format" }
        val salt = Base64.getDecoder().decode(lines[1])
        val iv = Base64.getDecoder().decode(lines[2])
        val ciphertext = Base64.getDecoder().decode(lines[3])
        return try {
            Cipher.getInstance(SIGNING_KEY_CIPHER).apply {
                init(Cipher.DECRYPT_MODE, deriveSigningStorageKey(rootPath, salt), GCMParameterSpec(128, iv))
            }.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("sync-core signing key could not be decrypted", error)
        }
    }

    private fun deriveSigningStorageKey(rootPath: Path, salt: ByteArray): SecretKeySpec {
        val material = listOf(
            "org-clock-sync-core-signing-v1",
            stableRootKey(rootPath),
            System.getProperty("user.name").orEmpty(),
            System.getProperty("user.home").orEmpty(),
        ).joinToString("|").toCharArray()
        val spec = PBEKeySpec(material, salt, 120_000, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

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

    private fun findLanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { network ->
            network.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .map { address -> network to address }
        }
        .filter { (_, address) -> !address.hostAddress.startsWith("169.254.") }
        .sortedByDescending { (network, address) ->
            var score = if (address.isSiteLocalAddress) 100 else 0
            val label = "${network.name} ${network.displayName}".lowercase(Locale.ROOT)
            if (VIRTUAL_ADAPTER_MARKERS.any(label::contains)) score -= 50
            score
        }
        .map { (_, address) -> address.hostAddress }
        .firstOrNull()

    companion object {
        const val DEFAULT_PORT = 8787
        val VIRTUAL_ADAPTER_MARKERS = listOf("vpn", "virtual", "vethernet", "hyper-v", "wsl", "docker")
        private const val SIGNING_KEY_DIRECTORY = ".orgclock"
        private const val SIGNING_PRIVATE_KEY_FILE = "sync-core-signing-es256-v1.pk8"
        private const val SIGNING_ENCRYPTED_PRIVATE_KEY_FILE = "sync-core-signing-es256-v1.pk8.enc"
        private const val SIGNING_PUBLIC_KEY_FILE = "sync-core-signing-es256-v1.pub"
        private const val EC_ALGORITHM = "EC"
        private const val ES256_SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val P256_CURVE = "secp256r1"
        private const val SIGNING_KEY_CIPHER = "AES/GCM/NoPadding"
        private const val SIGNING_KEY_STORAGE_VERSION = "org-clock-sync-core-signing-key-v1"
    }
}

internal fun ecdsaDerToJoseRaw(der: ByteArray, partSize: Int = 32): ByteArray {
    require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER signature." }
    var index = 2
    require(der[index] == 0x02.toByte()) { "Invalid ECDSA DER integer." }
    val rLength = der[index + 1].toInt() and 0xff
    val r = der.copyOfRange(index + 2, index + 2 + rLength)
    index += 2 + rLength
    require(der[index] == 0x02.toByte()) { "Invalid ECDSA DER integer." }
    val sLength = der[index + 1].toInt() and 0xff
    val s = der.copyOfRange(index + 2, index + 2 + sLength)
    return unsignedFixed(r, partSize) + unsignedFixed(s, partSize)
}

private fun unsignedFixed(value: ByteArray, size: Int): ByteArray {
    val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
    require(stripped.size <= size) { "ECDSA integer is too large." }
    return ByteArray(size - stripped.size) + stripped
}

internal fun stableRootKey(rootPath: Path): String {
    val canonical = runCatching { rootPath.toRealPath() }
        .getOrElse { rootPath.toAbsolutePath().normalize() }
        .toString()
    return if (System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("windows")) {
        canonical.lowercase(Locale.ROOT)
    } else {
        canonical
    }
}
