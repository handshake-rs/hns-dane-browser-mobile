package com.denuoweb.hnsdane.net

import android.content.Context
import com.denuoweb.hnsdane.R
import java.text.NumberFormat
import java.util.Locale

data class HnsSyncProgress(
    val syncStatusSchemaVersion: Long?,
    val network: String,
    val status: String,
    val bestHeight: Long?,
    val bestPeerHeight: Long?,
    val attempted: Long?,
    val successful: Long?,
    val accepted: Long?,
    val failed: Long?,
    val syncInFlight: Boolean,
    val stagedBestHeight: Long?,
    val stagedAccepted: Long?,
    val peerCount: Long?,
    val peerGroups: Long?,
    val estimatedTipHeight: Long?,
    val effectiveTargetHeight: Long?,
    val lagBlocks: Long?,
    val freshness: String,
    val freshnessThresholdBlocks: Long?,
    val treeIntervalBlocks: Long?,
    val authoritativeTreeRootHeight: Long?,
    val localTreeRootHeight: Long?,
    val treeRootReady: Boolean?,
    val blocksUntilAuthoritativeTreeRoot: Long?,
    val targetSource: String,
    val targetPeerGroups: Long?,
    val targetEvidenceExpired: Boolean?,
) {
    val targetHeight: Long?
        get() = effectiveTargetHeight

    private val displayStatus: String
        get() = if (syncInFlight) "syncing" else status.ifBlank { "idle" }

    val isBehind: Boolean
        get() {
            val best = bestHeight ?: return false
            val target = targetHeight ?: return false
            return target > best
        }

    val isBehindKnownPeer: Boolean
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
            return status in CURRENT_STATUSES &&
                freshness == "current" &&
                best > 0L &&
                target >= best &&
                lag >= 0L &&
                threshold == 2L &&
                lag == target - best &&
                lag <= threshold &&
                targetEvidenceExpired == false
        }

    val isAuthorityReady: Boolean
        get() {
            if (syncStatusSchemaVersion != CURRENT_SCHEMA_VERSION || treeRootReady != true) return false
            val best = bestHeight ?: return false
            val localRoot = localTreeRootHeight ?: return false
            val interval = treeIntervalBlocks ?: return false
            val blocksUntilAuthority = blocksUntilAuthoritativeTreeRoot ?: return false
            if (
                best <= 0L ||
                interval <= 0L ||
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
                (targetPeerGroups ?: 0L) >= 3L &&
                targetEvidenceExpired == false
        }

    /**
     * Whether the global diagnostic sync indicator should be visible.
     *
     * This is deliberately stricter than [isAuthorityReady]: the latest committed
     * name-tree root can remain safe to browse while newer headers are still being
     * synchronized. UI visibility must not be used as a navigation security gate.
     */
    val shouldShowProgress: Boolean
        get() = syncInFlight || !isCurrent

    val shouldContinueSoon: Boolean
        get() = status == "syncing" &&
            (accepted ?: 0L) > 0L

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
        val best = stagedBestHeight?.takeIf { syncInFlight } ?: bestHeight ?: return null
        val target = targetHeight ?: return null
        if (target <= 0L) return null
        return ((best.coerceIn(0L, target) * 1000L) / target).toInt()
    }

    fun summary(): String {
        return renderSummary(
            statusText = displayStatus,
            formattedBest = bestHeight?.formatHeight() ?: "unknown",
            bestHeightText = { "bestHeight $it" },
            formatHeight = { it.formatHeight() },
            targetText = { "target ${it.formatHeight()}" },
            rootReadyText = { "HNS root ${it.formatHeight()} ready" },
            rootRequiredText = { "needs HNS root ${it.formatHeight()}" },
            acceptedText = { "accepted +${it.formatHeight()}" },
            stagedAcceptedText = { "staged accepted +${it.formatHeight()}" },
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
            rootReadyText = {
                context.getString(R.string.sync_progress_root_ready, it.formatHeight(context))
            },
            rootRequiredText = {
                context.getString(R.string.sync_progress_root_required, it.formatHeight(context))
            },
            acceptedText = {
                context.getString(R.string.sync_progress_accepted, it.formatHeight(context))
            },
            stagedAcceptedText = {
                context.getString(R.string.sync_progress_staged_accepted, it.formatHeight(context))
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
        rootReadyText: (Long) -> String,
        rootRequiredText: (Long) -> String,
        acceptedText: (Long) -> String,
        stagedAcceptedText: (Long) -> String,
        peersText: (Long) -> String,
    ): String {
        val parts = mutableListOf(statusText)
        if (syncInFlight) {
            stagedBestHeight?.let { parts += "staged validated ${formatHeight(it)}" }
        } else {
            parts += bestHeightText(formattedBest)
        }
        targetHeight?.let { parts += targetText(it) }
        authoritativeTreeRootHeight?.let {
            parts += if (isAuthorityReady) rootReadyText(it) else rootRequiredText(it)
        }
        when {
            bestPeerHeight != null -> parts += "raw peer ${formatHeight(bestPeerHeight)}"
            estimatedTipHeight != null -> parts += "estimate ${formatHeight(estimatedTipHeight)}"
        }
        if (syncInFlight) {
            stagedAccepted?.let { parts += stagedAcceptedText(it) }
        } else {
            accepted?.takeIf { it > 0L }?.let { parts += acceptedText(it) }
        }
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

        fun fromJson(statusJson: String?): HnsSyncProgress {
            if (statusJson.isNullOrBlank()) {
                return HnsSyncProgress(
                    syncStatusSchemaVersion = null,
                    network = "unknown",
                    status = "idle",
                    bestHeight = null,
                    bestPeerHeight = null,
                    attempted = null,
                    successful = null,
                    accepted = null,
                    failed = null,
                    syncInFlight = false,
                    stagedBestHeight = null,
                    stagedAccepted = null,
                    peerCount = null,
                    peerGroups = null,
                    estimatedTipHeight = null,
                    effectiveTargetHeight = null,
                    lagBlocks = null,
                    freshness = "unknown",
                    freshnessThresholdBlocks = null,
                    treeIntervalBlocks = null,
                    authoritativeTreeRootHeight = null,
                    localTreeRootHeight = null,
                    treeRootReady = null,
                    blocksUntilAuthoritativeTreeRoot = null,
                    targetSource = "unknown",
                    targetPeerGroups = null,
                    targetEvidenceExpired = null,
                )
            }
            return HnsSyncProgress(
                syncStatusSchemaVersion = longField(statusJson, "syncStatusSchemaVersion"),
                network = stringField(statusJson, "network") ?: "unknown",
                status = stringField(statusJson, "status") ?: "idle",
                bestHeight = longField(statusJson, "bestHeight"),
                bestPeerHeight = longField(statusJson, "bestPeerHeight"),
                attempted = longField(statusJson, "attempted"),
                successful = longField(statusJson, "successful"),
                accepted = longField(statusJson, "accepted"),
                failed = longField(statusJson, "failed"),
                syncInFlight = booleanField(statusJson, "syncInFlight") ?: false,
                stagedBestHeight = longField(statusJson, "stagedBestHeight"),
                stagedAccepted = longField(statusJson, "stagedAccepted"),
                peerCount = longField(statusJson, "peerCount"),
                peerGroups = longField(statusJson, "peerGroups"),
                estimatedTipHeight = longField(statusJson, "estimatedTipHeight"),
                effectiveTargetHeight = longField(statusJson, "effectiveTargetHeight"),
                lagBlocks = longField(statusJson, "lagBlocks"),
                freshness = stringField(statusJson, "freshness") ?: "unknown",
                freshnessThresholdBlocks = longField(statusJson, "freshnessThresholdBlocks"),
                treeIntervalBlocks = longField(statusJson, "treeIntervalBlocks"),
                authoritativeTreeRootHeight = longField(statusJson, "authoritativeTreeRootHeight"),
                localTreeRootHeight = longField(statusJson, "localTreeRootHeight"),
                treeRootReady = booleanField(statusJson, "treeRootReady"),
                blocksUntilAuthoritativeTreeRoot =
                    longField(statusJson, "blocksUntilAuthoritativeTreeRoot"),
                targetSource = stringField(statusJson, "targetSource") ?: "unknown",
                targetPeerGroups = longField(statusJson, "targetPeerGroups"),
                targetEvidenceExpired = booleanField(statusJson, "targetEvidenceExpired"),
            )
        }

        private fun stringField(json: String, name: String): String? {
            val pattern = """"$name"\s*:\s*"([^"]*)"""".toRegex()
            return pattern.find(json)?.groupValues?.getOrNull(1)
        }

        private fun longField(json: String, name: String): Long? {
            val pattern = """"$name"\s*:\s*(null|-?\d+)(?=\s*[,}])""".toRegex()
            val value = pattern.find(json)?.groupValues?.getOrNull(1) ?: return null
            return value.takeUnless { it == "null" }?.toLongOrNull()
        }

        private fun booleanField(json: String, name: String): Boolean? {
            val pattern = """"$name"\s*:\s*(true|false|null)(?=\s*[,}])""".toRegex()
            return when (pattern.find(json)?.groupValues?.getOrNull(1)) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }
}
