package com.denuoweb.hnsdane.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps only a wallet database key. Recovery phrases and chain private keys never enter WebView. */
internal class AndroidWalletKeyStore(context: Context, networkId: String) {
    private val namespace = walletStorageNamespace(networkId)
    private val preferences = context.getSharedPreferences(
        namespace.preferencesName,
        Context.MODE_PRIVATE,
    )
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val wrappingContext = namespace.wrappingContext.toByteArray(Charsets.UTF_8)

    /** Stores the first database key only; replacing an existing wallet key is never implicit. */
    fun storeDatabaseKey(value: ByteArray) = synchronized(STORAGE_LOCK) {
        require(value.size == 32) { "Wallet database key must be 32 bytes" }
        require(value.any { it != 0.toByte() }) { "Wallet database key must be nonzero" }
        check(!preferences.contains(IV_KEY) && !preferences.contains(CIPHERTEXT_KEY)) {
            "Wallet database key is already stored"
        }
        check(!keyStore.containsAlias(namespace.keyAlias)) {
            "Wallet wrapping key is already stored"
        }

        var createdWrappingKey = false
        var iv = ByteArray(0)
        var ciphertext = ByteArray(0)
        try {
            val wrappingKey = createWrappingKey()
            createdWrappingKey = true
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            cipher.updateAAD(wrappingContext)
            ciphertext = cipher.doFinal(value)
            iv = cipher.iv
            val stored = preferences.edit()
                .putString(IV_KEY, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
            check(stored) { "Wallet database key could not be stored durably" }
        } catch (error: Throwable) {
            preferences.edit().remove(IV_KEY).remove(CIPHERTEXT_KEY).commit()
            if (createdWrappingKey && keyStore.containsAlias(namespace.keyAlias)) {
                keyStore.deleteEntry(namespace.keyAlias)
            }
            throw error
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    fun hasDatabaseKey(): Boolean = synchronized(STORAGE_LOCK) {
        preferences.contains(IV_KEY) &&
            preferences.contains(CIPHERTEXT_KEY) &&
            keyStore.containsAlias(namespace.keyAlias)
    }

    /** Detects incomplete storage too, so reconciliation can remove orphaned material. */
    fun hasAnyDatabaseKeyMaterial(): Boolean = synchronized(STORAGE_LOCK) {
        preferences.contains(IV_KEY) ||
            preferences.contains(CIPHERTEXT_KEY) ||
            keyStore.containsAlias(namespace.keyAlias)
    }

    fun loadDatabaseKey(): ByteArray? = synchronized(STORAGE_LOCK) {
        val iv = preferences.getString(IV_KEY, null)?.let(::decode) ?: return@synchronized null
        val ciphertext = preferences.getString(CIPHERTEXT_KEY, null)?.let(::decode) ?: run {
            iv.fill(0)
            return@synchronized null
        }
        try {
            val wrappingKey = keyStore.getKey(namespace.keyAlias, null) as? SecretKey
                ?: return@synchronized null
            val plaintext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
                updateAAD(wrappingContext)
                doFinal(ciphertext)
            }
            if (plaintext.size != 32 || plaintext.all { it == 0.toByte() }) {
                plaintext.fill(0)
                null
            } else {
                plaintext
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    fun <T> withDatabaseKey(block: (ByteArray) -> T): T? {
        val value = loadDatabaseKey() ?: return null
        return try {
            block(value)
        } finally {
            value.fill(0)
        }
    }

    fun deleteDatabaseKey() = synchronized(STORAGE_LOCK) {
        var failure: Throwable? = null
        if (!preferences.edit().remove(IV_KEY).remove(CIPHERTEXT_KEY).commit()) {
            failure = IllegalStateException("Wallet database key could not be deleted durably")
        }
        try {
            if (keyStore.containsAlias(namespace.keyAlias)) {
                keyStore.deleteEntry(namespace.keyAlias)
            }
        } catch (error: Throwable) {
            if (failure == null) failure = error
        }
        failure?.let { throw it }
    }

    private fun createWrappingKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    namespace.keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKey()
        }

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()

    private companion object {
        const val IV_KEY = "database-key-iv"
        const val CIPHERTEXT_KEY = "database-key-ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val STORAGE_LOCK = Any()
    }
}
