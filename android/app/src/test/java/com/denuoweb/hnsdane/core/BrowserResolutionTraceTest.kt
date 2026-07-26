package com.denuoweb.hnsdane.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserResolutionTraceTest {
    @Test
    fun acceptsConsistentRetainedSelections() {
        assertEquals(
            BrowserResolvedNamespace.Hns,
            BrowserResolutionTrace.selectedNamespace(
                """{"namespaceResolution":{"outcome":"hnsOnly","selected":"hns"}}""",
            ),
        )
        assertEquals(
            BrowserResolvedNamespace.Icann,
            BrowserResolutionTrace.selectedNamespace(
                """{"namespaceResolution":{"outcome":"bothDivergent","selected":"icann"}}""",
            ),
        )
        assertTrue(
            BrowserResolutionTrace.authorizesWebPkiFallback(
                """{"namespaceResolution":{"outcome":"icannOnly","selected":"icann"}}""",
            ),
        )
    }

    @Test
    fun rejectsLegacyMalformedMissingAndContradictorySelections() {
        val rejected = listOf(
            null,
            "",
            "not-json",
            """{"nameClass":"icann"}""",
            """{"selectedNamespace":"icann"}""",
            """{"namespaceResolution":{"outcome":"indeterminate","selected":null}}""",
            """{"namespaceResolution":{"outcome":"hnsOnly","selected":"icann"}}""",
            """{"namespaceResolution":{"outcome":"icannOnly","selected":"hns"}}""",
        )
        rejected.forEach { trace ->
            assertNull(trace, BrowserResolutionTrace.selectedNamespace(trace))
            assertFalse(
                trace,
                BrowserResolutionTrace.authorizesWebPkiFallback(trace),
            )
        }
    }
}
