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

internal data class NativeBitcoinSyncProgress(
    val successfulHandshakes: Int,
    val requiredPeerCount: Int,
    val connectionFailures: Int,
    val peerTimeouts: Int,
    val incompatiblePeers: Int,
    val connectionsMet: Boolean,
    val chainHeight: Long?,
    val completionBasisPoints: Long,
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

    fun snapshot(bundle: ByteArray): NativeBitcoinWalletSnapshot? = parse(bundle) { json ->
        parseSnapshot(json)
    }

    fun receive(bundle: ByteArray): NativeBitcoinReceiveAddress? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("receiveAddress", "snapshot"))) return@parse null
        val receiveAddress = address(json.optString("receiveAddress", "")) ?: return@parse null
        val snapshot = parseSnapshot(json.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        if (receiveAddress != snapshot.receiveAddress) return@parse null
        NativeBitcoinReceiveAddress(receiveAddress, snapshot)
    }

    fun synchronization(bundle: ByteArray): NativeBitcoinSynchronization? = parse(bundle) { json ->
        if (
            !hasExactKeys(json, setOf(
                "snapshot", "sequence", "checkpointHeight", "connectedPeerCount", "requiredPeerCount",
            ))
        ) return@parse null
        val snapshot = parseSnapshot(json.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        val sequence = positiveLong(json, "sequence") ?: return@parse null
        val checkpointHeight = nonnegativeLong(json, "checkpointHeight") ?: return@parse null
        val connectedPeerCount = peerCount(json, "connectedPeerCount") ?: return@parse null
        val requiredPeerCount = peerCount(json, "requiredPeerCount") ?: return@parse null
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

    fun syncProgress(bundle: ByteArray): NativeBitcoinSyncProgress? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "successfulHandshakes", "requiredPeerCount", "connectionFailures",
            "peerTimeouts", "incompatiblePeers", "connectionsMet", "chainHeight",
            "completionBasisPoints",
        ))) return@parse null
        val handshakes = json.optInt("successfulHandshakes", -1).takeIf { it in 0..255 }
            ?: return@parse null
        val requiredPeers = json.optInt("requiredPeerCount", -1).takeIf { it in 1..255 }
            ?: return@parse null
        val connectionFailures = json.optInt("connectionFailures", -1).takeIf { it in 0..65_535 }
            ?: return@parse null
        val peerTimeouts = json.optInt("peerTimeouts", -1).takeIf { it in 0..65_535 }
            ?: return@parse null
        val incompatiblePeers = json.optInt("incompatiblePeers", -1).takeIf { it in 0..65_535 }
            ?: return@parse null
        val connectionsMet = when (json.opt("connectionsMet")) {
            true -> true
            false -> false
            else -> return@parse null
        }
        val chainHeight = if (json.isNull("chainHeight")) null else
            nonnegativeLong(json, "chainHeight") ?: return@parse null
        val completion = nonnegativeLong(json, "completionBasisPoints")
            ?.takeIf { it <= 10_000L } ?: return@parse null
        NativeBitcoinSyncProgress(
            handshakes,
            requiredPeers,
            connectionFailures,
            peerTimeouts,
            incompatiblePeers,
            connectionsMet,
            chainHeight,
            completion,
        )
    }

    fun sendApproval(bundle: ByteArray): NativeBitcoinSendApproval? = parse(bundle) { json ->
        if (
            !hasExactKeys(json, setOf(
                "actionToken", "destination", "amountSats", "feeSats", "maximumFeeSats", "expiresAtUnix",
            ))
        ) return@parse null
        val token = json.optString("actionToken", "").toByteArray(Charsets.US_ASCII)
        val actionToken = NativeHnsValueActionToken.takeOwnership(token) ?: return@parse null
        val destination = address(json.optString("destination", ""))
        val amount = positiveLong(json, "amountSats")
        val fee = positiveLong(json, "feeSats")
        val maximumFee = positiveLong(json, "maximumFeeSats")
        val expires = positiveLong(json, "expiresAtUnix")
        if (destination == null || amount == null || fee == null || maximumFee == null || expires == null || fee > maximumFee) {
            actionToken.close()
            return@parse null
        }
        NativeBitcoinSendApproval(actionToken, destination, amount, fee, maximumFee, expires)
    }

    fun sendReceipt(bundle: ByteArray): NativeBitcoinSendReceipt? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("txid", "wtxid", "attemptCount", "submittedAtUnix"))) return@parse null
        val txid = hexHash(json.optString("txid", "")) ?: return@parse null
        val wtxid = hexHash(json.optString("wtxid", "")) ?: return@parse null
        val attempts = json.optInt("attemptCount", -1).takeIf { it in 1..16 } ?: return@parse null
        val submitted = if (json.isNull("submittedAtUnix")) null else positiveLong(json, "submittedAtUnix")
            ?: return@parse null
        NativeBitcoinSendReceipt(txid, wtxid, attempts, submitted)
    }

    private inline fun <T> parse(bundle: ByteArray, project: (JSONObject) -> T?): T? {
        return try {
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
    }

    private fun parseSnapshot(json: JSONObject): NativeBitcoinWalletSnapshot? {
        if (
            !hasExactKeys(json, setOf(
                "network", "receiveAddress", "confirmedSats", "trustedPendingSats",
                "untrustedPendingSats", "immatureSats", "totalSats", "synchronizedHeight",
                "connectedPeerCount", "requiredPeerCount",
            ))
        ) return null
        val network = json.optString("network", "")
        if (network !in setOf("mainnet", "testnet", "testnet4", "signet", "regtest")) return null
        val receiveAddress = address(json.optString("receiveAddress", "")) ?: return null
        val confirmed = nonnegativeLong(json, "confirmedSats") ?: return null
        val trusted = nonnegativeLong(json, "trustedPendingSats") ?: return null
        val untrusted = nonnegativeLong(json, "untrustedPendingSats") ?: return null
        val immature = nonnegativeLong(json, "immatureSats") ?: return null
        val total = nonnegativeLong(json, "totalSats") ?: return null
        if (total != confirmed + trusted + untrusted + immature) return null
        return NativeBitcoinWalletSnapshot(
            network,
            receiveAddress,
            confirmed,
            trusted,
            untrusted,
            immature,
            total,
            nonnegativeLong(json, "synchronizedHeight") ?: return null,
            peerCount(json, "connectedPeerCount") ?: return null,
            peerCount(json, "requiredPeerCount") ?: return null,
        )
    }

    private fun address(value: String): String? =
        value.takeIf { it.isNotBlank() && it.length <= 128 && it.all(Char::isLetterOrDigit) }

    private fun hexHash(value: String): String? =
        value.takeIf { it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' } }

    private fun nonnegativeLong(json: JSONObject, key: String): Long? =
        json.optLong(key, -1L).takeIf { it >= 0L }

    private fun positiveLong(json: JSONObject, key: String): Long? =
        json.optLong(key, 0L).takeIf { it > 0L }

    private fun peerCount(json: JSONObject, key: String): Int? =
        json.optInt(key, -1).takeIf { it in 0..8 }

    private fun hasExactKeys(json: JSONObject, expected: Set<String>): Boolean {
        val actual = HashSet<String>()
        val keys = json.keys()
        while (keys.hasNext()) actual.add(keys.next())
        return actual == expected
    }
}
