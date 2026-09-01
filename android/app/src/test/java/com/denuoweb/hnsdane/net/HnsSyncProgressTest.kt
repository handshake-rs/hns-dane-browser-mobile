package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsSyncProgressTest {
    @Test
    fun parsesHeightsAndReportsBehindProgress() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"syncing","attempted":4,"successful":1,"accepted":2000,"failed":3,"peerCount":4,"peerGroups":4,"bestHeight":93344,"bestPeerHeight":335684,"estimatedTipHeight":335900,"effectiveTargetHeight":335684,"lagBlocks":242340,"freshness":"stale","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":93313,"treeRootReady":false,"blocksUntilAuthoritativeTreeRoot":242321,"targetSource":"corroboratedPeers","targetPeerGroups":4,"targetEvidenceExpired":false}""",
        )

        assertEquals("syncing", progress.status)
        assertEquals(93_344L, progress.bestHeight)
        assertEquals(335_684L, progress.bestPeerHeight)
        assertEquals(2_000L, progress.accepted)
        assertTrue(progress.isBehind)
        assertTrue(progress.isBehindKnownPeer)
        assertTrue(progress.shouldContinueSoon)
        assertFalse(progress.isAuthorityReady)
        assertEquals(278, progress.progressPermille())
        assertEquals(
            "syncing • current height 93,344 • target 335,684 • peers 4",
            progress.summary(),
        )
    }

    @Test
    fun upToDateProgressUsesIdlePolling() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","syncInFlight":false,"stagedBestHeight":null,"stagedAccepted":0,"bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        assertFalse(progress.isBehind)
        assertTrue(progress.isCurrent)
        assertTrue(progress.isAuthorityReady)
        assertTrue(progress.shouldShowProgress)
        assertFalse(progress.shouldContinueSoon)
        assertEquals(1000, progress.progressPermille())
        assertEquals(
            "up_to_date • current height 335,684 • target 335,684",
            progress.summary(),
        )
    }

    @Test
    fun doesNotPresentEstimatedTipWhenAuthoritativeTargetIsUnknown() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"syncing","accepted":2000,"bestHeight":92000,"bestPeerHeight":null,"estimatedTipHeight":335684,"effectiveTargetHeight":null,"lagBlocks":null,"freshness":"unknown","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":null,"localTreeRootHeight":91981,"treeRootReady":null,"blocksUntilAuthoritativeTreeRoot":null,"targetSource":"unknown","targetPeerGroups":0,"targetEvidenceExpired":true,"peerCount":0}""",
        )

        assertFalse(progress.isBehind)
        assertFalse(progress.isBehindKnownPeer)
        assertTrue(progress.hasUnknownTargetProgress)
        assertTrue(progress.shouldContinueSoon)
        assertEquals(null, progress.progressPermille())
        val summary = progress.summary()
        assertEquals(
            "syncing • current height 92,000",
            summary,
        )
        assertFalse(summary.contains("target unknown"))
        assertFalse(summary.contains("HNS root unknown"))
    }

    @Test
    fun legacyStatusWithoutAuthoritativeFieldsFailsClosedWithoutHotPolling() {
        val progress = HnsSyncProgress.fromJson(
            """{"status":"up_to_date","accepted":0,"bestHeight":335680,"bestPeerHeight":null,"estimatedTipHeight":335684,"peerCount":23}""",
        )

        assertFalse(progress.isBehind)
        assertFalse(progress.isCurrent)
        assertEquals("unknown", progress.freshness)
        assertEquals(null, progress.effectiveTargetHeight)
        assertFalse(progress.isBehindKnownPeer)
        assertFalse(progress.hasUnknownTargetProgress)
        assertFalse(progress.shouldContinueSoon)
    }

    @Test
    fun mismatchedCurrentnessContractFailsClosed() {
        val baseline =
            """"network":"mainnet","status":"up_to_date","bestHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false"""

        assertFalse(HnsSyncProgress.fromJson("{$baseline}").isCurrent)
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":3,$baseline}""".replace(
                    """"freshnessThresholdBlocks":2""",
                    """"freshnessThresholdBlocks":144""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":3,$baseline}""".replace(
                    """"freshnessThresholdBlocks":2""",
                    """"freshnessThresholdBlocks":2.5""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":3,$baseline}""".replace(
                    """"targetPeerGroups":3""",
                    """"targetPeerGroups":2""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":3,$baseline}""".replace(
                    """"localTreeRootHeight":335665""",
                    """"localTreeRootHeight":335666""",
                ),
            ).isAuthorityReady,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":3,$baseline}""".replace(
                    """"targetEvidenceExpired":false""",
                    """"targetEvidenceExpired":true""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":3,$baseline}"""
                    .replace(""""bestHeight":335684""", """"bestHeight":0""")
                    .replace(""""effectiveTargetHeight":335684""", """"effectiveTargetHeight":0"""),
            ).isCurrent,
        )
    }

    @Test
    fun attemptedPassWithNoSuccessfulPeerCannotRetainCurrentness() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"attempted","attempted":1,"successful":0,"accepted":0,"failed":4,"bestHeight":343703,"effectiveTargetHeight":343703,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":343681,"localTreeRootHeight":343681,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":10,"targetEvidenceExpired":false}""",
        )

        assertTrue(progress.isAuthorityReady)
        assertFalse(progress.isCurrent)
        assertTrue(progress.shouldShowProgress)
    }

    @Test
    fun failedFreshnessProbeRetainsCorroboratedCurrentChainPresentation() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","attempted":1,"successful":0,"accepted":0,"failed":4,"bestHeight":345108,"effectiveTargetHeight":345108,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":345097,"localTreeRootHeight":345097,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":12,"targetEvidenceExpired":false}""",
        )

        assertTrue(progress.isAuthorityReady)
        assertTrue(progress.isCurrent)
        assertTrue(progress.shouldShowProgress)
        assertFalse(progress.shouldRetrySoon)
    }

    @Test
    fun tipCanLagWhileTheLatestCommittedTreeRootRemainsReady() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"syncing","accepted":16,"bestHeight":335670,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":14,"freshness":"stale","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        assertTrue(progress.isBehind)
        assertFalse(progress.isCurrent)
        assertTrue(progress.isAuthorityReady)
        assertTrue(progress.shouldShowProgress)
        assertTrue(progress.shouldContinueSoon)
    }

    @Test
    fun diagnosticProgressReturnsWhenReadyNodeStartsSyncingAgain() {
        val current = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )
        val syncingWithUsableRoot = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"syncing","accepted":0,"bestHeight":335684,"bestPeerHeight":335700,"effectiveTargetHeight":335700,"lagBlocks":16,"freshness":"stale","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )
        val currentAgain = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","bestHeight":335700,"bestPeerHeight":335700,"effectiveTargetHeight":335700,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )
        val currentWithNewSyncInFlight = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","syncInFlight":true,"stagedBestHeight":335700,"stagedAccepted":0,"bestHeight":335700,"bestPeerHeight":335700,"effectiveTargetHeight":335700,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        assertTrue(current.isAuthorityReady)
        assertTrue(current.shouldShowProgress)
        assertTrue(syncingWithUsableRoot.isAuthorityReady)
        assertTrue(syncingWithUsableRoot.shouldShowProgress)
        assertTrue(currentAgain.isAuthorityReady)
        assertTrue(currentAgain.shouldShowProgress)
        assertTrue(currentWithNewSyncInFlight.isCurrent)
        assertTrue(currentWithNewSyncInFlight.isAuthorityReady)
        assertTrue(currentWithNewSyncInFlight.shouldShowProgress)
    }

    @Test
    fun inFlightTelemetryShowsValidatedProgressWithoutChangingAuthority() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","accepted":0,"syncInFlight":true,"stagedBestHeight":324000,"stagedAccepted":24000,"bestHeight":300000,"bestPeerHeight":337000,"effectiveTargetHeight":337000,"lagBlocks":37000,"freshness":"stale","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":299989,"localTreeRootHeight":299989,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        assertTrue(progress.syncInFlight)
        assertEquals(324_000L, progress.stagedBestHeight)
        assertEquals(24_000L, progress.stagedAccepted)
        assertTrue(progress.shouldShowProgress)
        assertTrue(progress.isAuthorityReady)
        assertEquals(961, progress.progressPermille())
        val summary = progress.summary()
        assertEquals(
            "syncing • staged validated 324,000 • target 337,000",
            summary,
        )
        assertFalse(summary.contains("committed"))
        assertFalse(summary.contains("300,000"))
    }

    @Test
    fun stagedHeightCannotSatisfyCommittedAuthorityReadiness() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"syncing","syncInFlight":true,"stagedBestHeight":324000,"stagedAccepted":24020,"bestHeight":299980,"bestPeerHeight":337000,"effectiveTargetHeight":337000,"lagBlocks":37020,"freshness":"stale","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":299989,"localTreeRootHeight":299989,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        assertFalse(progress.isAuthorityReady)
        assertTrue(progress.shouldShowProgress)
        assertEquals(961, progress.progressPermille())
    }

    @Test
    fun idleWithoutPeersRetriesDiscovery() {
        val progress = HnsSyncProgress.fromJson(
            """{"status":"idle","bestHeight":null,"bestPeerHeight":null,"estimatedTipHeight":335684,"peerCount":0}""",
        )

        assertTrue(progress.shouldRetrySoon)
    }
}
