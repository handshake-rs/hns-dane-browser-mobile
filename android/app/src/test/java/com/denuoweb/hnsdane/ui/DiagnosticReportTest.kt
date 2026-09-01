package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun markdownIncludesOperationalFieldsAndEscapesCodeFences() {
        val report = DiagnosticReport.markdown(
            labels = ENGLISH_LABELS,
            buildLabel = "debug 1.0.1 (53)",
            rustCore = "hns-dane-browser-rust-core/1.0.0",
            rustDiagnostics = """{"securityDefault":"fail-closed","note":"```"}""",
            proxyOverrideSupported = true,
            thirdPartyCookiesBlocked = true,
            generatedAtMillis = 0,
        )

        assertTrue(report.contains("# Shakescape Diagnostic Bundle"))
        assertTrue(report.contains("Generated: 1970-01-01T00:00:00Z"))
        assertTrue(report.contains("Build: debug 1.0.1 (53)"))
        assertTrue(report.contains("Rust core: hns-dane-browser-rust-core/1.0.0"))
        assertTrue(report.contains("Proxy override supported: true"))
        assertFalse(report.contains("## Sync Status"))
        assertFalse(report.contains("## Recent Gateway Events"))
        assertTrue(report.contains("` ` `"))
        assertFalse(report.contains("\"note\":\"```\""))
    }

    private companion object {
        val ENGLISH_LABELS = DiagnosticReportLabels(
            title = "# Shakescape Diagnostic Bundle",
            generated = { "Generated: $it" },
            build = { "Build: $it" },
            rustCore = { "Rust core: $it" },
            proxyOverride = { "Proxy override supported: $it" },
            thirdPartyCookies = { "Third-party cookies blocked: $it" },
            rustDiagnostics = "## Rust Diagnostics",
        )
    }
}
