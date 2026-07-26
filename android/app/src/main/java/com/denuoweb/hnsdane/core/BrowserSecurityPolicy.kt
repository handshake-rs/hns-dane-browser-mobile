package com.denuoweb.hnsdane.core

object BrowserSecurityPolicy {
    fun state(
        targetKind: BrowserTargetKind?,
        proxyAvailable: Boolean,
        syncStatusJson: String?,
        mainFrameHnsStatusCode: Int? = null,
        mainFrameHnsTlsPolicy: HnsPageTlsPolicy? = null,
        mainFrameHnsResolverPolicy: HnsPageResolverPolicy? = null,
        mainFrameHnsSecurityPath: HnsPageSecurityPath? = null,
        mainFrameHnsResolutionTraceJson: String? = null,
        isOpaqueIpLiteral: Boolean = false,
    ): SecurityState {
        if (targetKind == BrowserTargetKind.LocalAsset) {
            return SecurityState.LocalContent
        }
        if (targetKind == BrowserTargetKind.Blocked) {
            return SecurityState.ValidationFailed
        }
        if (targetKind == BrowserTargetKind.ExactUrl && isOpaqueIpLiteral) {
            return if (proxyAvailable) {
                SecurityState.WebPkiOnly
            } else {
                SecurityState.ValidationFailed
            }
        }
        if (
            targetKind != BrowserTargetKind.HnsName &&
            targetKind != BrowserTargetKind.ExactUrl &&
            targetKind != BrowserTargetKind.NativeGateway
        ) {
            return SecurityState.WebPkiOnly
        }
        if (mainFrameHnsStatusCode?.let { it in 400..599 } == true) {
            return SecurityState.ValidationFailed
        }
        if (mainFrameHnsStatusCode?.let { it in 200..299 } == true) {
            if (
                mainFrameHnsResolverPolicy == HnsPageResolverPolicy.HnsDohCompatibility ||
                mainFrameHnsSecurityPath == HnsPageSecurityPath.DaneThirdPartyDoh ||
                mainFrameHnsSecurityPath == HnsPageSecurityPath.HnsThirdPartyDoh ||
                (
                    targetKind == BrowserTargetKind.HnsName &&
                        mainFrameHnsTlsPolicy == HnsPageTlsPolicy.WebPkiFallback
                    )
            ) {
                return SecurityState.ValidationFailed
            }
            mainFrameHnsSecurityPath?.let { securityPath ->
                return securityPath.securityState()
            }
            if (mainFrameHnsTlsPolicy == HnsPageTlsPolicy.Dane) {
                return SecurityState.DaneVerified
            }
            if (mainFrameHnsTlsPolicy == HnsPageTlsPolicy.WebPkiFallback) {
                if (
                    targetKind == BrowserTargetKind.ExactUrl ||
                    targetKind == BrowserTargetKind.NativeGateway
                ) {
                    return if (
                        BrowserResolutionTrace.authorizesWebPkiFallback(
                            mainFrameHnsResolutionTraceJson,
                        )
                    ) {
                        SecurityState.WebPkiOnly
                    } else {
                        SecurityState.ValidationFailed
                    }
                }
                return SecurityState.ValidationFailed
            }
            if (
                targetKind == BrowserTargetKind.ExactUrl ||
                targetKind == BrowserTargetKind.NativeGateway
            ) {
                return SecurityState.WebPkiOnly
            }
            return SecurityState.HnsVerified
        }
        if (!proxyAvailable && targetKind == BrowserTargetKind.HnsName) {
            return SecurityState.ProofUnavailable
        }
        if (
            targetKind == BrowserTargetKind.ExactUrl ||
            targetKind == BrowserTargetKind.NativeGateway
        ) {
            return SecurityState.Loading
        }
        if (
            syncStatusJson.hasSyncStatus("error") ||
            syncStatusJson.hasSyncStatus("seed_failed") ||
            syncStatusJson.hasSyncStatus("peer_failed")
        ) {
            return SecurityState.ProofUnavailable
        }
        if (
            syncStatusJson.hasAuthoritativelyCurrentHeaders() &&
            (
                syncStatusJson.hasSyncStatus("synced") ||
                    syncStatusJson.hasSyncStatus("up_to_date") ||
                    syncStatusJson.hasSyncStatus("attempted")
                )
        ) {
            return SecurityState.Loading
        }

        return SecurityState.Syncing
    }

    private fun HnsPageSecurityPath.securityState(): SecurityState =
        when (this) {
            HnsPageSecurityPath.DaneAuthoritativeDoh -> SecurityState.DaneViaAuthoritativeDoh
            HnsPageSecurityPath.DaneAuthoritativeDns53 -> SecurityState.DaneViaAuthoritativeDns53
            HnsPageSecurityPath.DaneThirdPartyDoh -> SecurityState.ValidationFailed
            HnsPageSecurityPath.StatelessDane -> SecurityState.StatelessDane
            HnsPageSecurityPath.DaneIcannDoh -> SecurityState.DaneViaIcannDoh
            HnsPageSecurityPath.HnsAuthoritativeDoh -> SecurityState.HnsViaAuthoritativeDoh
            HnsPageSecurityPath.HnsAuthoritativeDns53 -> SecurityState.HnsViaAuthoritativeDns53
            HnsPageSecurityPath.HnsThirdPartyDoh -> SecurityState.ValidationFailed
            HnsPageSecurityPath.DaneP2pDnsRelay -> SecurityState.DaneViaP2pDnsRelay
            HnsPageSecurityPath.HnsP2pDnsRelay -> SecurityState.HnsViaP2pDnsRelay
        }

    private fun String?.hasSyncStatus(status: String): Boolean =
        this?.contains("\"status\":\"$status\"") == true

    private fun String?.hasAuthoritativelyCurrentHeaders(): Boolean {
        val json = this ?: return false
        if (json.longField("syncStatusSchemaVersion") != 2L) return false
        val best = json.longField("bestHeight") ?: return false
        val target = json.longField("effectiveTargetHeight") ?: return false
        val lag = json.longField("lagBlocks") ?: return false
        val threshold = json.longField("freshnessThresholdBlocks") ?: return false
        val targetPeerGroups = json.longField("targetPeerGroups") ?: return false
        val evidenceExpired = json.booleanField("targetEvidenceExpired") ?: return false
        return json.stringField("freshness") == "current" &&
            json.stringField("targetSource") == "corroboratedPeers" &&
            best > 0L &&
            target >= best &&
            lag >= 0L &&
            threshold == 2L &&
            lag == target - best &&
            lag <= threshold &&
            targetPeerGroups >= 3L &&
            !evidenceExpired
    }

    private fun String.longField(name: String): Long? {
        val pattern = """"$name"\s*:\s*(null|-?\d+)(?=\s*[,}])""".toRegex()
        val value = pattern.find(this)?.groupValues?.getOrNull(1) ?: return null
        return value.takeUnless { it == "null" }?.toLongOrNull()
    }

    private fun String.stringField(name: String): String? {
        val pattern = """"$name"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.booleanField(name: String): Boolean? {
        val pattern = """"$name"\s*:\s*(true|false|null)(?=\s*[,}])""".toRegex()
        return when (pattern.find(this)?.groupValues?.getOrNull(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
