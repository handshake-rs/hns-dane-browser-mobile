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

    val shouldContinueSoon: Boolean
        get() = status == "syncing" &&
            (accepted ?: 0L) > 0L

    val shouldRetrySoon: Boolean
        get() = status in RETRY_STATUSES || needsPeerDiscovery || hasUnknownTargetProgress

    val hasUnknownTargetProgress: Boolean
        get() = bestHeight != null &&
            bestHeight > 0L &&
            effectiveTargetHeight == null &&
            ((accepted ?: 0L) > 0L || status == "syncing")

    val needsPeerDiscovery: Boolean
        get() = status == "idle" && (peerCount ?: 0L) == 0L

    fun progressPermille(): Int? {
        val best = bestHeight ?: return null
        val target = targetHeight ?: return null
        if (target <= 0L) return null
        return ((best.coerceIn(0L, target) * 1000L) / target).toInt()
    }

    fun summary(): String {
        val formattedBest = bestHeight?.formatHeight() ?: "unknown"
        val target = targetHeight
        val targetPart = when {
            isBehind && target != null -> "target ${target.formatHeight()}"
            target != null -> "target ${target.formatHeight()}"
            else -> "target unknown"
        }
        val authorityPart = when {
            isAuthorityReady && authoritativeTreeRootHeight != null ->
                " • HNS root ${authoritativeTreeRootHeight.formatHeight()} ready"
            authoritativeTreeRootHeight != null ->
                " • needs HNS root ${authoritativeTreeRootHeight.formatHeight()}"
            else -> " • HNS root unknown"
        }
        val diagnosticPart = when {
            bestPeerHeight != null -> " • raw peer ${bestPeerHeight.formatHeight()}"
            estimatedTipHeight != null -> " • estimate ${estimatedTipHeight.formatHeight()}"
            else -> ""
        }
        val acceptedPart = accepted
            ?.takeIf { it > 0L }
            ?.let { " • accepted +${it.formatHeight()}" }
            .orEmpty()
        val peerPart = peerCount
            ?.takeIf { it > 0L }
            ?.let { " • peers ${it.formatHeight()}" }
            .orEmpty()
        return "${status.ifBlank { "idle" }} • bestHeight $formattedBest • $targetPart$authorityPart$diagnosticPart$acceptedPart$peerPart"
    }

    fun summary(context: Context): String {
        val formattedBest = bestHeight?.formatHeight(context) ?: context.getString(R.string.common_unknown)
        val target = targetHeight
        val targetPart = when {
            isBehind && target != null -> context.getString(R.string.sync_progress_target, target.formatHeight(context))
            target != null -> context.getString(R.string.sync_progress_target, target.formatHeight(context))
            else -> context.getString(R.string.sync_progress_target_unknown)
        }
        val authorityPart = when {
            isAuthorityReady && authoritativeTreeRootHeight != null ->
                " • ${context.getString(R.string.sync_progress_root_ready, authoritativeTreeRootHeight.formatHeight(context))}"
            authoritativeTreeRootHeight != null ->
                " • ${context.getString(R.string.sync_progress_root_required, authoritativeTreeRootHeight.formatHeight(context))}"
            else -> " • ${context.getString(R.string.sync_progress_root_unknown)}"
        }
        val diagnosticPart = when {
            bestPeerHeight != null -> " • raw peer ${bestPeerHeight.formatHeight(context)}"
            estimatedTipHeight != null -> " • estimate ${estimatedTipHeight.formatHeight(context)}"
            else -> ""
        }
        val acceptedPart = accepted
            ?.takeIf { it > 0L }
            ?.let { " • ${context.getString(R.string.sync_progress_accepted, it.formatHeight(context))}" }
            .orEmpty()
        val peerPart = peerCount
            ?.takeIf { it > 0L }
            ?.let { " • ${context.getString(R.string.sync_progress_peers, it.formatHeight(context))}" }
            .orEmpty()
        return context.getString(
            R.string.sync_progress_summary,
            statusLabel(context),
            formattedBest,
            targetPart,
            authorityPart + diagnosticPart + acceptedPart,
            peerPart,
        )
    }

    private fun Long.formatHeight(): String =
        NumberFormat.getIntegerInstance(Locale.US).format(this)

    private fun Long.formatHeight(context: Context): String =
        NumberFormat.getIntegerInstance(context.resources.configuration.locales[0] ?: Locale.getDefault()).format(this)

    private fun statusLabel(context: Context): String =
        when (status.ifBlank { "idle" }) {
            "idle" -> context.getString(R.string.sync_status_idle)
            "syncing" -> context.getString(R.string.sync_status_syncing)
            "up_to_date" -> context.getString(R.string.sync_status_up_to_date)
            "error" -> context.getString(R.string.sync_status_error)
            "seed_failed" -> context.getString(R.string.sync_status_seed_failed)
            "peer_failed" -> context.getString(R.string.sync_status_peer_failed)
            else -> status.replace('_', ' ')
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
