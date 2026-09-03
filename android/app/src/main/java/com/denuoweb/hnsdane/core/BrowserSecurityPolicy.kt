package com.denuoweb.hnsdane.core

import com.denuoweb.hnsdane.net.HnsSyncProgress

object BrowserSecurityPolicy {
    fun state(
        targetKind: BrowserTargetKind?,
        proxyAvailable: Boolean,
        syncStatusJson: String? = null,
        mainFrameHnsStatusCode: Int? = null,
        mainFrameHnsTlsPolicy: HnsPageTlsPolicy? = null,
        mainFrameHnsResolverPolicy: HnsPageResolverPolicy? = null,
        mainFrameHnsSecurityPath: HnsPageSecurityPath? = null,
        mainFrameHnsResolutionTraceJson: String? = null,
        isOpaqueIpLiteral: Boolean = false,
        syncProgress: HnsSyncProgress = HnsSyncProgress.fromJson(syncStatusJson),
        expectedNetwork: String? = null,
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
        if (syncProgress.requiresAttention) {
            return SecurityState.ProofUnavailable
        }
        if (
            expectedNetwork?.let { syncProgress.isAuthorityReadyFor(it) }
                ?: syncProgress.isAuthorityReady
        ) {
            return SecurityState.Loading
        }

        return SecurityState.Syncing
    }

    private fun HnsPageSecurityPath.securityState(): SecurityState =
        when (this) {
            HnsPageSecurityPath.DaneAuthoritativeDoh -> SecurityState.DaneViaAuthoritativeDoh
            HnsPageSecurityPath.DaneAuthoritativeDns53 -> SecurityState.DaneViaAuthoritativeDns53
            HnsPageSecurityPath.DaneThirdPartyDoh ->
                SecurityState.DaneViaUserConfiguredRecoveryDoh
            HnsPageSecurityPath.StatelessDane -> SecurityState.StatelessDane
            HnsPageSecurityPath.DaneIcannDoh -> SecurityState.DaneViaIcannDoh
            HnsPageSecurityPath.HnsAuthoritativeDoh -> SecurityState.HnsViaAuthoritativeDoh
            HnsPageSecurityPath.HnsAuthoritativeDns53 -> SecurityState.HnsViaAuthoritativeDns53
            HnsPageSecurityPath.HnsThirdPartyDoh ->
                SecurityState.HnsViaUserConfiguredRecoveryDoh
            HnsPageSecurityPath.DaneP2pDnsRelay -> SecurityState.DaneViaP2pDnsRelay
            HnsPageSecurityPath.HnsP2pDnsRelay -> SecurityState.HnsViaP2pDnsRelay
        }

}
