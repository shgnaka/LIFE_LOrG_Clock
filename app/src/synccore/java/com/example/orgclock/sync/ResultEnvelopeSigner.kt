package com.example.orgclock.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

interface ResultEnvelopeSigner {
    fun signCanonical(canonicalPayload: String): Result<String>
}

class AndroidKeystoreResultEnvelopeSigner(
    private val keyAliasProvider: () -> String,
) : ResultEnvelopeSigner {
    override fun signCanonical(canonicalPayload: String): Result<String> = runCatching {
        val alias = keyAliasProvider().trim().ifBlank { SyncRuntimePrefs.DEFAULT_RESULT_SIGNING_KEY_ALIAS }
        val privateKey = loadOrCreateSigningKey(alias)
        val signer = Signature.getInstance(ED25519_SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(canonicalPayload.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun loadOrCreateSigningKey(alias: String): java.security.PrivateKey {
        val keyStore = loadKeyStore()
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing.privateKey

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_ED25519, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).build()
        generator.initialize(spec)
        generator.generateKeyPair()

        val created = loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        requireNotNull(created?.privateKey) { "result signing key not available after generation" }
        return created.privateKey
    }

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ED25519_SIGNATURE_ALGORITHM = "Ed25519"
    }
}
