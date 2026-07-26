package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsSyncProgressTest {
    @Test
    fun parsesHeightsAndReportsBehindProgress() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":2,"status":"syncing","attempted":4,"successful":1,"accepted":2000,"failed":3,"peerCount":4,"peerGroups":4,"bestHeight":93344,"bestPeerHeight":335684,"estimatedTipHeight":335900,"effectiveTargetHeight":335684,"lagBlocks":242340,"freshness":"stale","freshnessThresholdBlocks":2,"targetSource":"corroboratedPeers","targetPeerGroups":4,"targetEvidenceExpired":false}""",
        )

        assertEquals("syncing", progress.status)
        assertEquals(93_344L, progress.bestHeight)
        assertEquals(335_684L, progress.bestPeerHeight)
        assertEquals(2_000L, progress.accepted)
        assertTrue(progress.isBehind)
        assertTrue(progress.isBehindKnownPeer)
        assertTrue(progress.shouldContinueSoon)
        assertEquals(278, progress.progressPermille())
        assertEquals(
            "syncing • bestHeight 93,344 • target 335,684 • freshness stale • raw peer 335,684 • accepted +2,000 • peers 4",
            progress.summary(),
        )
    }

    @Test
    fun upToDateProgressUsesIdlePolling() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":2,"status":"up_to_date","bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        assertFalse(progress.isBehind)
        assertTrue(progress.isCurrent)
        assertFalse(progress.shouldContinueSoon)
        assertEquals(1000, progress.progressPermille())
    }

    @Test
    fun estimatedTipRemainsDiagnosticWhenAuthoritativeTargetIsUnknown() {
        val progress = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":2,"status":"syncing","accepted":2000,"bestHeight":92000,"bestPeerHeight":null,"estimatedTipHeight":335684,"effectiveTargetHeight":null,"lagBlocks":null,"freshness":"unknown","freshnessThresholdBlocks":2,"targetSource":"unknown","targetPeerGroups":0,"targetEvidenceExpired":true,"peerCount":0}""",
        )

        assertFalse(progress.isBehind)
        assertFalse(progress.isBehindKnownPeer)
        assertTrue(progress.hasUnknownTargetProgress)
        assertTrue(progress.shouldContinueSoon)
        assertEquals(null, progress.progressPermille())
        assertEquals(
            "syncing • bestHeight 92,000 • target unknown • freshness unknown • estimate 335,684 • accepted +2,000",
            progress.summary(),
        )
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
            """"status":"up_to_date","bestHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false"""

        assertFalse(HnsSyncProgress.fromJson("{$baseline}").isCurrent)
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":2,$baseline}""".replace(
                    """"freshnessThresholdBlocks":2""",
                    """"freshnessThresholdBlocks":144""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":2,$baseline}""".replace(
                    """"freshnessThresholdBlocks":2""",
                    """"freshnessThresholdBlocks":2.5""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":2,$baseline}""".replace(
                    """"targetPeerGroups":3""",
                    """"targetPeerGroups":2""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":2,$baseline}""".replace(
                    """"targetEvidenceExpired":false""",
                    """"targetEvidenceExpired":true""",
                ),
            ).isCurrent,
        )
        assertFalse(
            HnsSyncProgress.fromJson(
                """{"syncStatusSchemaVersion":2,$baseline}"""
                    .replace(""""bestHeight":335684""", """"bestHeight":0""")
                    .replace(""""effectiveTargetHeight":335684""", """"effectiveTargetHeight":0"""),
            ).isCurrent,
        )
    }

    @Test
    fun idleWithoutPeersRetriesDiscovery() {
        val progress = HnsSyncProgress.fromJson(
            """{"status":"idle","bestHeight":null,"bestPeerHeight":null,"estimatedTipHeight":335684,"peerCount":0}""",
        )

        assertTrue(progress.shouldRetrySoon)
    }
}
