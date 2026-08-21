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
        check(!walletDeletionPendingLocked()) { "Wallet deletion cleanup is pending" }
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
        !walletDeletionPendingLocked() && hasCompleteDatabaseKeyLocked()
    }

    /** Detects incomplete storage too, so reconciliation can remove orphaned material. */
    fun hasAnyDatabaseKeyMaterial(): Boolean = synchronized(STORAGE_LOCK) {
        hasAnyDatabaseKeyMaterialLocked()
    }

    fun walletDeletionPending(): Boolean = synchronized(STORAGE_LOCK) {
        walletDeletionPendingLocked()
    }

    fun loadDatabaseKey(): ByteArray? = synchronized(STORAGE_LOCK) {
        if (walletDeletionPendingLocked()) return@synchronized null
        val iv = preferences.getString(IV_KEY, null)?.let(::decode) ?: return@synchronized null
        val ciphertext = preferences.getString(CIPHERTEXT_KEY, null)?.let(::decode) ?: run {
            iv.fill(0)
            return@synchronized null
        }
        return try {
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

    /**
     * Returns the Keystore-authenticated HNS header floor for the next direct
     * wallet open. It is intentionally outside the encrypted wallet database:
     * restoring an older database backup must not restore its chain authority.
     * A pending record deliberately returns its prior committed floor: native
     * can then reopen only an equal-or-newer checkpoint and atomically heal an
     * interrupted synchronization without ever accepting an older backup.
     */
    fun directHnsRollbackFloorForOpen(): ByteArray = synchronized(STORAGE_LOCK) {
        check(!walletDeletionPendingLocked()) { "Wallet deletion cleanup is pending" }
        val record = readDirectHnsFloorRecordLocked() ?: return@synchronized ByteArray(
            DIRECT_HNS_FLOOR_BYTES,
        )
        try {
            record.floor.copyOf()
        } finally {
            record.floor.fill(0)
        }
    }

    /**
     * Writes a durable interruption marker before native code may advance an
     * encrypted header checkpoint. A process death then fails closed rather
     * than reopening an older wallet database at a lower authority floor.
     */
    fun beginDirectHnsSynchronization() = synchronized(STORAGE_LOCK) {
        check(!walletDeletionPendingLocked()) { "Wallet deletion cleanup is pending" }
        val current = readDirectHnsFloorRecordLocked()
        check(current?.state != DirectHnsFloorState.Pending) {
            "A direct HNS synchronization was interrupted; refusing rollback"
        }
        val floor = current?.floor ?: ByteArray(DIRECT_HNS_FLOOR_BYTES)
        try {
            writeDirectHnsFloorRecordLocked(DirectHnsFloorState.Pending, floor)
        } finally {
            floor.fill(0)
        }
    }

    /** Completes an interrupted-safe direct header synchronization. */
    fun commitDirectHnsSynchronization(floor: ByteArray) = synchronized(STORAGE_LOCK) {
        require(floor.size == DIRECT_HNS_FLOOR_BYTES) { "Invalid direct HNS rollback floor" }
        check(!walletDeletionPendingLocked()) { "Wallet deletion cleanup is pending" }
        val current = readDirectHnsFloorRecordLocked()
        check(current?.state == DirectHnsFloorState.Pending) {
            "Direct HNS synchronization was not prepared"
        }
        try {
            check(floorAtLeast(floor, current.floor)) {
                "Direct HNS rollback floor moved backwards"
            }
            writeDirectHnsFloorRecordLocked(DirectHnsFloorState.Committed, floor)
        } finally {
            current.floor.fill(0)
            floor.fill(0)
        }
    }

    /** Stores the native coordinator's opening floor after a successful install. */
    fun storeInitialDirectHnsRollbackFloor(floor: ByteArray) = synchronized(STORAGE_LOCK) {
        require(floor.size == DIRECT_HNS_FLOOR_BYTES) { "Invalid direct HNS rollback floor" }
        check(!walletDeletionPendingLocked()) { "Wallet deletion cleanup is pending" }
        val current = readDirectHnsFloorRecordLocked()
        try {
            if (current != null) {
                check(floorAtLeast(floor, current.floor)) {
                    "Direct HNS rollback floor moved backwards"
                }
            }
            writeDirectHnsFloorRecordLocked(DirectHnsFloorState.Committed, floor)
        } finally {
            current?.floor?.fill(0)
            floor.fill(0)
        }
    }

    /** Ordinary fail-closed cleanup for incomplete, never-confirmed storage. */
    fun deleteDatabaseKey() = synchronized(STORAGE_LOCK) {
        check(deletionLatchStateLocked() == WalletDeletionLatchState.None) {
            "Confirmed wallet deletion is pending"
        }
        deleteWrappingKeyIfPresent()
        check(
            preferences.edit()
                .remove(IV_KEY)
                .remove(CIPHERTEXT_KEY)
                .remove(DIRECT_HNS_FLOOR_IV_KEY)
                .remove(DIRECT_HNS_FLOOR_CIPHERTEXT_KEY)
                .commit(),
        ) { "Wallet database key could not be deleted durably" }
        check(!hasAnyDatabaseKeyMaterialLocked()) { "Wallet database key deletion was incomplete" }
    }

    /**
     * Durably records an already-confirmed user request before key destruction.
     * Reconciliation will not reopen a wallet while either deletion marker exists.
     */
    fun requestConfirmedWalletDeletion() = synchronized(STORAGE_LOCK) {
        when (deletionLatchStateLocked()) {
            WalletDeletionLatchState.DurableRequest -> return@synchronized
            WalletDeletionLatchState.AwaitingDurableRequest,
            WalletDeletionLatchState.None -> Unit
        }
        check(hasCompleteDatabaseKeyLocked()) { "Confirmed wallet key is unavailable" }
        PROCESS_DELETION_LATCH.begin(namespace.keyAlias)
        val stored = runCatching {
            preferences.edit().putBoolean(DELETION_REQUESTED_KEY, true).commit()
        }.getOrDefault(false)
        check(stored) { "Wallet deletion request could not be stored durably" }
        PROCESS_DELETION_LATCH.markDurable(namespace.keyAlias)
    }

    /** Deletes the wrapping key before wrapped material and advances to file cleanup. */
    fun deleteDatabaseKeyForConfirmedWalletDeletion(): WalletDatabaseKeyDeletionResult =
        synchronized(STORAGE_LOCK) {
            check(deletionLatchStateLocked() == WalletDeletionLatchState.DurableRequest) {
                "Wallet deletion was not durably requested"
            }
            deleteWrappingKeyIfPresent()
            // The wrapping key is the only capability that decrypts the database key. Once its
            // verified deletion succeeds, a SharedPreferences failure must not misreport the wallet
            // as intact or prevent encrypted-file removal. The previously durable request marker
            // keeps restart reconciliation fail closed until wrapped metadata is cleaned up too.
            val metadataStored = runCatching {
                preferences.edit()
                    .remove(IV_KEY)
                    .remove(CIPHERTEXT_KEY)
                    .remove(DIRECT_HNS_FLOOR_IV_KEY)
                    .remove(DIRECT_HNS_FLOOR_CIPHERTEXT_KEY)
                    .putBoolean(DELETION_REQUESTED_KEY, true)
                    .putBoolean(FILE_CLEANUP_PENDING_KEY, true)
                    .commit()
            }.getOrDefault(false)
            if (metadataStored) {
                WalletDatabaseKeyDeletionResult.Removed
            } else {
                WalletDatabaseKeyDeletionResult.RemovedMetadataCleanupPending
            }
        }

    /** Clears retry state only after the caller verifies every database artifact is absent. */
    fun finishConfirmedWalletDeletion() = synchronized(STORAGE_LOCK) {
        check(deletionLatchStateLocked() == WalletDeletionLatchState.DurableRequest) {
            "Wallet deletion was not durably requested"
        }
        deleteWrappingKeyIfPresent()
        // A previous wrapped-metadata commit may have failed after the wrapping key was removed.
        // Completion retries that cleanup only after the caller proves all database files absent.
        check(
            preferences.edit()
                .remove(IV_KEY)
                .remove(CIPHERTEXT_KEY)
                .remove(DIRECT_HNS_FLOOR_IV_KEY)
                .remove(DIRECT_HNS_FLOOR_CIPHERTEXT_KEY)
                .putBoolean(DELETION_REQUESTED_KEY, true)
                .putBoolean(FILE_CLEANUP_PENDING_KEY, true)
                .commit(),
        ) { "Wallet database-key metadata cleanup could not be stored durably" }
        check(!hasAnyDatabaseKeyMaterialLocked()) {
            "Wallet database key material remains during deletion completion"
        }
        val completed = runCatching {
            preferences.edit()
                .remove(DELETION_REQUESTED_KEY)
                .remove(FILE_CLEANUP_PENDING_KEY)
                .commit()
        }.getOrDefault(false)
        check(completed) { "Wallet deletion completion could not be stored durably" }
        PROCESS_DELETION_LATCH.complete(namespace.keyAlias)
    }

    private fun hasCompleteDatabaseKeyLocked(): Boolean =
        preferences.contains(IV_KEY) &&
            preferences.contains(CIPHERTEXT_KEY) &&
            keyStore.containsAlias(namespace.keyAlias)

    private fun hasAnyDatabaseKeyMaterialLocked(): Boolean =
        preferences.contains(IV_KEY) ||
            preferences.contains(CIPHERTEXT_KEY) ||
            preferences.contains(DIRECT_HNS_FLOOR_IV_KEY) ||
            preferences.contains(DIRECT_HNS_FLOOR_CIPHERTEXT_KEY) ||
            keyStore.containsAlias(namespace.keyAlias)

    private fun readDirectHnsFloorRecordLocked(): DirectHnsFloorRecord? {
        val ivText = preferences.getString(DIRECT_HNS_FLOOR_IV_KEY, null)
        val ciphertextText = preferences.getString(DIRECT_HNS_FLOOR_CIPHERTEXT_KEY, null)
        if (ivText == null && ciphertextText == null) return null
        check(ivText != null && ciphertextText != null) { "Direct HNS rollback floor is incomplete" }
        val iv = decode(ivText) ?: throw IllegalStateException("Direct HNS rollback floor IV is invalid")
        val ciphertext = decode(ciphertextText)
            ?: throw IllegalStateException("Direct HNS rollback floor ciphertext is invalid")
        return try {
            val wrappingKey = keyStore.getKey(namespace.keyAlias, null) as? SecretKey
                ?: throw IllegalStateException("Wallet wrapping key is unavailable")
            val plaintext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
                updateAAD(directHnsFloorWrappingContext)
                doFinal(ciphertext)
            }
            try {
                check(plaintext.size == DIRECT_HNS_FLOOR_RECORD_BYTES) {
                    "Direct HNS rollback floor record is invalid"
                }
                check(plaintext[0] == DIRECT_HNS_FLOOR_RECORD_VERSION) {
                    "Direct HNS rollback floor version is unsupported"
                }
                val state = DirectHnsFloorState.fromWire(plaintext[1])
                val floor = plaintext.copyOfRange(2, plaintext.size)
                DirectHnsFloorRecord(state, floor)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun writeDirectHnsFloorRecordLocked(state: DirectHnsFloorState, floor: ByteArray) {
        require(floor.size == DIRECT_HNS_FLOOR_BYTES) { "Invalid direct HNS rollback floor" }
        val wrappingKey = keyStore.getKey(namespace.keyAlias, null) as? SecretKey
            ?: throw IllegalStateException("Wallet wrapping key is unavailable")
        val plaintext = ByteArray(DIRECT_HNS_FLOOR_RECORD_BYTES)
        var iv = ByteArray(0)
        var ciphertext = ByteArray(0)
        try {
            plaintext[0] = DIRECT_HNS_FLOOR_RECORD_VERSION
            plaintext[1] = state.wire
            floor.copyInto(plaintext, destinationOffset = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            cipher.updateAAD(directHnsFloorWrappingContext)
            ciphertext = cipher.doFinal(plaintext)
            iv = cipher.iv
            check(
                preferences.edit()
                    .putString(DIRECT_HNS_FLOOR_IV_KEY, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(
                        DIRECT_HNS_FLOOR_CIPHERTEXT_KEY,
                        Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    )
                    .commit(),
            ) { "Direct HNS rollback floor could not be stored durably" }
        } finally {
            plaintext.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun floorAtLeast(candidate: ByteArray, floor: ByteArray): Boolean {
        val candidateHeight = java.nio.ByteBuffer.wrap(candidate, 0, 4).int.toLong() and 0xffff_ffffL
        val floorHeight = java.nio.ByteBuffer.wrap(floor, 0, 4).int.toLong() and 0xffff_ffffL
        if (candidateHeight < floorHeight) return false
        for (index in 4 until DIRECT_HNS_FLOOR_BYTES) {
            val candidateByte = candidate[index].toInt() and 0xff
            val floorByte = floor[index].toInt() and 0xff
            if (candidateByte != floorByte) return candidateByte > floorByte
        }
        return true
    }

    private fun walletDeletionPendingLocked(): Boolean =
        deletionLatchStateLocked() != WalletDeletionLatchState.None

    private fun deletionLatchStateLocked(): WalletDeletionLatchState =
        PROCESS_DELETION_LATCH.observe(
            namespace = namespace.keyAlias,
            persistedMarker = preferences.getBoolean(DELETION_REQUESTED_KEY, false) ||
                preferences.getBoolean(FILE_CLEANUP_PENDING_KEY, false),
        )

    private fun deleteWrappingKeyIfPresent() {
        val deletionFailure = runCatching {
            if (keyStore.containsAlias(namespace.keyAlias)) {
                keyStore.deleteEntry(namespace.keyAlias)
            }
        }.exceptionOrNull()
        val stillPresent = runCatching { keyStore.containsAlias(namespace.keyAlias) }
            .getOrElse { inspectionFailure ->
                deletionFailure?.addSuppressed(inspectionFailure)
                throw deletionFailure ?: inspectionFailure
            }
        if (stillPresent) {
            throw deletionFailure
                ?: IllegalStateException("Wallet wrapping key deletion was incomplete")
        }
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
        const val DIRECT_HNS_FLOOR_IV_KEY = "direct-hns-floor-iv"
        const val DIRECT_HNS_FLOOR_CIPHERTEXT_KEY = "direct-hns-floor-ciphertext"
        const val DIRECT_HNS_FLOOR_RECORD_VERSION: Byte = 1
        const val DIRECT_HNS_FLOOR_BYTES = 36
        const val DIRECT_HNS_FLOOR_RECORD_BYTES = DIRECT_HNS_FLOOR_BYTES + 2
        const val DELETION_REQUESTED_KEY = "confirmed-wallet-deletion-requested"
        const val FILE_CLEANUP_PENDING_KEY = "encrypted-wallet-file-cleanup-pending"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val STORAGE_LOCK = Any()
        val PROCESS_DELETION_LATCH = WalletDeletionProcessLatch()
    }

    private val directHnsFloorWrappingContext =
        "${namespace.wrappingContext}:direct-hns-header-floor-v1".toByteArray(Charsets.UTF_8)

    private data class DirectHnsFloorRecord(
        val state: DirectHnsFloorState,
        val floor: ByteArray,
    )

    private enum class DirectHnsFloorState(val wire: Byte) {
        Committed(0),
        Pending(1);

        companion object {
            fun fromWire(wire: Byte): DirectHnsFloorState = when (wire) {
                Committed.wire -> Committed
                Pending.wire -> Pending
                else -> throw IllegalStateException("Direct HNS rollback floor state is invalid")
            }
        }
    }
}
