package com.denuoweb.hnsdane.wallet

import android.content.Context
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies a Keystore-wrapped credential for the app-owned loopback HNS node.
 *
 * Initial device qualification can place one strict bootstrap record in the
 * app-private no-backup wallet directory (for example through `adb run-as`).
 * The source opens that record with `O_NOFOLLOW`, authenticates its inode,
 * owner, mode, link count, network, and grammar, unlinks the same inode, then
 * wraps the authorization and exact public Shakescape acceptance policy with a
 * distinct Android Keystore key. Plaintext is never accepted from preferences,
 * an Intent, a URI, or a WebView.
 */
internal class AndroidWalletNodeCredentialSource(
    context: Context,
    private val networkId: String,
) : WalletReadBootstrapSource {
    private val applicationContext = context.applicationContext
    private val namespace = walletStorageNamespace(networkId)
    private val preferences = applicationContext.getSharedPreferences(
        "wallet-node-rpc-v2-$networkId",
        Context.MODE_PRIVATE,
    )
    private val keyAlias = "hns-wallet-node-rpc-wrapping-v2-$networkId"
    private val wrappingContext = "hns-wallet-node-rpc-credential-v2:$networkId"
        .toByteArray(Charsets.UTF_8)
    private val bootstrapFile = File(
        File(applicationContext.noBackupFilesDir, namespace.directoryName),
        BOOTSTRAP_FILE_NAME,
    ).canonicalFile

    override fun take(authority: WalletReadBootstrapAuthority): NativeHnsReadConfiguration? =
        synchronized(STORAGE_LOCK) {
            if (authority.networkId != networkId || !authority.hasCurrentStorageLease()) {
                return@synchronized null
            }
            if (!hasCompleteCredentialLocked()) {
                if (hasAnyCredentialMaterialLocked()) return@synchronized null
                val provisioned = consumeBootstrapFile() ?: return@synchronized null
                provisioned.use {
                    if (!storeCredentialLocked(provisioned)) return@synchronized null
                }
            }
            val credential = loadCredentialLocked() ?: return@synchronized null
            credential.use {
                NativeHnsReadConfiguration.takeOwnership(
                    authority = authority,
                    loopbackPort = credential.loopbackPort,
                    authorization = credential.takeAuthorization(),
                    shakescapePolicyJson = credential.takeShakescapePolicyJson(),
                )
            }
        }

    private fun hasCompleteCredentialLocked(): Boolean {
        val keyStore = androidKeyStore()
        return preferences.contains(PORT_KEY) &&
            preferences.contains(IV_KEY) &&
            preferences.contains(CIPHERTEXT_KEY) &&
            keyStore.containsAlias(keyAlias)
    }

    private fun hasAnyCredentialMaterialLocked(): Boolean {
        val keyStore = androidKeyStore()
        return preferences.contains(PORT_KEY) ||
            preferences.contains(IV_KEY) ||
            preferences.contains(CIPHERTEXT_KEY) ||
            keyStore.containsAlias(keyAlias)
    }

    private fun storeCredentialLocked(credential: ProvisionedNodeCredential): Boolean {
        if (hasAnyCredentialMaterialLocked()) return false
        var plaintext = ByteArray(0)
        var iv = ByteArray(0)
        var ciphertext = ByteArray(0)
        var keyCreated = false
        return try {
            plaintext = credential.encryptedPayload()
            val wrappingKey = createWrappingKey()
            keyCreated = true
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey)
                updateAAD(wrappingContext)
            }
            ciphertext = cipher.doFinal(plaintext)
            iv = cipher.iv
            preferences.edit()
                .putInt(PORT_KEY, credential.loopbackPort)
                .putString(IV_KEY, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        } catch (_: Throwable) {
            false
        }.also { stored ->
            if (!stored) {
                preferences.edit()
                    .remove(PORT_KEY)
                    .remove(IV_KEY)
                    .remove(CIPHERTEXT_KEY)
                    .commit()
                if (keyCreated) {
                    runCatching { androidKeyStore().deleteEntry(keyAlias) }
                }
            }
        }.also {
            plaintext.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun loadCredentialLocked(): ProvisionedNodeCredential? {
        if (!hasCompleteCredentialLocked()) return null
        val port = preferences.getInt(PORT_KEY, 0)
        if (port !in 1..USHORT_MAX) return null
        var iv = preferences.getString(IV_KEY, null)?.decodeBase64() ?: return null
        var ciphertext = preferences.getString(CIPHERTEXT_KEY, null)?.decodeBase64() ?: run {
            iv.fill(0)
            return null
        }
        var plaintext = ByteArray(0)
        return try {
            val key = androidKeyStore().getKey(keyAlias, null) as? SecretKey ?: return null
            plaintext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(wrappingContext)
                doFinal(ciphertext)
            }
            ProvisionedNodeCredential.fromEncryptedPayload(port, plaintext)
        } catch (_: Throwable) {
            null
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
            plaintext.fill(0)
        }
    }

    private fun consumeBootstrapFile(): ProvisionedNodeCredential? {
        val expectedParent = runCatching { bootstrapFile.parentFile?.canonicalFile }.getOrNull()
            ?: return null
        val expectedPath = File(expectedParent, BOOTSTRAP_FILE_NAME).path
        if (bootstrapFile.path != expectedPath) return null
        val descriptor = try {
            Os.open(
                expectedPath,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
                0,
            )
        } catch (_: Throwable) {
            return null
        }
        var contents = ByteArray(0)
        return try {
            val before = Os.fstat(descriptor)
            if (!acceptableBootstrapStat(before)) return null
            val length = before.st_size.toInt()
            contents = ByteArray(length)
            var offset = 0
            while (offset < contents.size) {
                val read = try {
                    Os.read(descriptor, contents, offset, contents.size - offset)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) continue else throw error
                }
                if (read <= 0) return null
                offset += read
            }
            val trailingByte = ByteArray(1)
            val trailing = try {
                Os.read(descriptor, trailingByte, 0, 1)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN) 0 else throw error
            } finally {
                trailingByte.fill(0)
            }
            if (trailing != 0) return null
            val after = Os.fstat(descriptor)
            if (!sameBootstrapInode(before, after) || !acceptableBootstrapStat(after)) return null
            val pathStat = Os.lstat(expectedPath)
            if (!sameBootstrapInode(after, pathStat) || !acceptableBootstrapStat(pathStat)) {
                return null
            }
            val parsed = parseWalletNodeBootstrap(contents, networkId) ?: return null
            try {
                Os.remove(expectedPath)
            } catch (_: Throwable) {
                parsed.close()
                return null
            }
            val unlinked = Os.fstat(descriptor)
            if (!sameBootstrapInode(after, unlinked) || unlinked.st_nlink != 0L) {
                parsed.close()
                return null
            }
            parsed
        } catch (_: Throwable) {
            null
        } finally {
            contents.fill(0)
            runCatching { Os.close(descriptor) }
        }
    }

    private fun acceptableBootstrapStat(stat: android.system.StructStat): Boolean =
        (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG &&
            stat.st_uid == Process.myUid() &&
            stat.st_nlink == 1L &&
            stat.st_size in 1..MAX_BOOTSTRAP_BYTES.toLong() &&
            stat.st_mode and PERMISSION_BITS == OWNER_READ_WRITE

    private fun sameBootstrapInode(
        left: android.system.StructStat,
        right: android.system.StructStat,
    ): Boolean = left.st_dev == right.st_dev && left.st_ino == right.st_ino

    private fun createWrappingKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
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

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun String.decodeBase64(): ByteArray? =
        runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()

    private companion object {
        const val BOOTSTRAP_FILE_NAME = "hns-node-rpc.bootstrap"
        const val PORT_KEY = "loopback-port"
        const val IV_KEY = "credential-iv"
        const val CIPHERTEXT_KEY = "credential-ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_BOOTSTRAP_BYTES = 32 * 1024
        const val USHORT_MAX = 65_535
        const val PERMISSION_BITS = 0x1ff
        const val OWNER_READ_WRITE = 0x180
        val STORAGE_LOCK = Any()
    }
}

/** Mutable plaintext credential whose authorization and policy are wiped on every path. */
internal class ProvisionedNodeCredential private constructor(
    val loopbackPort: Int,
    private var authorization: CharArray?,
    private var shakescapePolicyJson: ByteArray?,
) : AutoCloseable {
    fun takeAuthorization(): CharArray = authorization?.also { authorization = null }
        ?: CharArray(0)

    fun takeShakescapePolicyJson(): ByteArray = shakescapePolicyJson?.also { shakescapePolicyJson = null }
        ?: ByteArray(0)

    fun encryptedPayload(): ByteArray {
        val authorization = authorization ?: return ByteArray(0)
        val policy = shakescapePolicyJson ?: return ByteArray(0)
        if (
            authorization.isEmpty() || authorization.size > MAX_AUTHORIZATION_BYTES ||
            !validShakescapePolicy(policy)
        ) return ByteArray(0)
        return ByteBuffer.allocate(PAYLOAD_HEADER_BYTES + authorization.size + policy.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(PAYLOAD_MAGIC)
                put(PAYLOAD_VERSION)
                putShort(authorization.size.toShort())
                putInt(policy.size)
                authorization.forEach { put(it.code.toByte()) }
                put(policy)
            }
            .array()
    }

    override fun close() {
        authorization?.fill('\u0000')
        authorization = null
        shakescapePolicyJson?.fill(0)
        shakescapePolicyJson = null
    }

    override fun toString(): String =
        "ProvisionedNodeCredential(loopbackPort=<redacted>, authorization=<redacted>, " +
            "shakescapePolicy=<redacted>)"

    companion object {
        fun takeAscii(
            port: Int,
            ascii: ByteArray,
            shakescapePolicyJson: ByteArray,
        ): ProvisionedNodeCredential? {
            var characters: CharArray? = null
            var policy: ByteArray? = null
            return try {
                if (
                    port !in 1..65_535 ||
                    ascii.isEmpty() || ascii.size > MAX_AUTHORIZATION_BYTES ||
                    ascii.first() == ' '.code.toByte() ||
                    ascii.last() == ' '.code.toByte() ||
                    ascii.any { it.toInt() and 0xff !in 0x20..0x7e } ||
                    !validShakescapePolicy(shakescapePolicyJson)
                ) {
                    null
                } else {
                    val owned = CharArray(ascii.size) { index ->
                        (ascii[index].toInt() and 0xff).toChar()
                    }
                    characters = owned
                    val ownedPolicy = shakescapePolicyJson.copyOf()
                    policy = ownedPolicy
                    ProvisionedNodeCredential(port, owned, ownedPolicy).also {
                        characters = null
                        policy = null
                    }
                }
            } finally {
                ascii.fill(0)
                shakescapePolicyJson.fill(0)
                characters?.fill('\u0000')
                policy?.fill(0)
            }
        }

        fun fromEncryptedPayload(port: Int, payload: ByteArray): ProvisionedNodeCredential? {
            if (payload.size < PAYLOAD_HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(PAYLOAD_MAGIC.size)
            buffer.get(magic)
            if (!magic.contentEquals(PAYLOAD_MAGIC) || buffer.get() != PAYLOAD_VERSION) return null
            val authorizationLength = buffer.short.toInt() and 0xffff
            val policyLength = buffer.int
            if (
                authorizationLength !in 1..MAX_AUTHORIZATION_BYTES ||
                policyLength !in 2..MAX_SHAKESCAPE_POLICY_BYTES ||
                buffer.remaining() != authorizationLength + policyLength
            ) return null
            val ascii = ByteArray(authorizationLength)
            val policy = ByteArray(policyLength)
            buffer.get(ascii)
            buffer.get(policy)
            return takeAscii(port, ascii, policy)
        }

        private fun validShakescapePolicy(policy: ByteArray): Boolean =
            policy.size in 2..MAX_SHAKESCAPE_POLICY_BYTES &&
                policy.first() == '{'.code.toByte() &&
                policy.last() == '}'.code.toByte() &&
                policy.none { byte ->
                    val value = byte.toInt() and 0xff
                    value == 0 || value > 0x7f
                }

        private const val MAX_AUTHORIZATION_BYTES = 4_096
        private const val MAX_SHAKESCAPE_POLICY_BYTES = 16 * 1024
        private const val PAYLOAD_HEADER_BYTES = 11
        private const val PAYLOAD_VERSION: Byte = 1
        private val PAYLOAD_MAGIC = byteArrayOf(
            'H'.code.toByte(),
            'N'.code.toByte(),
            'R'.code.toByte(),
            'C'.code.toByte(),
        )
    }
}

/** Strict byte-level grammar; avoids constructing an immutable authorization String. */
internal fun parseWalletNodeBootstrap(
    contents: ByteArray,
    expectedNetworkId: String,
): ProvisionedNodeCredential? {
    val prefix = "HNS_NODE_RPC_V2\nnetwork=".toByteArray(Charsets.US_ASCII)
    val portPrefix = "\nport=".toByteArray(Charsets.US_ASCII)
    val authorizationPrefix = "\nauthorization=".toByteArray(Charsets.US_ASCII)
    val policyPrefix = "\nshakescape_policy_base64=".toByteArray(Charsets.US_ASCII)
    if (!contents.startsWith(prefix) || contents.lastOrNull() != '\n'.code.toByte()) return null
    val networkStart = prefix.size
    val portMarker = contents.indexOf(portPrefix, networkStart)
    if (portMarker < 0) return null
    val expectedNetwork = expectedNetworkId.toByteArray(Charsets.US_ASCII)
    if (!contents.regionEquals(networkStart, portMarker, expectedNetwork)) return null
    val portStart = portMarker + portPrefix.size
    val authorizationMarker = contents.indexOf(authorizationPrefix, portStart)
    if (authorizationMarker < 0) return null
    val port = parseCanonicalPort(contents, portStart, authorizationMarker) ?: return null
    val authorizationStart = authorizationMarker + authorizationPrefix.size
    val policyMarker = contents.indexOf(policyPrefix, authorizationStart)
    if (policyMarker < 0) return null
    val authorizationEnd = policyMarker
    val policyStart = policyMarker + policyPrefix.size
    val policyEnd = contents.size - 1
    if (
        authorizationStart >= authorizationEnd ||
        policyStart >= policyEnd ||
        contents.indexOf(byteArrayOf('\n'.code.toByte()), authorizationStart) != policyMarker ||
        contents.indexOf(byteArrayOf('\n'.code.toByte()), policyStart) != policyEnd
    ) return null
    val authorization = contents.copyOfRange(authorizationStart, authorizationEnd)
    var encodedPolicy = contents.copyOfRange(policyStart, policyEnd)
    var policy = ByteArray(0)
    var canonicalPolicy = ByteArray(0)
    return try {
        policy = runCatching { Base64.decode(encodedPolicy, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        canonicalPolicy = Base64.encode(policy, Base64.NO_WRAP)
        if (!canonicalPolicy.contentEquals(encodedPolicy)) return null
        ProvisionedNodeCredential.takeAscii(port, authorization, policy).also {
            policy = ByteArray(0)
        }
    } finally {
        authorization.fill(0)
        encodedPolicy.fill(0)
        policy.fill(0)
        canonicalPolicy.fill(0)
    }
}

private fun parseCanonicalPort(bytes: ByteArray, start: Int, end: Int): Int? {
    if (start >= end || end - start > 5 || bytes[start] == '0'.code.toByte()) return null
    var value = 0
    for (index in start until end) {
        val digit = bytes[index].toInt() - '0'.code
        if (digit !in 0..9) return null
        value = value * 10 + digit
    }
    return value.takeIf { it in 1..65_535 }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private fun ByteArray.regionEquals(start: Int, end: Int, expected: ByteArray): Boolean =
    end - start == expected.size && expected.indices.all { index -> this[start + index] == expected[index] }

private fun ByteArray.indexOf(needle: ByteArray, start: Int): Int {
    if (needle.isEmpty() || start < 0 || needle.size > size) return -1
    for (candidate in start..(size - needle.size)) {
        if (needle.indices.all { offset -> this[candidate + offset] == needle[offset] }) {
            return candidate
        }
    }
    return -1
}
