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
internal class AndroidWalletKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun storeDatabaseKey(value: ByteArray) {
        require(value.size == 32) { "Wallet database key must be 32 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateWrappingKey())
        cipher.updateAAD(WRAPPING_CONTEXT)
        val ciphertext = cipher.doFinal(value)
        val stored = preferences.edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        ciphertext.fill(0)
        check(stored) { "Wallet database key could not be stored durably" }
    }

    fun loadDatabaseKey(): ByteArray? {
        val iv = preferences.getString(IV_KEY, null)?.let(::decode) ?: return null
        val ciphertext = preferences.getString(CIPHERTEXT_KEY, null)?.let(::decode) ?: return null
        return try {
            val wrappingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
                updateAAD(WRAPPING_CONTEXT)
                doFinal(ciphertext).takeIf { it.size == 32 }
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    fun deleteDatabaseKey() {
        check(preferences.edit().remove(IV_KEY).remove(CIPHERTEXT_KEY).commit()) {
            "Wallet database key could not be deleted durably"
        }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun loadOrCreateWrappingKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
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
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val PREFERENCES = "wallet-keystore-v1"
        const val KEY_ALIAS = "hns-wallet-database-wrapping-v1"
        const val IV_KEY = "database-key-iv"
        const val CIPHERTEXT_KEY = "database-key-ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val WRAPPING_CONTEXT = "hns-wallet-database-key-v1".toByteArray(Charsets.UTF_8)
    }
}
