package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Test

class HnsWebViewRequestAdmissionTest {
    @Test
    fun protectedRequestsUseOnlyTheCancellableProcessProxy() {
        assertEquals(
            ProtectedWebViewRequestAction.ProcessProxy,
            protectedWebViewRequestAction(proxyAvailable = true, treeRootReady = true),
        )
    }

    @Test
    fun missingProxyOrTreeRootFailsClosedWithoutCompatibilityJniWork() {
        for ((proxyAvailable, treeRootReady) in listOf(false to false, false to true, true to false)) {
            assertEquals(
                ProtectedWebViewRequestAction.Block,
                protectedWebViewRequestAction(proxyAvailable, treeRootReady),
            )
        }
    }
}
