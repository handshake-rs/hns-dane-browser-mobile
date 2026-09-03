package com.denuoweb.hnsdane.net

import android.content.Context
import com.denuoweb.hnsdane.R
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

data class HnsSyncProgress(
    val syncStatusSchemaVersion: Long? = null,
    val network: String = "unknown",
    val status: String = "idle",
    val bestHeight: Long? = null,
    val bestPeerHeight: Long? = null,
    val attempted: Long? = null,
    val successful: Long? = null,
    val accepted: Long? = null,
    val failed: Long? = null,
    val syncInFlight: Boolean = false,
    val stagedBestHeight: Long? = null,
    val stagedAccepted: Long? = null,
    val peerCount: Long? = null,
    val peerGroups: Long? = null,
    val estimatedTipHeight: Long? = null,
    val effectiveTargetHeight: Long? = null,
    val lagBlocks: Long? = null,
    val freshness: String = "unknown",
    val freshnessThresholdBlocks: Long? = null,
    val treeIntervalBlocks: Long? = null,
    val authoritativeTreeRootHeight: Long? = null,
    val localTreeRootHeight: Long? = null,
    val treeRootReady: Boolean? = null,
    val blocksUntilAuthoritativeTreeRoot: Long? = null,
    val targetSource: String = "unknown",
    val targetPeerGroups: Long? = null,
    val targetEvidenceExpired: Boolean? = null,
) {
    /**
     * Header-tip maintenance is not browser synchronization when the committed
     * chain already contains the name-tree root authoritative for the peer
     * target. Keep that distinction in one projection so callers do not each
     * reinterpret the native transport status.
     */
    private val displayStatus: String
        get() = when {
            isCurrent -> "up_to_date"
            isAuthorityReady -> "name_state_ready"
            syncInFlight -> "syncing"
            else -> status.ifBlank { "idle" }
        }

    val isBehind: Boolean
        get() {
            val best = bestHeight ?: return false
            val target = effectiveTargetHeight ?: return false
            return target > best
        }

    val isCurrent: Boolean
        get() {
            if (!isAuthorityReady) return false
            val best = bestHeight ?: return false
            val target = effectiveTargetHeight ?: return false
            val lag = lagBlocks ?: return false
            val threshold = freshnessThresholdBlocks ?: return false
            // A scheduler pass can attempt multiple peer endpoints. A result
            // with no successful peer is a transport failure, not evidence
            // that the retained local tip is current.
            val attemptedWithNoSuccessfulPeer =
                status == "attempted" && (successful ?: 0L) == 0L
            return status in CURRENT_STATUSES &&
                !attemptedWithNoSuccessfulPeer &&
                freshness == "current" &&
                best > 0L &&
                target >= best &&
                lag >= 0L &&
                threshold == REQUIRED_FRESHNESS_THRESHOLD_BLOCKS &&
                lag == target - best &&
                lag <= threshold &&
                targetEvidenceExpired == false
        }

    val isAuthorityReady: Boolean
        get() {
            if (
                syncStatusSchemaVersion != CURRENT_SCHEMA_VERSION ||
                network !in EXPECTED_TREE_INTERVAL_BLOCKS ||
                treeRootReady != true
            ) {
                return false
            }
            val best = bestHeight ?: return false
            val localRoot = localTreeRootHeight ?: return false
            val interval = treeIntervalBlocks ?: return false
            val expectedInterval = EXPECTED_TREE_INTERVAL_BLOCKS[network] ?: return false
            val blocksUntilAuthority = blocksUntilAuthoritativeTreeRoot ?: return false
            if (
                best <= 0L ||
                interval != expectedInterval ||
                localRoot <= 0L ||
                localRoot > best ||
                blocksUntilAuthority != 0L
            ) {
                return false
            }
            if (network == "regtest") {
                return true
            }
            val target = effectiveTargetHeight ?: return false
            val authorityRoot = authoritativeTreeRootHeight ?: return false
            return targetSource == "corroboratedPeers" &&
                target >= best &&
                authorityRoot > 0L &&
                localRoot == authorityRoot &&
                best >= authorityRoot &&
                (targetPeerGroups ?: 0L) >= REQUIRED_TARGET_PEER_GROUPS &&
                targetEvidenceExpired == false
        }

    fun isAuthorityReadyFor(expectedNetwork: String): Boolean =
        network == expectedNetwork && isAuthorityReady

    /** The global diagnostic sync strip remains visible in every state. */
    val shouldShowProgress: Boolean
        get() = true

    val needsTreeRootCatchUpContinuation: Boolean
        get() = status == "syncing" &&
            (accepted ?: 0L) > 0L &&
            !isAuthorityReady

    val requiresAttention: Boolean
        get() = status in RETRY_STATUSES

    val shouldRetrySoon: Boolean
        get() = status in RETRY_STATUSES ||
            needsPeerDiscovery ||
            needsHeaderBootstrap ||
            hasUnknownTargetProgress

    val hasUnknownTargetProgress: Boolean
        get() = syncStatusSchemaVersion == CURRENT_SCHEMA_VERSION &&
            network != "regtest" &&
            bestHeight != null &&
            bestHeight > 0L &&
            effectiveTargetHeight == null

    val needsPeerDiscovery: Boolean
        get() = status == "idle" && (peerCount ?: 0L) == 0L

    /**
     * A reset mainnet/testnet store is initialized at genesis while its retained peers may still
     * be temporarily ineligible. Keep retrying it without applying this cadence to legacy status
     * payloads or deliberately local regtest state.
     */
    val needsHeaderBootstrap: Boolean
        get() = syncStatusSchemaVersion == CURRENT_SCHEMA_VERSION &&
            network != "regtest" &&
            bestHeight == 0L

    fun progressPermille(): Int? {
        // Staged height is presentation-only. Authority and navigation decisions
        // continue to use the committed fields above.
        val best = stagedBestHeight?.takeIf { syncInFlight && !isAuthorityReady }
            ?: bestHeight
            ?: return null
        val target = effectiveTargetHeight ?: return null
        if (target <= 0L) return null
        return ((best.coerceIn(0L, target) * 1000L) / target).toInt()
    }

    /**
     * Presentation-only heights for a navigation waiting on header authority.
     * The staged height contains headers already validated in the private sync
     * stage, but cannot grant authority before atomic publication. Until peer
     * groups corroborate a target, the clock-derived estimate is labelled so
     * it cannot be mistaken for authenticated peer evidence.
     */
    fun gateHeights(): SyncGateHeights? {
        val current = stagedBestHeight?.takeIf { syncInFlight } ?: bestHeight
        val target = effectiveTargetHeight ?: estimatedTipHeight
        if (current == null && target == null) return null
        return SyncGateHeights(
            current = current,
            target = target,
            targetIsEstimated = effectiveTargetHeight == null && estimatedTipHeight != null,
        )
    }

    fun summary(): String {
        return renderSummary(
            statusText = displayStatus,
            formattedBest = bestHeight?.formatHeight() ?: "unknown",
            bestHeightText = { "current height $it" },
            formatHeight = { it.formatHeight() },
            targetText = { "target ${it.formatHeight()}" },
            peersText = { "peers ${it.formatHeight()}" },
        )
    }

    fun summary(context: Context): String {
        return renderSummary(
            statusText = statusLabel(context),
            formattedBest = bestHeight?.formatHeight(context) ?: context.getString(R.string.common_unknown),
            bestHeightText = {
                context.getString(R.string.sync_progress_best_height, it)
            },
            formatHeight = { it.formatHeight(context) },
            targetText = {
                context.getString(R.string.sync_progress_target, it.formatHeight(context))
            },
            peersText = {
                context.getString(R.string.sync_progress_peers, it.formatHeight(context))
            },
        )
    }

    private fun renderSummary(
        statusText: String,
        formattedBest: String,
        bestHeightText: (String) -> String,
        formatHeight: (Long) -> String,
        targetText: (Long) -> String,
        peersText: (Long) -> String,
    ): String {
        val parts = mutableListOf(statusText)
        if (syncInFlight && !isAuthorityReady) {
            stagedBestHeight?.let { parts += "staged validated ${formatHeight(it)}" }
        } else {
            parts += bestHeightText(formattedBest)
        }
        effectiveTargetHeight?.let { parts += targetText(it) }
        peerCount?.takeIf { it > 0L }?.let { parts += peersText(it) }
        return parts.joinToString(" • ")
    }

    private fun Long.formatHeight(): String =
        NumberFormat.getIntegerInstance(Locale.US).format(this)

    private fun Long.formatHeight(context: Context): String =
        NumberFormat.getIntegerInstance(context.resources.configuration.locales[0] ?: Locale.getDefault()).format(this)

    private fun statusLabel(context: Context): String =
        when (displayStatus) {
            "idle" -> context.getString(R.string.sync_status_idle)
            "syncing" -> context.getString(R.string.sync_status_syncing)
            "up_to_date" -> context.getString(R.string.sync_status_up_to_date)
            "error" -> context.getString(R.string.sync_status_error)
            "seed_failed" -> context.getString(R.string.sync_status_seed_failed)
            "peer_failed" -> context.getString(R.string.sync_status_peer_failed)
            else -> displayStatus.replace('_', ' ')
        }

    companion object {
        private val CURRENT_STATUSES = setOf("up_to_date", "synced", "attempted")
        private val RETRY_STATUSES = setOf("error", "peer_failed", "seed_failed")
        private const val CURRENT_SCHEMA_VERSION = 3L
        private const val REQUIRED_FRESHNESS_THRESHOLD_BLOCKS = 2L
        private const val REQUIRED_TARGET_PEER_GROUPS = 3L
        private const val MAX_STATUS_JSON_CHARS = 64 * 1024
        private const val MAX_U32 = 0xffff_ffffL
        private val EXPECTED_TREE_INTERVAL_BLOCKS = mapOf(
            "mainnet" to 36L,
            "testnet" to 36L,
            "regtest" to 5L,
        )

        fun fromJson(statusJson: String?): HnsSyncProgress {
            val json = statusJson
                ?.takeIf { it.isNotBlank() && it.length <= MAX_STATUS_JSON_CHARS }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return empty()
            return HnsSyncProgress(
                syncStatusSchemaVersion = json.nonnegativeLongField("syncStatusSchemaVersion"),
                network = json.stringField("network") ?: "unknown",
                status = json.stringField("status") ?: "idle",
                bestHeight = json.u32Field("bestHeight"),
                bestPeerHeight = json.u32Field("bestPeerHeight"),
                attempted = json.nonnegativeLongField("attempted"),
                successful = json.nonnegativeLongField("successful"),
                accepted = json.nonnegativeLongField("accepted"),
                failed = json.nonnegativeLongField("failed"),
                syncInFlight = json.booleanField("syncInFlight") ?: false,
                stagedBestHeight = json.u32Field("stagedBestHeight"),
                stagedAccepted = json.nonnegativeLongField("stagedAccepted"),
                peerCount = json.nonnegativeLongField("peerCount"),
                peerGroups = json.nonnegativeLongField("peerGroups"),
                estimatedTipHeight = json.u32Field("estimatedTipHeight"),
                effectiveTargetHeight = json.u32Field("effectiveTargetHeight"),
                lagBlocks = json.u32Field("lagBlocks"),
                freshness = json.stringField("freshness") ?: "unknown",
                freshnessThresholdBlocks = json.u32Field("freshnessThresholdBlocks"),
                treeIntervalBlocks = json.u32Field("treeIntervalBlocks"),
                authoritativeTreeRootHeight = json.u32Field("authoritativeTreeRootHeight"),
                localTreeRootHeight = json.u32Field("localTreeRootHeight"),
                treeRootReady = json.booleanField("treeRootReady"),
                blocksUntilAuthoritativeTreeRoot =
                    json.u32Field("blocksUntilAuthoritativeTreeRoot"),
                targetSource = json.stringField("targetSource") ?: "unknown",
                targetPeerGroups = json.nonnegativeLongField("targetPeerGroups"),
                targetEvidenceExpired = json.booleanField("targetEvidenceExpired"),
            )
        }

        private fun empty() = HnsSyncProgress()

        private fun JSONObject.stringField(name: String): String? =
            opt(name) as? String

        private fun JSONObject.longField(name: String): Long? =
            (opt(name) as? Number)?.toString()?.toLongOrNull()

        private fun JSONObject.nonnegativeLongField(name: String): Long? =
            longField(name)?.takeIf { it >= 0L }

        private fun JSONObject.u32Field(name: String): Long? =
            longField(name)?.takeIf { it in 0L..MAX_U32 }

        private fun JSONObject.booleanField(name: String): Boolean? =
            opt(name) as? Boolean
    }
}

data class SyncGateHeights(
    val current: Long?,
    val target: Long?,
    val targetIsEstimated: Boolean,
)
