package com.denuoweb.hnsdane.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserSecurityPolicyTest {
    @Test
    fun bundledStartPageIsReportedAsLocalContent() {
        assertEquals(
            SecurityState.LocalContent,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.LocalAsset,
                proxyAvailable = false,
                syncStatusJson = null,
            ),
        )
    }

    @Test
    fun normalWebTargetsStayPendingUntilRustReportsTheTlsDecision() {
        assertEquals(
            SecurityState.Loading,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.ExactUrl,
                proxyAvailable = false,
                syncStatusJson = null,
            ),
        )
    }

    @Test
    fun publicIpLiteralUsesWebPkiOnlyInsideTheLiveOpaqueProxyPath() {
        assertEquals(
            SecurityState.WebPkiOnly,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.ExactUrl,
                proxyAvailable = true,
                syncStatusJson = null,
                isOpaqueIpLiteral = true,
            ),
        )
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.ExactUrl,
                proxyAvailable = false,
                syncStatusJson = null,
                isOpaqueIpLiteral = true,
            ),
        )
        assertEquals(true, isCanonicalIpLiteral("1.1.1.1"))
        assertEquals(true, isCanonicalIpLiteral("2001:db8::1"))
        assertEquals(false, isCanonicalIpLiteral("example.com"))
    }

    @Test
    fun hnsTargetsRequireProxyAvailability() {
        assertEquals(
            SecurityState.ProofUnavailable,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = false,
                syncStatusJson = """{"status":"up_to_date"}""",
            ),
        )
    }

    @Test
    fun hnsTargetsShowLoadingWhenSyncIsReadyButPageIsNotVerified() {
        for (status in listOf("synced", "up_to_date")) {
            assertEquals(
                status,
                SecurityState.Loading,
                BrowserSecurityPolicy.state(
                    targetKind = BrowserTargetKind.HnsName,
                    proxyAvailable = true,
                    syncStatusJson = """{"status":"$status"}""",
                ),
            )
        }
    }

    @Test
    fun hnsTargetsStaySyncingWhenPeerHeightIsStillAhead() {
        assertEquals(
            SecurityState.Syncing,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"synced","bestHeight":93344,"bestPeerHeight":335684}""",
            ),
        )
    }

    @Test
    fun hnsTargetsStaySyncingWhenEstimatedTipIsStillAhead() {
        assertEquals(
            SecurityState.Syncing,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"synced","bestHeight":92000,"bestPeerHeight":null,"estimatedTipHeight":335684}""",
            ),
        )
    }

    @Test
    fun mainFrameHnsGatewayFailureOverridesReadySyncState() {
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"up_to_date"}""",
                mainFrameHnsStatusCode = 502,
            ),
        )
    }

    @Test
    fun mainFrameHnsGatewaySuccessCanShowVerifiedBeforeNextSyncSnapshot() {
        assertEquals(
            SecurityState.HnsVerified,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
            ),
        )
    }

    @Test
    fun mainFrameHnsGatewaySuccessShowsDaneVerifiedWhenNativeReportsDane() {
        assertEquals(
            SecurityState.DaneVerified,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsTlsPolicy = HnsPageTlsPolicy.Dane,
            ),
        )
    }

    @Test
    fun icannDaneHostCanShowDaneVerified() {
        assertEquals(
            SecurityState.DaneVerified,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.ExactUrl,
                proxyAvailable = false,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsTlsPolicy = HnsPageTlsPolicy.Dane,
            ),
        )
    }

    @Test
    fun icannAuthenticatedAbsenceShowsWebPkiOnly() {
        assertEquals(
            SecurityState.WebPkiOnly,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.ExactUrl,
                proxyAvailable = false,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsTlsPolicy = HnsPageTlsPolicy.WebPkiFallback,
                mainFrameHnsResolutionTraceJson = ICANN_ONLY_RESOLUTION_TRACE,
            ),
        )
    }

    @Test
    fun webPkiFallbackRequiresAConsistentNestedIcannSelection() {
        val rejectedTraces = listOf(
            null,
            "",
            "not-json",
            """{"nameClass":"icann"}""",
            """{"namespaceResolution":{"outcome":"hnsOnly","selected":"hns"}}""",
            """{"namespaceResolution":{"outcome":"hnsOnly","selected":"icann"}}""",
        )
        rejectedTraces.forEach { trace ->
            assertEquals(
                trace,
                SecurityState.ValidationFailed,
                BrowserSecurityPolicy.state(
                    targetKind = BrowserTargetKind.NativeGateway,
                    proxyAvailable = true,
                    syncStatusJson = """{"status":"idle"}""",
                    mainFrameHnsStatusCode = 200,
                    mainFrameHnsTlsPolicy = HnsPageTlsPolicy.WebPkiFallback,
                    mainFrameHnsResolutionTraceJson = trace,
                ),
            )
        }
    }

    @Test
    fun disabledHnsDohResolverStatusFailsClosedEvenWithDane() {
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsTlsPolicy = HnsPageTlsPolicy.Dane,
                mainFrameHnsResolverPolicy = HnsPageResolverPolicy.HnsDohCompatibility,
            ),
        )
    }

    @Test
    fun explicitSecurityPathsSelectExactSuccessfulState() {
        val expectations = mapOf(
            HnsPageSecurityPath.DaneAuthoritativeDoh to SecurityState.DaneViaAuthoritativeDoh,
            HnsPageSecurityPath.DaneAuthoritativeDns53 to SecurityState.DaneViaAuthoritativeDns53,
            HnsPageSecurityPath.DaneThirdPartyDoh to SecurityState.ValidationFailed,
            HnsPageSecurityPath.StatelessDane to SecurityState.StatelessDane,
            HnsPageSecurityPath.DaneIcannDoh to SecurityState.DaneViaIcannDoh,
            HnsPageSecurityPath.HnsAuthoritativeDoh to SecurityState.HnsViaAuthoritativeDoh,
            HnsPageSecurityPath.HnsAuthoritativeDns53 to SecurityState.HnsViaAuthoritativeDns53,
            HnsPageSecurityPath.HnsThirdPartyDoh to SecurityState.ValidationFailed,
            HnsPageSecurityPath.DaneP2pDnsRelay to SecurityState.DaneViaP2pDnsRelay,
            HnsPageSecurityPath.HnsP2pDnsRelay to SecurityState.HnsViaP2pDnsRelay,
        )

        expectations.forEach { (path, expectedState) ->
            assertEquals(
                path.name,
                expectedState,
                BrowserSecurityPolicy.state(
                    targetKind = BrowserTargetKind.HnsName,
                    proxyAvailable = true,
                    syncStatusJson = """{"status":"idle"}""",
                    mainFrameHnsStatusCode = 200,
                    mainFrameHnsSecurityPath = path,
                ),
            )
        }
    }

    @Test
    fun disabledLegacyStatusTakesPrecedenceOverOtherwiseValidSecurityPath() {
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsTlsPolicy = HnsPageTlsPolicy.WebPkiFallback,
                mainFrameHnsResolverPolicy = HnsPageResolverPolicy.HnsDohCompatibility,
                mainFrameHnsSecurityPath = HnsPageSecurityPath.StatelessDane,
            ),
        )
    }

    @Test
    fun gatewayFailureTakesPrecedenceOverExplicitSecurityPath() {
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"up_to_date"}""",
                mainFrameHnsStatusCode = 502,
                mainFrameHnsSecurityPath = HnsPageSecurityPath.DaneAuthoritativeDoh,
            ),
        )
    }

    @Test
    fun hnsWebPkiFallbackStatusFailsClosed() {
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsTlsPolicy = HnsPageTlsPolicy.WebPkiFallback,
            ),
        )
    }

    @Test
    fun disabledHnsDohResolverStatusFailsClosedForHttp() {
        assertEquals(
            SecurityState.ValidationFailed,
            BrowserSecurityPolicy.state(
                targetKind = BrowserTargetKind.HnsName,
                proxyAvailable = true,
                syncStatusJson = """{"status":"idle"}""",
                mainFrameHnsStatusCode = 200,
                mainFrameHnsResolverPolicy = HnsPageResolverPolicy.HnsDohCompatibility,
            ),
        )
    }

    @Test
    fun hnsTargetsShowProofUnavailableForSyncFailures() {
        for (status in listOf("error", "seed_failed", "peer_failed")) {
            assertEquals(
                status,
                SecurityState.ProofUnavailable,
                BrowserSecurityPolicy.state(
                    targetKind = BrowserTargetKind.HnsName,
                    proxyAvailable = true,
                    syncStatusJson = """{"status":"$status"}""",
                ),
            )
        }
    }

    @Test
    fun hnsTargetsRemainSyncingForUnknownOrInitialStatus() {
        for (statusJson in listOf(null, """{"status":"idle"}""", """{"status":"status"}""")) {
            assertEquals(
                SecurityState.Syncing,
                BrowserSecurityPolicy.state(
                    targetKind = BrowserTargetKind.HnsName,
                    proxyAvailable = true,
                    syncStatusJson = statusJson,
                ),
            )
        }
    }

    private companion object {
        const val ICANN_ONLY_RESOLUTION_TRACE =
            """{"namespaceResolution":{"outcome":"icannOnly","selected":"icann","reason":"onlyAvailableRoot","hnsState":"authenticatedAbsent","icannState":"present","fingerprint":null}}"""
    }
}
