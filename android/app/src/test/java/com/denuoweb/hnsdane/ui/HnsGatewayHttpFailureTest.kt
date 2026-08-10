package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsGatewayHttpFailureTest {
    @Test
    fun recognizesRetryableProxyGeneratedFailures() {
        for (
            reason in listOf(
                "HNS Resolution Unavailable",
                "HNS Proof Unavailable",
                "Namespace Resolution Indeterminate",
                "HNS Sync Incomplete",
            )
        ) {
            assertTrue(isRetryableHnsGatewayHttpFailure(503, reason))
        }
    }

    @Test
    fun leavesOriginAndPermanentErrorsUntouched() {
        assertFalse(isRetryableHnsGatewayHttpFailure(503, "Service Unavailable"))
        assertFalse(isRetryableHnsGatewayHttpFailure(502, "Namespace Resolution Indeterminate"))
        assertFalse(isRetryableHnsGatewayHttpFailure(404, "HNS Name Not Found"))
        assertFalse(isRetryableHnsGatewayHttpFailure(503, null))
    }
}
