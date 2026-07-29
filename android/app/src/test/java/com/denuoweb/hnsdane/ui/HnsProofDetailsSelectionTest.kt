package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsProofDetailsSelectionTest {
    @Test
    fun acceptsOnlyConsistentRetainedIcannSelections() {
        listOf("icannOnly", "bothConvergent", "bothDivergent").forEach { outcome ->
            assertTrue(
                outcome,
                proofDetailsUsesIcann(trace(outcome, "icann")),
            )
        }

        listOf("hnsOnly", "bothConvergent", "bothDivergent").forEach { outcome ->
            assertFalse(
                outcome,
                proofDetailsUsesIcann(trace(outcome, "hns")),
            )
        }
    }

    @Test
    fun neverInfersIcannFromLegacyMalformedOrContradictoryTraces() {
        val rejected = listOf(
            null,
            "",
            "not-json",
            """{"nameClass":"icann"}""",
            """{"namespaceResolution":{"outcome":"neither","selected":null}}""",
            """{"namespaceResolution":{"outcome":"indeterminate","selected":null}}""",
            trace("hnsOnly", "icann"),
            trace("icannOnly", "hns"),
            """{"namespaceResolution":{"outcome":"icannOnly","selected":"icann"}}${" ".repeat(64 * 1024)}""",
        )

        rejected.forEach { trace ->
            assertFalse(trace, proofDetailsUsesIcann(trace))
        }
    }

    private fun trace(outcome: String, selected: String): String =
        """{"namespaceResolution":{"outcome":"$outcome","selected":"$selected"}}"""
}
