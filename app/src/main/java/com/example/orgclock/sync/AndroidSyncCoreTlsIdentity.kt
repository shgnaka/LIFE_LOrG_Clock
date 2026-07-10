package com.example.orgclock.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

internal data class AndroidSyncCoreTlsIdentity(
    val sslServerSocketFactory: SSLServerSocketFactory,
    val certificateSha256: String,
)

internal class AndroidKeystoreSyncCoreTlsIdentityStore(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun loadOrCreate(): AndroidSyncCoreTlsIdentity {
        val alias = keyAlias.trim().ifBlank { DEFAULT_KEY_ALIAS }
        val entry = loadOrCreateEntry(alias)
        val certificate = entry.certificate as X509Certificate
        val keyManager = SingleAliasKeyManager(alias, entry.privateKey, certificate)
        val sslContext = SSLContext.getInstance(TLS_PROTOCOL).apply {
            init(arrayOf(keyManager), null, SecureRandom())
        }
        return AndroidSyncCoreTlsIdentity(
            sslServerSocketFactory = sslContext.serverSocketFactory,
            certificateSha256 = certificate.sha256Hex(),
        )
    }

    private fun loadOrCreateEntry(alias: String): KeyStore.PrivateKeyEntry {
        loadKeyStore().getEntry(alias, null)?.let { existing ->
            (existing as? KeyStore.PrivateKeyEntry)?.let { return it }
        }
        generateKeyPair(alias)
        val created = loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        requireNotNull(created?.privateKey) { "sync-core TLS key is unavailable after generation" }
        return created
    }

    private fun generateKeyPair(alias: String) {
        val now = nowEpochMs()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal(CERTIFICATE_SUBJECT))
                .setCertificateSerialNumber(BigInteger.valueOf(now.coerceAtLeast(1L)))
                .setCertificateNotBefore(Date(now - CERTIFICATE_BACKDATE_MS))
                .setCertificateNotAfter(Date(now + CERTIFICATE_VALIDITY_MS))
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "orgclock.sync_core.tls.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TLS_PROTOCOL = "TLS"
        const val EC_CURVE = "secp256r1"
        const val CERTIFICATE_SUBJECT = "CN=Org Clock Sync Core"
        const val CERTIFICATE_BACKDATE_MS = 24L * 60L * 60L * 1_000L
        const val CERTIFICATE_VALIDITY_MS = 10L * 365L * 24L * 60L * 60L * 1_000L
    }
}

private class SingleAliasKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = emptyArray()
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = alias
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = privateKey
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias
}

private fun X509Certificate.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(encoded)
    .joinToString("") { "%02x".format(it) }
