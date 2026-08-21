package com.denuoweb.hnsdane.wallet

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Closed projections returned by the wallet-owned Kyoto JNI controller.
 * These are app-owned Bitcoin values, not data fetched from an RPC node,
 * indexer, or marketplace relay.
 */
internal data class NativeBitcoinWalletSnapshot(
    val network: String,
    val receiveAddress: String,
    val confirmedSats: Long,
    val trustedPendingSats: Long,
    val untrustedPendingSats: Long,
    val immatureSats: Long,
    val totalSats: Long,
    val synchronizedHeight: Long,
    val connectedPeerCount: Int,
    val requiredPeerCount: Int,
)

internal data class NativeBitcoinReceiveAddress(
    val receiveAddress: String,
    val snapshot: NativeBitcoinWalletSnapshot,
)

internal data class NativeBitcoinSynchronization(
    val snapshot: NativeBitcoinWalletSnapshot,
    val sequence: Long,
    val checkpointHeight: Long,
    val connectedPeerCount: Int,
    val requiredPeerCount: Int,
)

internal data class NativeBitcoinSendApproval(
    val actionToken: NativeHnsValueActionToken,
    val destination: String,
    val amountSats: Long,
    val feeSats: Long,
    val maximumFeeSats: Long,
    val expiresAtUnix: Long,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeBitcoinSendReceipt(
    val txid: String,
    val wtxid: String,
    val attemptCount: Int,
    val submittedAtUnix: Long?,
)

internal object NativeBitcoinWalletBundle {
    private const val HEADER_BYTES = 12
    private const val MAX_JSON_BYTES = 16 * 1024
    private const val VERSION = 1
    private val magic = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte())

    fun snapshot(bundle: ByteArray): NativeBitcoinWalletSnapshot? = parse(bundle) { object ->
        parseSnapshot(object)
    }

    fun receive(bundle: ByteArray): NativeBitcoinReceiveAddress? = parse(bundle) { object ->
        if (object.keySet() != setOf("receiveAddress", "snapshot")) return@parse null
        val receiveAddress = address(object.optString("receiveAddress", "")) ?: return@parse null
        val snapshot = parseSnapshot(object.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        if (receiveAddress != snapshot.receiveAddress) return@parse null
        NativeBitcoinReceiveAddress(receiveAddress, snapshot)
    }

    fun synchronization(bundle: ByteArray): NativeBitcoinSynchronization? = parse(bundle) { object ->
        if (
            object.keySet() != setOf(
                "snapshot", "sequence", "checkpointHeight", "connectedPeerCount", "requiredPeerCount",
            )
        ) return@parse null
        val snapshot = parseSnapshot(object.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        val sequence = positiveLong(object, "sequence") ?: return@parse null
        val checkpointHeight = nonnegativeLong(object, "checkpointHeight") ?: return@parse null
        val connectedPeerCount = peerCount(object, "connectedPeerCount") ?: return@parse null
        val requiredPeerCount = peerCount(object, "requiredPeerCount") ?: return@parse null
        if (
            checkpointHeight != snapshot.synchronizedHeight ||
            connectedPeerCount != snapshot.connectedPeerCount ||
            requiredPeerCount != snapshot.requiredPeerCount
        ) return@parse null
        NativeBitcoinSynchronization(
            snapshot,
            sequence,
            checkpointHeight,
            connectedPeerCount,
            requiredPeerCount,
        )
    }

    fun sendApproval(bundle: ByteArray): NativeBitcoinSendApproval? = parse(bundle) { object ->
        if (
            object.keySet() != setOf(
                "actionToken", "destination", "amountSats", "feeSats", "maximumFeeSats", "expiresAtUnix",
            )
        ) return@parse null
        val token = object.optString("actionToken", "").toByteArray(Charsets.US_ASCII)
        val actionToken = NativeHnsValueActionToken.takeOwnership(token) ?: return@parse null
        val destination = address(object.optString("destination", ""))
        val amount = positiveLong(object, "amountSats")
        val fee = positiveLong(object, "feeSats")
        val maximumFee = positiveLong(object, "maximumFeeSats")
        val expires = positiveLong(object, "expiresAtUnix")
        if (destination == null || amount == null || fee == null || maximumFee == null || expires == null || fee > maximumFee) {
            actionToken.close()
            return@parse null
        }
        NativeBitcoinSendApproval(actionToken, destination, amount, fee, maximumFee, expires)
    }

    fun sendReceipt(bundle: ByteArray): NativeBitcoinSendReceipt? = parse(bundle) { object ->
        if (object.keySet() != setOf("txid", "wtxid", "attemptCount", "submittedAtUnix")) return@parse null
        val txid = hexHash(object.optString("txid", "")) ?: return@parse null
        val wtxid = hexHash(object.optString("wtxid", "")) ?: return@parse null
        val attempts = object.optInt("attemptCount", -1).takeIf { it in 1..16 } ?: return@parse null
        val submitted = if (object.isNull("submittedAtUnix")) null else positiveLong(object, "submittedAtUnix")
            ?: return@parse null
        NativeBitcoinSendReceipt(txid, wtxid, attempts, submitted)
    }

    private inline fun <T> parse(bundle: ByteArray, project: (JSONObject) -> T?): T? = try {
        if (bundle.size !in HEADER_BYTES..HEADER_BYTES + MAX_JSON_BYTES) return null
        val buffer = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN)
        val foundMagic = ByteArray(4)
        buffer.get(foundMagic)
        if (!foundMagic.contentEquals(magic)) return null
        if (buffer.get().toInt() and 0xff != VERSION) return null
        if (buffer.get().toInt() != 0 || buffer.short.toInt() != 0) return null
        val length = buffer.int
        if (length !in 2..MAX_JSON_BYTES || length != buffer.remaining()) return null
        val encoded = ByteArray(length)
        buffer.get(encoded)
        val text = encoded.toString(Charsets.UTF_8)
        if (text.toByteArray(Charsets.UTF_8).contentEquals(encoded).not()) return null
        project(JSONObject(text))
    } catch (_: Exception) {
        null
    }

    private fun parseSnapshot(object: JSONObject): NativeBitcoinWalletSnapshot? {
        if (
            object.keySet() != setOf(
                "network", "receiveAddress", "confirmedSats", "trustedPendingSats",
                "untrustedPendingSats", "immatureSats", "totalSats", "synchronizedHeight",
                "connectedPeerCount", "requiredPeerCount",
            )
        ) return null
        val network = object.optString("network", "")
        if (network !in setOf("mainnet", "testnet", "testnet4", "signet", "regtest")) return null
        val receiveAddress = address(object.optString("receiveAddress", "")) ?: return null
        val confirmed = nonnegativeLong(object, "confirmedSats") ?: return null
        val trusted = nonnegativeLong(object, "trustedPendingSats") ?: return null
        val untrusted = nonnegativeLong(object, "untrustedPendingSats") ?: return null
        val immature = nonnegativeLong(object, "immatureSats") ?: return null
        val total = nonnegativeLong(object, "totalSats") ?: return null
        if (total != confirmed + trusted + untrusted + immature) return null
        return NativeBitcoinWalletSnapshot(
            network,
            receiveAddress,
            confirmed,
            trusted,
            untrusted,
            immature,
            total,
            nonnegativeLong(object, "synchronizedHeight") ?: return null,
            peerCount(object, "connectedPeerCount") ?: return null,
            peerCount(object, "requiredPeerCount") ?: return null,
        )
    }

    private fun address(value: String): String? =
        value.takeIf { it.isNotBlank() && it.length <= 128 && it.all(Char::isLetterOrDigit) }

    private fun hexHash(value: String): String? =
        value.takeIf { it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' } }

    private fun nonnegativeLong(object: JSONObject, key: String): Long? =
        object.optLong(key, -1L).takeIf { it >= 0L }

    private fun positiveLong(object: JSONObject, key: String): Long? =
        object.optLong(key, 0L).takeIf { it > 0L }

    private fun peerCount(object: JSONObject, key: String): Int? =
        object.optInt(key, -1).takeIf { it in 0..8 }
}
