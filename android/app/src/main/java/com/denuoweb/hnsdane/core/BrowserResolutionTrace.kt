package com.denuoweb.hnsdane.core

import org.json.JSONObject

internal enum class BrowserResolvedNamespace {
    Hns,
    Icann,
}

/**
 * Parses only the retained dual-root decision emitted by the shared Rust runtime.
 *
 * Legacy top-level name classes are deliberately not authoritative: they do not
 * prove that the complete hostname was resolved through both namespace roots.
 */
internal object BrowserResolutionTrace {
    fun selectedNamespace(traceJson: String?): BrowserResolvedNamespace? {
        val trace = traceJson?.takeIf {
            it.isNotBlank() &&
                it.length <= MAX_TRACE_BYTES &&
                it.toByteArray(Charsets.UTF_8).size <= MAX_TRACE_BYTES
        } ?: return null
        val resolution = runCatching {
            JSONObject(trace).optJSONObject("namespaceResolution")
        }.getOrNull() ?: return null
        val outcome = resolution.opt("outcome") as? String ?: return null
        return when (resolution.opt("selected") as? String) {
            "hns" ->
                if (outcome in HNS_SELECTION_OUTCOMES) {
                    BrowserResolvedNamespace.Hns
                } else {
                    null
                }
            "icann" ->
                if (outcome in ICANN_SELECTION_OUTCOMES) {
                    BrowserResolvedNamespace.Icann
                } else {
                    null
                }
            else -> null
        }
    }

    fun authorizesWebPkiFallback(traceJson: String?): Boolean =
        selectedNamespace(traceJson) == BrowserResolvedNamespace.Icann

    private const val MAX_TRACE_BYTES = 64 * 1024
    private val HNS_SELECTION_OUTCOMES =
        setOf("hnsOnly", "bothConvergent", "bothDivergent")
    private val ICANN_SELECTION_OUTCOMES =
        setOf("icannOnly", "bothConvergent", "bothDivergent")
}
