package com.example.orgclock.desktop

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class DesktopTlsIdentity private constructor(
    val sslContext: SSLContext,
    val certificateSha256: String,
) {
    companion object {
        private const val KEY_ALIAS = "org-clock-lan-sync"
        private const val STORE_FILE = "lan-sync.p12"
        private val password = "org-clock-local-sync".toCharArray()

        fun loadOrCreate(rootPath: Path): DesktopTlsIdentity {
            ensureProvider()
            val rootKey = MessageDigest.getInstance("SHA-256")
                .digest(stableRootKey(rootPath).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(24)
            val directory = Path.of(System.getProperty("user.home"), ".orgclock", "tls", rootKey)
            Files.createDirectories(directory)
            setOwnerOnlyPermissions(directory, isDirectory = true)
            val storePath = directory.resolve(STORE_FILE)
            val keyStore = KeyStore.getInstance("PKCS12")
            if (Files.exists(storePath)) {
                Files.newInputStream(storePath).use { keyStore.load(it, password) }
            } else {
                keyStore.load(null, password)
                val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
                val now = Instant.now()
                val subject = X500Name("CN=Org Clock Desktop LAN Sync")
                val certificateBuilder = JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger(160, SecureRandom()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    subject,
                    keyPair.public,
                )
                val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
                val certificate = JcaX509CertificateConverter().setProvider("BC")
                    .getCertificate(certificateBuilder.build(signer))
                certificate.verify(keyPair.public)
                keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, password, arrayOf(certificate))
                Files.newOutputStream(storePath).use { keyStore.store(it, password) }
                setOwnerOnlyPermissions(storePath, isDirectory = false)
            }
            val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers.keyManagers, null, SecureRandom())
            }
            return DesktopTlsIdentity(sslContext, certificate.sha256Hex())
        }

        private fun ensureProvider() {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
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
    }
}

internal fun X509Certificate.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(encoded)
    .joinToString("") { "%02x".format(it) }
