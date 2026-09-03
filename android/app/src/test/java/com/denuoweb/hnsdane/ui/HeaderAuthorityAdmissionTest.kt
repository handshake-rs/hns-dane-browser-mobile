package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.net.HnsSyncProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderAuthorityAdmissionTest {
    @Test
    fun authenticatedAuthorityPersistsUntilExplicitRecoveryOrNetworkChange() {
        val ready = HnsSyncProgress.fromJson(
            """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","bestHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
        )

        // Foreground refresh preparation is deliberately absent from this
        // authority boundary: it is presentation-only and cannot revoke the
        // already authenticated snapshot.
        assertTrue(browserHasCurrentHeaderAuthority(false, ready, "mainnet"))
        assertFalse(browserHasCurrentHeaderAuthority(true, ready, "mainnet"))
        assertFalse(browserHasCurrentHeaderAuthority(false, ready, "testnet"))
    }
}
